package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * FE インバータ BE。
 *
 * <p>役割:
 * <ul>
 *   <li>FE バッファ (= パンタからの集電を一時保持)</li>
 *   <li>からくり列車の電動駆動の許可信号 (= FE 残量が 0 なら "動作不可")</li>
 *   <li>D3 でパンタとリンクされ集電された FE を受け取る</li>
 * </ul>
 */
public class FEInverterBlockEntity extends BlockEntity implements IEnergyStorage {

    /** 車載バッファ容量 (= 50k FE)。短時間の停電は乗り越えられる程度。 */
    public static final int CAPACITY = 50_000;
    public static final int MAX_RECEIVE = 5_000;

    private int energy = 0;

    public FEInverterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FE_INVERTER.get(), pos, state);
    }

    public boolean canDrive() {
        return energy > 0;
    }

    /** D3 のパンタ集電から呼ばれる内部給電 (= IEnergyStorage の receiveEnergy をバイパス可能)。 */
    public int internalAccept(int amount) {
        int accepted = Math.min(amount, CAPACITY - energy);
        if (accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    /** 1 tick の駆動消費。FE 不足なら 0 を返す (= 駆動不可)。 */
    public int consumeForDrive(int amount) {
        if (amount <= 0) return 0;
        int taken = Math.min(amount, energy);
        if (taken > 0) {
            energy -= taken;
            setChanged();
        }
        return taken;
    }

    // ===== IEnergyStorage =====

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (toReceive <= 0) return 0;
        int accepted = Math.min(MAX_RECEIVE, Math.min(CAPACITY - energy, toReceive));
        if (!simulate && accepted > 0) {
            energy += accepted;
            setChanged();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
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
        this.energy = Math.max(0, Math.min(CAPACITY, tag.getInt("Energy")));
    }
}
