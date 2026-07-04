package com.trainsystemutilities.structure.blockentity;

import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 自動改札 BE。 トグル式: open / closed アニメを保持。 isOpen 状態に応じて
 * controller が対応アニメを自動再生・終端保持し、 BlockState OPEN を sync して collision を切替える。
 */
public class TicketGateBlockEntity extends BlockEntity implements GeoBlockEntity {

    public static final String CONTROLLER_ID = "door";
    public static final String ANIM_OPEN_NAME   = "open";
    public static final String ANIM_CLOSED_NAME = "closed";

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean open = false;
    /** 所属駅 (StationGroup) UUID。 駅範囲内への設置で紐付く (= null は未接続)。 券売機と同方式。 */
    private java.util.UUID associatedStationGroup;
    /** 切符投入で開いた後の自動閉じカウントダウン (tick)。 >0 = 計測中。 再投入でリセット (= 延長)。 */
    private int closeTimer = 0;

    public TicketGateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TICKET_GATE.get(), pos, state);
    }

    public boolean isOpen() { return open; }

    public java.util.UUID getAssociatedStationGroup() { return associatedStationGroup; }

    public void setAssociatedStationGroup(java.util.UUID id) {
        this.associatedStationGroup = id;
        markUpdated();
    }

    /** トグル: open ↔ closed。 BlockState OPEN を sync して collision を切替える。 */
    public void toggle() {
        open = !open;
        if (level != null && !level.isClientSide) {
            BlockState s = getBlockState();
            if (s.hasProperty(com.trainsystemutilities.structure.block.TicketGateBlock.OPEN)) {
                BlockState ns = s.setValue(com.trainsystemutilities.structure.block.TicketGateBlock.OPEN, open);
                if (ns != s) level.setBlock(getBlockPos(), ns, Block.UPDATE_ALL);
            }
        }
        markUpdated();
    }

    /** 指定状態に合わせる (= 既に同状態なら no-op)。 */
    public void setOpen(boolean shouldOpen) {
        if (this.open != shouldOpen) toggle();
    }

    /** 切符投入で開く: 開 + 自動閉じカウントダウン開始 (= 再投入でリセット = 延長)。 */
    public void openForTicket(int delayTicks) {
        setOpen(true);
        closeTimer = delayTicks;
    }

    /** server tick: 自動閉じカウントダウン。 0 で閉じる。 */
    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos,
                                  BlockState state, TicketGateBlockEntity be) {
        if (be.closeTimer > 0 && --be.closeTimer == 0) {
            be.setOpen(false);
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
        if (associatedStationGroup != null) tag.putUUID("StationGroup", associatedStationGroup);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        open = tag.getBoolean("Open");
        associatedStationGroup = tag.hasUUID("StationGroup") ? tag.getUUID("StationGroup") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        tag.putBoolean("Open", open);
        if (associatedStationGroup != null) tag.putUUID("StationGroup", associatedStationGroup);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER_ID, 0, state -> {
            if (open) {
                return state.setAndContinue(RawAnimation.begin()
                        .then(ANIM_OPEN_NAME, Animation.LoopType.HOLD_ON_LAST_FRAME));
            }
            return state.setAndContinue(RawAnimation.begin()
                    .then(ANIM_CLOSED_NAME, Animation.LoopType.HOLD_ON_LAST_FRAME));
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
