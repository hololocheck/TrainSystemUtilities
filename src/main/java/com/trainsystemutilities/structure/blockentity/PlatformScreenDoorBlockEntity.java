package com.trainsystemutilities.structure.blockentity;

import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ホームドア BE。 トグル式: open / close アニメを triggerable で持つ。
 * isOpen 状態を保持し、 右クリックでトグル → 対応アニメを trigger。
 */
public class PlatformScreenDoorBlockEntity extends BlockEntity implements GeoBlockEntity, BandColorable {

    public static final String CONTROLLER_ID = "door";
    public static final String ANIM_OPEN_NAME  = "open";
    public static final String ANIM_CLOSE_NAME = "close";
    private static final int DEFAULT_BAND_COLOR = 0xFF66BB6A;  // 緑 (= 山手線風)

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean open = false;
    // multi-block: master 描画 + 周囲 dummy = collision のみ
    private boolean isMaster = true;
    private BlockPos masterPos = null;
    private int bandColorARGB = DEFAULT_BAND_COLOR;

    public PlatformScreenDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLATFORM_SCREEN_DOOR.get(), pos, state);
    }

    public boolean isOpen() { return open; }
    public boolean isMaster() { return isMaster; }
    public BlockPos getMasterPos() { return masterPos; }

    @Override
    public int getBandColorARGB() { return bandColorARGB; }

    @Override
    public void setBandColorARGB(int argb) {
        // master 側で適用 → group 全体に sync (= dummy も同じ色を持って 描画は master のみ)
        BlockPos masterCellPos = isMaster ? getBlockPos() : (masterPos != null ? masterPos : getBlockPos());
        if (level != null && !level.isClientSide && !masterCellPos.equals(getBlockPos())) {
            BlockEntity mbe = level.getBlockEntity(masterCellPos);
            if (mbe instanceof PlatformScreenDoorBlockEntity m) {
                m.setBandColorARGB(argb);
                return;
            }
        }
        this.bandColorARGB = argb;
        markUpdated();
    }

    public void setDummy(BlockPos master) {
        this.isMaster = false;
        this.masterPos = master;
        markUpdated();
    }

    /** トグル: open ↔ close。 controller predicate が 自動でアニメを再生・終端保持する。
     *  + 全 cell の BlockState OPEN を sync して collision を切り替え。 */
    public void toggle() {
        open = !open;
        if (level != null && !level.isClientSide) {
            updateOpenStateForGroup();
        }
        markUpdated();
    }

    /** 指定状態に合わせる (= 既に同状態なら no-op)。 trigger から呼ぶ。 */
    public void setOpen(boolean shouldOpen) {
        if (this.open != shouldOpen) toggle();
    }

    /** master + 全 dummy cells の BlockState OPEN プロパティを sync (= collision 制御)。 */
    private void updateOpenStateForGroup() {
        if (level == null || level.isClientSide) return;
        BlockPos masterCellPos = isMaster ? getBlockPos() : (masterPos != null ? masterPos : getBlockPos());
        // master を含む group 内 全 cell を 検索 (= 同 BE type で masterPos が一致 or self)
        int RADIUS = 4;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                BlockPos p = masterCellPos.offset(dx, 0, dz);
                BlockEntity be = level.getBlockEntity(p);
                if (be instanceof PlatformScreenDoorBlockEntity door
                        && (p.equals(masterCellPos)
                                || (door.masterPos != null && door.masterPos.equals(masterCellPos)))) {
                    BlockState s = level.getBlockState(p);
                    if (s.hasProperty(com.trainsystemutilities.structure.block.PlatformScreenDoorBlock.OPEN)) {
                        BlockState newState = s.setValue(
                                com.trainsystemutilities.structure.block.PlatformScreenDoorBlock.OPEN, open);
                        if (newState != s) {
                            level.setBlock(p, newState, net.minecraft.world.level.block.Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
    }

    private void markUpdated() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(getBlockPos(), state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putBoolean("Open", open);
        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) tag.putLong("MasterPos", masterPos.asLong());
        tag.putInt("BandColorARGB", bandColorARGB);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        open = tag.getBoolean("Open");
        isMaster = !tag.contains("IsMaster") || tag.getBoolean("IsMaster");
        masterPos = tag.contains("MasterPos") ? BlockPos.of(tag.getLong("MasterPos")) : null;
        bandColorARGB = tag.contains("BandColorARGB") ? tag.getInt("BandColorARGB") : DEFAULT_BAND_COLOR;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        tag.putBoolean("Open", open);
        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) tag.putLong("MasterPos", masterPos.asLong());
        tag.putInt("BandColorARGB", bandColorARGB);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // predicate ベース: open 状態に応じて open / close アニメを自動再生 + 最終フレーム保持
        controllers.add(new AnimationController<>(this, CONTROLLER_ID, 0, state -> {
            if (open) {
                return state.setAndContinue(RawAnimation.begin()
                        .then(ANIM_OPEN_NAME, Animation.LoopType.HOLD_ON_LAST_FRAME));
            } else {
                return state.setAndContinue(RawAnimation.begin()
                        .then(ANIM_CLOSE_NAME, Animation.LoopType.HOLD_ON_LAST_FRAME));
            }
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
