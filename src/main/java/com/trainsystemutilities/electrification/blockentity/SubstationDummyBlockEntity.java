package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.electrification.block.SubstationMultiblock;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Marker {@link BlockEntity} attached to every dummy block in the substation
 * cubicle multiblock (3×4×2 = 23 dummies per cubicle).
 *
 * <p>It carries no state of its own and never ticks — its sole purpose is to
 * make tooltip mods such as <em>Jade</em> recognise that a "real" block lives
 * here so they invoke their per-block providers (in particular the energy bar
 * provider). The actual energy data is then resolved through the
 * {@link net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage}
 * delegation registered in {@code ElectrificationCapabilities}, which finds
 * the core block via {@link SubstationMultiblock#findCore} and returns its
 * {@link IEnergyStorage}.
 *
 * <p>Without this BE Jade looks up the block at the cursor, sees no
 * BlockEntity, and skips the energy provider entirely — so the FE bar
 * only ever appears on the single core position. With this BE Jade
 * fires the provider on all 24 positions and the bar shows up across the
 * whole multiblock.
 */
public class SubstationDummyBlockEntity extends BlockEntity {

    public SubstationDummyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUBSTATION_DUMMY.get(), pos, state);
    }
}
