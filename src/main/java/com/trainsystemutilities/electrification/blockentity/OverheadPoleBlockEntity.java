package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 架線柱 BE。
 *
 * <p>**新規追加**: {@link #connectedTrusses} = 自分に anchor している truss の position list。
 * BERenderer から「pole 上端 → 接続 truss の端点」 を beam で描画する際に参照。
 */
public class OverheadPoleBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private float yawDegrees = Float.NaN;
    private final List<BlockPos> connectedTrusses = new ArrayList<>();

    public OverheadPoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERHEAD_POLE.get(), pos, state);
    }

    public float getYawDegrees() { return yawDegrees; }
    public boolean hasCustomYaw() { return !Float.isNaN(yawDegrees); }

    public void setYawDegrees(float yaw) {
        this.yawDegrees = yaw;
        markUpdated();
    }

    public List<BlockPos> getConnectedTrusses() { return connectedTrusses; }

    public void addConnectedTruss(BlockPos trussPos) {
        if (!connectedTrusses.contains(trussPos)) {
            connectedTrusses.add(trussPos);
            markUpdated();
        }
    }

    public void removeConnectedTruss(BlockPos trussPos) {
        if (connectedTrusses.remove(trussPos)) markUpdated();
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
        if (hasCustomYaw()) tag.putFloat("YawDegrees", yawDegrees);
        if (!connectedTrusses.isEmpty()) {
            ListTag list = new ListTag();
            for (BlockPos p : connectedTrusses) list.add(LongTag.valueOf(p.asLong()));
            tag.put("ConnectedTrusses", list);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        yawDegrees = tag.contains("YawDegrees") ? tag.getFloat("YawDegrees") : Float.NaN;
        connectedTrusses.clear();
        if (tag.contains("ConnectedTrusses")) {
            ListTag list = tag.getList("ConnectedTrusses", net.minecraft.nbt.Tag.TAG_LONG);
            for (int i = 0; i < list.size(); i++) {
                connectedTrusses.add(BlockPos.of(((LongTag) list.get(i)).getAsLong()));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        if (hasCustomYaw()) tag.putFloat("YawDegrees", yawDegrees);
        if (!connectedTrusses.isEmpty()) {
            ListTag list = new ListTag();
            for (BlockPos p : connectedTrusses) list.add(LongTag.valueOf(p.asLong()));
            tag.put("ConnectedTrusses", list);
        }
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
