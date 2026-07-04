package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.TicketConfigSavedData;
import com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Set;
import java.util.UUID;

/**
 * Client → Server: 券売機で行き先をクリック → 切符を発券する。
 *
 * <p>サーバ側で再検証 (券売機が存在 / 近接 / 行き先が販売可 / 自駅でない) してから
 * 切符 ItemStack を発駅・着駅情報付きでプレイヤーへ付与する。 v1 は無料発券。
 */
public record BuyTicketPayload(BlockPos machinePos, UUID destId) implements CustomPacketPayload {

    public static final Type<BuyTicketPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "buy_ticket"));

    public static final StreamCodec<FriendlyByteBuf, BuyTicketPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeBlockPos(p.machinePos); buf.writeUUID(p.destId); },
                    buf -> new BuyTicketPayload(buf.readBlockPos(), buf.readUUID()));

    public static void handle(BuyTicketPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var level = player.level();
            // 券売機が存在 + 近接 (簡易チェック: ~8 ブロック)
            if (!(level.getBlockEntity(payload.machinePos) instanceof TicketVendingMachineBlockEntity be)) return;
            if (player.distanceToSqr(payload.machinePos.getX() + 0.5,
                    payload.machinePos.getY() + 0.5, payload.machinePos.getZ() + 0.5) > 64.0) return;

            var server = player.getServer();
            if (server == null) return;
            StationGroupSavedData sgData = StationGroupSavedData.get(server);
            StationGroup dest = sgData.get(payload.destId);
            if (dest == null) return;

            // 販売可検証 (空 = 未設定 = 全駅販売可、 Phase 3 で取捨選択)
            Set<UUID> sellable = TicketConfigSavedData.get(server).sellable();
            if (!sellable.isEmpty() && !sellable.contains(payload.destId)) return;

            // 発駅 (= 所属駅)。駅範囲外の券売機では発券しない。自駅への切符も不可。
            UUID assoc = be.getAssociatedStationGroup();
            StationGroup origin = assoc != null ? sgData.get(assoc) : null;
            if (origin == null) return;
            if (origin.id().equals(payload.destId)) return;
            String originName = origin.name();

            // 切符を発券
            ItemStack ticket = new ItemStack(ModItems.TICKET.get());
            ticket.set(ModDataComponents.TICKET_FROM.get(), originName);
            ticket.set(ModDataComponents.TICKET_FROM_ID.get(), origin.id());
            ticket.set(ModDataComponents.TICKET_TO.get(), dest.name());
            ticket.set(ModDataComponents.TICKET_TO_ID.get(), payload.destId);
            if (!player.getInventory().add(ticket)) {
                player.drop(ticket, false);
            }
            player.displayClientMessage(
                    Component.translatable("tsu.ticket.bought_fmt", dest.name())
                            .withStyle(net.minecraft.ChatFormatting.AQUA), true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
