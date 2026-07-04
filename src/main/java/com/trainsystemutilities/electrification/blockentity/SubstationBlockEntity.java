package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.electrification.wire.SubstationRegistry;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 変電所 BE。FE バッファ (= IEnergyStorage capability の窓口) + ネットワーク接続数の表示。
 *
 * <p><b>v2 architecture (チャンクロード非依存):</b>
 * BE は per-dimension {@link SubstationRegistry} SavedData の "view" として動作する。
 * 給電 tick (= 隣接碍子からの BFS + {@link com.trainsystemutilities.electrification.wire.EnergizedWireState}
 * へのマーク + FE 維持コスト引き落とし) は
 * {@link com.trainsystemutilities.electrification.SubstationTickHandler} がグローバルに実行する。
 * これにより変電所チャンクが非ロードでも給電が継続する。
 *
 * <p>BE.energy は SavedData の値を反映するキャッシュ。
 * <ul>
 *   <li>BE.onLoad(): SavedData に登録 (未登録時のみ) + SavedData → energy 同期</li>
 *   <li>BE.receiveEnergy(): energy 更新 + SavedData も更新</li>
 *   <li>ブロック破壊時 ({@link com.trainsystemutilities.electrification.block.SubstationBlock#onRemove}):
 *       SavedData から登録解除</li>
 * </ul>
 *
 * <p>BE.serverTick は廃止 (= SubstationTickHandler が代わりに処理)。
 */
public class SubstationBlockEntity extends BlockEntity implements IEnergyStorage, GeoBlockEntity {

    /** FE バッファ容量 (1M FE)。 */
    public static final int CAPACITY = 1_000_000;
    /** 1 tick あたり外部から受け取れる最大 FE。 */
    public static final int MAX_RECEIVE = 10_000;
    /** ネットワーク維持コスト = 接続 1 本につき 1 FE / tick (SubstationTickHandler で使用)。 */
    public static final int FE_PER_CONNECTION_PER_TICK = 1;

    /** SavedData の値を反映するキャッシュ (= GUI 表示・IEnergyStorage 応答用)。 */
    private int energy = 0;
    /** SubstationTickHandler が直前 tick で計算した接続数 (= GUI 表示用)。
     *  SavedData 化されておらず、未ロード時は 0 が表示されるが UI 確認用なので問題なし。 */
    private int lastNetworkConnections = 0;

    /** Geckolib アニメーションインスタンスキャッシュ (アニメ無しの静的描画でも必須)。 */
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public SubstationBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUBSTATION.get(), pos, state);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 静的モデルなのでアニメ無し。Controller 登録は必須なので何もしない controller を 1 つ追加。
        controllers.add(new AnimationController<>(this, "main", 0, state -> PlayState.STOP));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    public int getStoredEnergy() { return energy; }

    public int getNetworkConnectionCount() { return lastNetworkConnections; }

    /** SubstationTickHandler が tick 後に「直前計算した接続数」を BE 側にも書き戻すためのフック。
     *  BE がロードされている時のみ呼ばれる (= ロードされていなければ表示更新の必要なし)。 */
    public void setLastNetworkConnections(int n) { this.lastNetworkConnections = n; }

    /** SubstationTickHandler が SavedData の FE を更新した際に BE 側の energy キャッシュも同期する。 */
    public void syncFromRegistry(int registryFe) {
        if (this.energy != registryFe) {
            this.energy = registryFe;
            setChanged();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 初回ロード時: SavedData にまだ登録されていなければ、BE の NBT に保存されていた
        // energy 値で登録する (= 旧版からの移行対応)。
        // 既に登録されていれば SavedData 側を真と見なして BE.energy を上書きする。
        if (level instanceof ServerLevel server) {
            SubstationRegistry reg = SubstationRegistry.get(server);
            net.minecraft.core.Direction facing = getBlockState().hasProperty(
                    com.trainsystemutilities.electrification.block.SubstationBlock.FACING)
                    ? getBlockState().getValue(
                            com.trainsystemutilities.electrification.block.SubstationBlock.FACING)
                    : net.minecraft.core.Direction.NORTH;
            if (!reg.isRegistered(worldPosition)) {
                reg.register(worldPosition, this.energy, facing);
            } else {
                // SavedData の値で BE.energy を上書き (= 未ロード中に維持コストが引き落とされた分を反映)
                this.energy = reg.getFe(worldPosition);
                // facing も最新化 (= マルチブロック向き変更などに対応)
                reg.setFacing(worldPosition, facing);
            }
        }
    }

    // ===== IEnergyStorage =====

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        int accepted = Math.min(MAX_RECEIVE, Math.min(CAPACITY - energy, toReceive));
        if (!simulate && accepted > 0) {
            energy += accepted;
            // SavedData にも反映 (= 給電 tick が常に最新の FE を見えるように)
            if (level instanceof ServerLevel server) {
                SubstationRegistry.get(server).setFe(worldPosition, energy);
            }
            setChanged();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        // 変電所は外部に FE を取り出させない (= 一方通行)
        return 0;
    }

    @Override
    public int getEnergyStored() { return energy; }

    @Override
    public int getMaxEnergyStored() { return CAPACITY; }

    @Override
    public boolean canExtract() { return false; }

    @Override
    public boolean canReceive() { return true; }

    // ===== NBT =====

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Energy", energy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.energy = Math.min(CAPACITY, Math.max(0, tag.getInt("Energy")));
    }
}
