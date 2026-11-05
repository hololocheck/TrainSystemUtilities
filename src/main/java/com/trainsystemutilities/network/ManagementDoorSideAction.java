package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 駅のドアの開く方向 — the management computer host's {@code door-side} action ({@link ManagementComputerData}).
 *
 * <p>Until 2026-09-30 the station detail's door buttons wrote the railway management block through the integrated
 * server ({@code Minecraft.getSingleplayerServer()}) and sent nothing, so on a dedicated server a press did nothing:
 * measured on VM-A, the block's DoorOpenSide stayed AUTO after the NORTH button took the click. The block is found
 * the way that write found it - through this computer's station to manager map - so a client names only a block the
 * computer already manages; {@link RailwayManagementBlockEntity#setDoorOpenSide} applies it to the Create station
 * and sends the block update the screen reads back. A private block takes the change only from a player it admits
 * itself ({@link ManagementComputerData#railwayManagerAdmits}, 2026-10-01).</p>
 */
public final class ManagementDoorSideAction {

    private ManagementDoorSideAction() {}

    public static void apply(ManagementComputerBlockEntity mc, Level level, ServerPlayer player, String stationName,
                             BlockPos stationPos, String side) {
        RailwayManagementBlockEntity.DoorSide s = sideOf(side);
        if (s == null) return;
        BlockPos rmPos = mc.getManagerPosForStation(stationName, stationPos);
        if (rmPos == null) return;
        if (level.getBlockEntity(rmPos) instanceof RailwayManagementBlockEntity rm
                && ManagementComputerData.railwayManagerAdmits(rm, player)) {
            rm.setDoorOpenSide(s);
        }
    }

    /** A declared value as the block's enum; null for a name it does not have (the declaration is its names). */
    @Nullable
    static RailwayManagementBlockEntity.DoorSide sideOf(String name) {
        try {
            return RailwayManagementBlockEntity.DoorSide.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
