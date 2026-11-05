package com.trainsystemutilities.network;

import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.TicketConfigSavedData;
import com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.UUID;

/**
 * 券売機で切符を買う — the per-player stations host's {@code buy} action ({@link StationsData}; BuyTicketPayload until
 * MANTA_7_CONCEPT C4). The machine is named by its position and checked here as the payload's handler checked it.
 */
public final class BuyTicketAction {

    private BuyTicketAction() {}

    public static void apply(ServerPlayer player, BlockPos machinePos, UUID destId) {
        var level = player.level();
        if (!(level.getBlockEntity(machinePos) instanceof TicketVendingMachineBlockEntity be)) return;
        if (player.distanceToSqr(machinePos.getX() + 0.5,
                machinePos.getY() + 0.5, machinePos.getZ() + 0.5) > 64.0) return;
        var server = player.getServer();
        if (server == null) return;
        StationGroupSavedData sgData = StationGroupSavedData.get(server);
        StationGroup dest = sgData.get(destId);
        if (dest == null) return;
        Set<UUID> sellable = TicketConfigSavedData.get(server).sellable();
        if (!sellable.isEmpty() && !sellable.contains(destId)) return;
        UUID assoc = be.getAssociatedStationGroup();
        StationGroup origin = assoc != null ? sgData.get(assoc) : null;
        if (origin == null) return;
        if (origin.id().equals(destId)) return;
        String originName = origin.name();
        ItemStack ticket = new ItemStack(ModItems.TICKET.get());
        ticket.set(ModDataComponents.TICKET_FROM.get(), originName);
        ticket.set(ModDataComponents.TICKET_FROM_ID.get(), origin.id());
        ticket.set(ModDataComponents.TICKET_TO.get(), dest.name());
        ticket.set(ModDataComponents.TICKET_TO_ID.get(), destId);
        if (!player.getInventory().add(ticket)) {
            player.drop(ticket, false);
        }
        player.displayClientMessage(
                Component.translatable("tsu.ticket.bought_fmt", dest.name())
                        .withStyle(net.minecraft.ChatFormatting.AQUA), true);
    }
}
