package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.TicketConfigSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Client → Server: ({@code computerPos} の管理用コンピューターについて) 販売可な着駅一覧 + 自ネットワークの
 * 駅グループを要求。 Server は {@link TicketConfigSyncPayload} を返送。
 */
public record TicketConfigRequestPayload(BlockPos computerPos) implements CustomPacketPayload {

    public static final Type<TicketConfigRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "ticket_config_req"));

    public static final StreamCodec<FriendlyByteBuf, TicketConfigRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.computerPos),
                    buf -> new TicketConfigRequestPayload(buf.readBlockPos()));

    public static void handle(TicketConfigRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // SECURITY (TSU-NET-002): reach + BE + canAccess を検証。旧実装は computerPos だけで
            // sellable 一覧と自ネットワークの駅グループを取得でき、遠隔/private computer の情報が漏れた。
            if (!com.trainsystemutilities.util.PermissionHelper.isWithinReach(player, payload.computerPos)) return;
            var level = player.level();
            if (!(level.getBlockEntity(payload.computerPos)
                    instanceof com.trainsystemutilities.blockentity.ManagementComputerBlockEntity be)) return;
            if (!be.canAccess(player)) return;

            var server = player.server;
            var sellable = new ArrayList<>(TicketConfigSavedData.get(server).sellable());
            // 自ネットワーク = この管理用コンピューターがスキャン済みの駅を含む駅グループ集合
            // (= リンクした線路網。 findContaining で各スキャン駅 → 所属グループを解決)。
            List<UUID> networkGroups = new ArrayList<>();
            String dim = level.dimension().location().toString();
            var sgData = StationGroupSavedData.get(server);
            Set<UUID> seen = new HashSet<>();
            for (var s : be.getCachedStations()) {
                var g = sgData.findContaining(dim, s.position());
                if (g != null && seen.add(g.id())) networkGroups.add(g.id());
            }
            PacketDistributor.sendToPlayer(player, new TicketConfigSyncPayload(sellable, networkGroups));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
