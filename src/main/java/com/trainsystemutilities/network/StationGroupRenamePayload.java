package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.UUID;

/** Client → Server: 駅グループの名前変更。 */
public record StationGroupRenamePayload(UUID groupId, String newName) implements CustomPacketPayload {

    public static final Type<StationGroupRenamePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "station_group_rename"));

    public static final StreamCodec<FriendlyByteBuf, StationGroupRenamePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeUUID(p.groupId); buf.writeUtf(p.newName, 64); },
                    // P0-4 #7: newName — generic display name, 128 bytes (SafeName.DEFAULT_MAX_BYTES)
                    buf -> new StationGroupRenamePayload(buf.readUUID(), BoundedStreamCodec.readBoundedUtf(buf, 128)));

    public static void handle(StationGroupRenamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            String newName = payload.newName == null ? "" : payload.newName.trim();
            if (newName.isEmpty() || newName.length() > 64) return;
            // P0-4 #7: newName persisted to SavedData NBT and Create StationBlockEntity name — reject control/BiDi/oversized
            try {
                SafeName.validate(newName);
            } catch (IllegalArgumentException ex) {
                TrainSystemUtilities.LOGGER.warn("[StationGroup] reject rename — name failed SafeName validation: {}",
                        SafeLog.sanitize(ex.getMessage()));
                return;
            }

            var data = StationGroupSavedData.get(player.server);
            StationGroup old = data.get(payload.groupId);
            if (old == null) return;
            // SECURITY (TSU-03): owner or OP のみ rename 可 (owner 未設定の legacy は誰でも可)
            if (!PermissionHelper.canManageOwned(player, old.ownerUUID())) return;
            StationGroup updated = new StationGroup(
                    old.id(), newName, old.dimensionId(), old.minPos(), old.maxPos(),
                    old.stationBlockIds(), old.stationBlockPositions(), old.namingTemplate(), old.ownerUUID());
            data.put(updated);

            // 該当 dim level で各駅ブロックを再 rename
            var level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(old.dimensionId())));
            if (level != null) {
                for (int i = 0; i < updated.stationBlockPositions().size(); i++) {
                    BlockPos p = updated.stationBlockPositions().get(i);
                    int platformNum = i + 1;
                    String formatted = updated.formatStationName(platformNum);
                    var be = level.getBlockEntity(p);
                    if (be instanceof com.simibubi.create.content.trains.station.StationBlockEntity sbe) {
                        sbe.updateName(formatted);
                    }
                }
            }

            // 全プレイヤーに更新後リストをブロードキャスト
            broadcastListToAll(player.server);
            TrainSystemUtilities.LOGGER.info(
                    "[StationGroup] renamed {} '{}' → '{}' by {}",
                    payload.groupId, SafeLog.sanitize(old.name()), SafeLog.sanitize(newName), player.getName().getString());
        });
    }

    static void broadcastListToAll(net.minecraft.server.MinecraftServer server) {
        var all = new ArrayList<>(StationGroupSavedData.get(server).all());
        PacketDistributor.sendToAllPlayers(new StationGroupListResponsePayload(all));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
