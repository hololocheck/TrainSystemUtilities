package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** Client → Server: 駅グループの削除。 */
public record StationGroupDeletePayload(UUID groupId) implements CustomPacketPayload {

    public static final Type<StationGroupDeletePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "station_group_delete"));

    public static final StreamCodec<FriendlyByteBuf, StationGroupDeletePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.groupId),
                    buf -> new StationGroupDeletePayload(buf.readUUID()));

    public static void handle(StationGroupDeletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var data = StationGroupSavedData.get(player.server);
            StationGroup g = data.get(payload.groupId);
            if (g == null) return;
            // SECURITY (TSU-03): owner or OP のみ delete 可 (owner 未設定の legacy は誰でも可)
            if (!PermissionHelper.canManageOwned(player, g.ownerUUID())) return;
            boolean removed = data.remove(payload.groupId);
            if (removed) {
                com.trainsystemutilities.station.routing.navgraph.NavGraphCache
                        .remove(payload.groupId);
                com.trainsystemutilities.station.routing.navfield.NavFieldCache
                        .removeAll(player.server, payload.groupId);
                // 進行中の field 構築タスクもキャンセル
                com.trainsystemutilities.station.routing.navfield.NavFieldBuildScheduler
                        .cancelFor(payload.groupId);
                TrainSystemUtilities.LOGGER.info("[StationGroup] deleted {} by {}",
                        payload.groupId, player.getName().getString());
                StationGroupRenamePayload.broadcastListToAll(player.server);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
