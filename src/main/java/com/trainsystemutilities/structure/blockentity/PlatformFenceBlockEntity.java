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
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * ホーム柵 (1m/3m/5m 共通) の BE。 帯の色 ARGB を保持。
 *
 * <p>BlockBench モデルがまだ `color_band` ボーン分離前なので、 現状はモデル全体に
 * tint が乗る。 ボーン分離後は GeoModel 側で `color_band` のみに tint 適用に変更可能。
 */
public class PlatformFenceBlockEntity extends BlockEntity implements GeoBlockEntity, BandColorable {

    private static final int DEFAULT_BAND_COLOR = 0xFF66BB6A; // default = 緑 (= 山手線風)

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int bandColorARGB = DEFAULT_BAND_COLOR;
    // multi-block 用: master = 描画 + 中央 BE、 dummy = 衝突判定のみ、 master を参照
    private boolean isMaster = true;
    private BlockPos masterPos = null;

    public PlatformFenceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLATFORM_FENCE.get(), pos, state);
    }

    public int getBandColorARGB() { return bandColorARGB; }

    public void setBandColorARGB(int argb) {
        this.bandColorARGB = argb;
        markUpdated();
    }

    public boolean isMaster() { return isMaster; }
    public BlockPos getMasterPos() { return masterPos; }

    public void setDummy(BlockPos master) {
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

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("BandColorARGB", bandColorARGB);
        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) tag.putLong("MasterPos", masterPos.asLong());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        bandColorARGB = tag.contains("BandColorARGB") ? tag.getInt("BandColorARGB") : DEFAULT_BAND_COLOR;
        isMaster = !tag.contains("IsMaster") || tag.getBoolean("IsMaster");
        masterPos = tag.contains("MasterPos") ? BlockPos.of(tag.getLong("MasterPos")) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = super.getUpdateTag(provider);
        tag.putInt("BandColorARGB", bandColorARGB);
        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) tag.putLong("MasterPos", masterPos.asLong());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 静的モデルだが Geckolib は controller を 1 つは要求する (= 無いと render skip)
        controllers.add(new AnimationController<>(this, "idle", 0, state -> PlayState.STOP));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
