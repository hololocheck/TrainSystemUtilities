package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 架線トラス BE。
 *
 * <p>**新方式: 動的 anchor 描画**:
 * <ul>
 *   <li>{@link #anchorA} / {@link #anchorB} (= 連続 world 位置) を保持</li>
 *   <li>{@link #hasAnchors()} なら BERenderer が anchor 間に **任意角度 beam** を描画</li>
 *   <li>既存 model (geo.json) は使わない (= BlockState 形状情報は無視)</li>
 *   <li>これで 360° 連続 + pole-truss 接続が実現</li>
 * </ul>
 *
 * <p>{@link #anchorPoleA} / {@link #anchorPoleB} = 接続元 pole BE position
 * (= pole が削除された時の切断判定用)
 */
public class OverheadTrussBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private float yawDegrees = Float.NaN;

    // 動的 beam 描画用 anchor (= 連続 world 位置)
    private Vec3 anchorA = null;
    private Vec3 anchorB = null;
    // 接続元 pole の BlockPos (= 削除追跡用)
    private BlockPos anchorPoleA = null;
    private BlockPos anchorPoleB = null;

    // **多 cell 衝突判定用**:
    //   master = anchor を持ち、 描画ロジックを実行する cell (= beam の中央)
    //   member = beam の通り道に配置される追加 cell (= 衝突判定のみ、 描画なし)
    private boolean isMaster = true;
    private BlockPos masterPos = null;

    public OverheadTrussBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERHEAD_TRUSS.get(), pos, state);
    }

    public float getYawDegrees() { return yawDegrees; }
    public boolean hasCustomYaw() { return !Float.isNaN(yawDegrees); }

    public void setYawDegrees(float yaw) {
        this.yawDegrees = yaw;
        markUpdated();
    }

    public Vec3 getAnchorA() { return anchorA; }
    public Vec3 getAnchorB() { return anchorB; }
    public BlockPos getAnchorPoleA() { return anchorPoleA; }
    public BlockPos getAnchorPoleB() { return anchorPoleB; }
    public boolean hasAnchors() { return anchorA != null && anchorB != null; }

    public void setAnchors(Vec3 a, Vec3 b, BlockPos poleA, BlockPos poleB) {
        this.anchorA = a;
        this.anchorB = b;
        this.anchorPoleA = poleA;
        this.anchorPoleB = poleB;
        markUpdated();
    }

    public void clearAnchors() {
        anchorA = null;
        anchorB = null;
        anchorPoleA = null;
        anchorPoleB = null;
        markUpdated();
    }

    public boolean isMaster() { return isMaster; }
    public BlockPos getMasterPos() { return masterPos; }

    public void setMember(BlockPos master) {
        this.isMaster = false;
        this.masterPos = master;
        markUpdated();
    }

    private void markUpdated() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(getBlockPos(), state, state, 3);
        }
    }

    private void writeVec3(CompoundTag tag, String key, Vec3 v) {
        if (v == null) return;
        CompoundTag sub = new CompoundTag();
        sub.putDouble("x", v.x);
        sub.putDouble("y", v.y);
        sub.putDouble("z", v.z);
        tag.put(key, sub);
    }

    private Vec3 readVec3(CompoundTag tag, String key) {
        if (!tag.contains(key)) return null;
        CompoundTag sub = tag.getCompound(key);
        return new Vec3(sub.getDouble("x"), sub.getDouble("y"), sub.getDouble("z"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        if (hasCustomYaw()) tag.putFloat("YawDegrees", yawDegrees);
        writeVec3(tag, "AnchorA", anchorA);
        writeVec3(tag, "AnchorB", anchorB);
        if (anchorPoleA != null) tag.putLong("AnchorPoleA", anchorPoleA.asLong());
        if (anchorPoleB != null) tag.putLong("AnchorPoleB", anchorPoleB.asLong());
        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) tag.putLong("MasterPos", masterPos.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        yawDegrees = tag.contains("YawDegrees") ? tag.getFloat("YawDegrees") : Float.NaN;
        anchorA = readVec3(tag, "AnchorA");
        anchorB = readVec3(tag, "AnchorB");
        anchorPoleA = tag.contains("AnchorPoleA") ? BlockPos.of(tag.getLong("AnchorPoleA")) : null;
        anchorPoleB = tag.contains("AnchorPoleB") ? BlockPos.of(tag.getLong("AnchorPoleB")) : null;
        isMaster = !tag.contains("IsMaster") || tag.getBoolean("IsMaster");
        masterPos = tag.contains("MasterPos") ? BlockPos.of(tag.getLong("MasterPos")) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        if (hasCustomYaw()) tag.putFloat("YawDegrees", yawDegrees);
        writeVec3(tag, "AnchorA", anchorA);
        writeVec3(tag, "AnchorB", anchorB);
        if (anchorPoleA != null) tag.putLong("AnchorPoleA", anchorPoleA.asLong());
        if (anchorPoleB != null) tag.putLong("AnchorPoleB", anchorPoleB.asLong());
        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) tag.putLong("MasterPos", masterPos.asLong());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {}

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
