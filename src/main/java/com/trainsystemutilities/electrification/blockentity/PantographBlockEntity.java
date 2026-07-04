package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * パンタグラフ BE。Geckolib アニメーション + トグル状態管理。
 *
 * <p>状態 ({@link #deployed}):
 * <ul>
 *   <li>{@code true} = 展開中 (= モデル既定姿勢、bones 全 0,0,0)</li>
 *   <li>{@code false} = 折りたたみ中</li>
 * </ul>
 *
 * <p>右クリックで {@link #toggleDeployState()} を呼ぶと状態反転 + 該当アニメをトリガー。
 * 状態は NBT に保存され save/load 時に復元される。
 *
 * <p>登録アニメ (BlockBench から):
 * <ul>
 *   <li>{@code "deploy"} = 折→展開 (現状ファイルの {@code "fold"} を割当て)</li>
 *   <li>{@code "fold"} = 展開→折 (現状未作成 → 同じ {@code "fold"} を流用、要 BlockBench で逆向き作成)</li>
 * </ul>
 */
public class PantographBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final String CONTROLLER_ID = "main";
    /** トリガー名 (= Java から呼ぶ ID)。BlockBench のアニメ名と独立。 */
    private static final String TRIGGER_DEPLOY = "deploy_trigger";
    private static final String TRIGGER_FOLD = "fold_trigger";
    /** BlockBench で作成されたアニメ名。
     *  fold = folded→extended (= 展開動作)、unfold = extended→folded (= 折畳動作)。 */
    private static final String ANIM_DEPLOY_NAME = "fold";
    private static final String ANIM_FOLD_NAME = "unfold";

    /** 現在展開中か。NBT 保存対象。
     *  デフォルト = false (= 折りたたみ)。これにより:
     *   - ワールド設置時は折りたたまれた状態
     *   - からくり組立時もそのまま折りたたまれた状態でキャプチャされる
     *   - 管理 GUI / 右クリックで明示的に展開する必要がある (= 集電は deployed 時のみ) */
    private boolean deployed = false;
    /** 直近の tick で架線にコンタクトしていたか。 */
    private boolean inContact = false;
    /** 現在予約しているアニメ。null なら idle (= 何もしない)。
     *  setAndContinue ベースで毎フレーム同じアニメを返し続ける必要があるため保持。 */
    private transient String pendingAnim = null;
    /** S2: 現在描画中の展開度合い 0.0(折畳) ～ 1.0(完全展開)。
     *  setCustomAnimations が targetT に向かって毎フレーム補間して滑らかに変化させる。 */
    private transient float currentRenderT = 0f;

    public float getCurrentRenderT() { return currentRenderT; }
    public void setCurrentRenderT(float t) { this.currentRenderT = t; }

    public PantographBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PANTOGRAPH.get(), pos, state);
    }

    public boolean isDeployed() { return deployed; }

    /** クライアント側で外部から (= sync payload 経由で) deployed フラグを直接書き込むための setter。
     *  pendingAnim をリセットして次フレームの idlePredicate でアニメ切替が反映される。 */
    public void clientSetDeployed(boolean d) {
        if (this.deployed != d) {
            this.deployed = d;
            this.pendingAnim = null;
        }
    }

    public boolean isInContact() { return inContact; }

    public void setInContact(boolean contact) {
        if (this.inContact != contact) {
            this.inContact = contact;
            setChanged();
        }
    }

    /** 右クリック時に呼ばれる。展開↔折を切り替える。
     *  アニメ駆動は idlePredicate が deployed フラグを見て自動で切替えるため、
     *  ここでは triggerAnim を呼ばない (= 二重発火を防ぐ)。 */
    public void toggleDeployState() {
        deployed = !deployed;
        setChanged();
        // クライアントに sync (= getUpdateTag/getUpdatePacket 経由)
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /** Phase D3 集電 tick からの自動切り替え用。 */
    public void setDeployed(boolean shouldDeploy) {
        if (this.deployed != shouldDeploy) {
            toggleDeployState();
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER_ID, 0, this::idlePredicate)
                .triggerableAnim(TRIGGER_DEPLOY, RawAnimation.begin().thenPlay(ANIM_DEPLOY_NAME))
                .triggerableAnim(TRIGGER_FOLD, RawAnimation.begin().thenPlay(ANIM_FOLD_NAME)));
    }

    // ===== Geckolib AnimationController の内部時間フィールドへの reflection =====
    // tickOffset を毎フレーム書き換えることでアニメ再生位置を任意 T へ seek できる。
    // bones は Geckolib processor が自動補間 → アーム/集電版すべて連動。
    private static java.lang.reflect.Field FIELD_LAST_POLL_TIME;
    private static java.lang.reflect.Field FIELD_TICK_OFFSET;
    private static java.lang.reflect.Field FIELD_ANIM_STATE;
    private static java.lang.reflect.Field FIELD_SHOULD_RESET_TICK;
    private static java.lang.reflect.Field FIELD_CURRENT_ANIM;
    private static boolean REFLECTION_OK = false;
    static {
        try {
            FIELD_LAST_POLL_TIME = AnimationController.class.getDeclaredField("lastPollTime");
            FIELD_LAST_POLL_TIME.setAccessible(true);
            FIELD_TICK_OFFSET = AnimationController.class.getDeclaredField("tickOffset");
            FIELD_TICK_OFFSET.setAccessible(true);
            FIELD_ANIM_STATE = AnimationController.class.getDeclaredField("animationState");
            FIELD_ANIM_STATE.setAccessible(true);
            FIELD_SHOULD_RESET_TICK = AnimationController.class.getDeclaredField("shouldResetTick");
            FIELD_SHOULD_RESET_TICK.setAccessible(true);
            FIELD_CURRENT_ANIM = AnimationController.class.getDeclaredField("currentAnimation");
            FIELD_CURRENT_ANIM.setAccessible(true);
            REFLECTION_OK = true;
        } catch (NoSuchFieldException ex) {
            TrainSystemUtilities.LOGGER.warn(
                    "[Pantograph] AnimationController reflection failed: {}", ex.toString());
        }
    }

    /** 前フレームに私が書いた tickOffset 値 (= 今フレームに残っているか確認用)。 */
    private transient double previouslyWrittenTickOffset = Double.NaN;

    /** fold アニメ長 (秒)。 */
    private static final double ANIM_LENGTH_SECONDS = 0.375;
    /** Minecraft tick rate。 */
    private static final double TICKS_PER_SECOND = 20.0;
    /** T 補間速度 (毎フレーム target に向かって lerp)。 */
    private static final float T_LERP_RATE = 0.18f;

    /** ログ用 frame counter (= サンプリング)。 */
    private transient int seekLogCounter = 0;
    private static final int SEEK_LOG_EVERY = 40; // 2 秒に 1 回
    /** 前回ログした値 (= 変化検出用)。 */
    private transient float lastLoggedTargetT = -999f;
    /** 前フレームの seekTime (= delta 計算用、プルプル震え防止)。 */
    private transient double lastSeekTime = Double.NaN;
    /** delta の指数移動平均 (= FPS 変動による prediction error を抑える)。 */
    private transient double deltaEma = 0.333;
    /** 前回 frame の adjustedTick 推定値 (= 平滑化用)。 */
    private transient double lastAppliedAdjusted = -1.0;

    /**
     * 常に fold アニメを active にし、目標 T (= 折畳 0 〜 完全展開 1) に対応する
     * アニメ時間に tickOffset を書き換えて seek する。
     *
     * <p>これにより Geckolib processor は「fold アニメの t = T * 0.375s 時点」の
     * keyframe を評価して全 bones を正しく補間する ⇒ アーム / 集電版すべて連動。
     */
    private PlayState idlePredicate(AnimationState<PantographBlockEntity> state) {
        // 常に fold アニメを active (= setAndContinue は同じアニメなら no-op)
        state.setAndContinue(RawAnimation.begin()
                .then(ANIM_DEPLOY_NAME, Animation.LoopType.HOLD_ON_LAST_FRAME));

        AnimationController<PantographBlockEntity> ctrl = state.getController();

        // 目標 T を計算 + 毎フレーム滑らかに補間
        float targetT = computeTargetT();
        if (Math.abs(currentRenderT - targetT) < 0.001f) currentRenderT = targetT;
        else currentRenderT += (targetT - currentRenderT) * T_LERP_RATE;
        float seekT = Math.max(0f, Math.min(1f, currentRenderT));

        // Geckolib の adjustTick(seekTime) = speed * max(seekTime - tickOffset, 0)
        //   seekTime = state.animationTick (= Blaze3D.getTime() * 20 = 連続実時間 ticks)
        // 私の write は **次フレーム** の adjustedTick に影響するため、1 フレーム先の
        // seekTime advance (= delta) を考慮しないとプルプル震える。
        //   want: adjusted_next = seekTime_next - tickOffset = desiredTicks
        //   tickOffset = seekTime_next - desiredTicks = (seekTime_now + delta) - desiredTicks
        double seekTime = state.getAnimationTick();
        double rawDelta;
        if (Double.isNaN(lastSeekTime)) {
            rawDelta = deltaEma; // 初回は EMA 初期値 (0.333)
        } else {
            rawDelta = seekTime - lastSeekTime;
        }
        lastSeekTime = seekTime;

        // delta の指数移動平均 (異常値 0 or > 2 はスパイクとして除外)
        if (rawDelta > 0.001 && rawDelta < 2.0) {
            deltaEma = deltaEma * 0.85 + rawDelta * 0.15;
        }

        if (REFLECTION_OK) {
            try {
                // tickOffset を書き換えて adjustedTick = desiredTicks に近づける
                // (next frame: adjustedTick = seekTime_next - newOffset ≈ desiredTicks)
                // delta 予測誤差で ±0.05 ticks のブレが残るが、PantographGeoModel の
                // setCustomAnimations の freeze-on-stable がそれを上書きして wobble を消す。
                double desiredTimeTicks = seekT * ANIM_LENGTH_SECONDS * TICKS_PER_SECOND;
                double newOffset = (seekTime + deltaEma) - desiredTimeTicks;
                FIELD_TICK_OFFSET.setDouble(ctrl, newOffset);
                previouslyWrittenTickOffset = newOffset;
            } catch (IllegalAccessException ignored) {}
        }
        return PlayState.CONTINUE;
    }

    /** 接触キャッシュ + ローカル deployed から目標 T (0..1) を計算。
     *  T = 0 → 折畳ポーズ、 T = 1 → 完全展開、 中間値は途中の姿勢。
     *  接触中の場合は集電版 TOP が架線 Y にちょうど触れる T を逆算する:
     *    T = (8 + 16 * barOffsetY) / 27 (= 新モデル幾何の集電版 pivot=35, cube top=37 に対応)。 */
    private float computeTargetT() {
        var entry = com.trainsystemutilities.client.electrification
                .ClientPantographContactState.get(getBlockPos());
        if (entry == com.trainsystemutilities.client.electrification
                .ClientPantographContactState.Entry.NONE) {
            return deployed ? 1f : 0f;
        }
        if (!entry.deployed()) return 0f;
        if (!entry.inContact()) return 1f;
        float T = (8f + 16f * entry.barOffsetY()) / 27f;
        return Math.max(0f, Math.min(1f, T));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("Deployed", deployed);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Deployed")) {
            this.deployed = tag.getBoolean("Deployed");
        }
        // 再ロード後は pendingAnim をリセット (= 次フレームで再評価される)
        this.pendingAnim = null;
    }

    /** チャンク同期 / sendBlockUpdated で送られる NBT。Deployed フィールドを含めて
     *  クライアント側のワールド BE が正しい状態を持てるようにする。 */
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putBoolean("Deployed", deployed);
        return tag;
    }

    /** ブロック更新通知パケット。これがないとクライアント側に setChanged() で sync されない。 */
    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    /** クライアント側でパケット受信時に呼ばれる。NBT を読んで状態を反映。 */
    @Override
    public void onDataPacket(net.minecraft.network.Connection net,
                              net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt,
                              HolderLookup.Provider registries) {
        if (pkt == null || pkt.getTag() == null) return;
        loadAdditional(pkt.getTag(), registries);
    }
}
