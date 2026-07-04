package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.transit.TransitTerminalClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server → Client: 全列車スケジュール lite snapshot 配信。
 *
 * <p>サーバの {@link com.trainsystemutilities.station.TrainScheduleCache} を client cache
 * ({@link TransitTerminalClientCache}) に同期する。
 */
public record TransitSchedulePayload(Map<UUID, SnapshotLite> snapshots) implements CustomPacketPayload {

    /** Snapshot の lite copy (サーバ-クライアント往復用)。 */
    public record SnapshotLite(
            UUID trainId,
            String trainName,
            UUID currentGroupId,
            UUID nextGroupId,
            int etaTicksToNext,
            List<UUID> upcomingGroupIds,
            List<String> upcomingStationNames
    ) {}

    public static final Type<TransitSchedulePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "transit_schedule"));

    public static final StreamCodec<FriendlyByteBuf, TransitSchedulePayload> STREAM_CODEC =
            StreamCodec.of(TransitSchedulePayload::write, TransitSchedulePayload::read);

    private static void write(FriendlyByteBuf buf, TransitSchedulePayload p) {
        buf.writeVarInt(p.snapshots.size());
        for (SnapshotLite s : p.snapshots.values()) {
            buf.writeUUID(s.trainId);
            buf.writeUtf(s.trainName == null ? "" : s.trainName, 64);
            buf.writeBoolean(s.currentGroupId != null);
            if (s.currentGroupId != null) buf.writeUUID(s.currentGroupId);
            buf.writeBoolean(s.nextGroupId != null);
            if (s.nextGroupId != null) buf.writeUUID(s.nextGroupId);
            buf.writeVarInt(s.etaTicksToNext);
            // upcoming
            int n = Math.min(16, s.upcomingGroupIds == null ? 0 : s.upcomingGroupIds.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                buf.writeUUID(s.upcomingGroupIds.get(i));
                String name = i < s.upcomingStationNames.size() ? s.upcomingStationNames.get(i) : "";
                buf.writeUtf(name == null ? "" : name, 64);
            }
        }
    }

    private static TransitSchedulePayload read(FriendlyByteBuf buf) {
        // P0-4 #7: train snapshot count capped at 4096 (server world train count expected << 4096; preset-list budget).
        int count = BoundedStreamCodec.readBoundedListLength(buf, 4096);
        Map<UUID, SnapshotLite> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            // P0-4 #7: train display name; 128 bytes generic-name budget.
            String name = BoundedStreamCodec.readBoundedUtf(buf, 128);
            UUID cur = buf.readBoolean() ? buf.readUUID() : null;
            UUID next = buf.readBoolean() ? buf.readUUID() : null;
            // P0-4 #7 hotfix3: write は writeVarInt → read も readVarInt 必須 (encoding 整合)。
            int eta = buf.readVarInt();
            // P0-4 #7: write side caps upcoming list at 16; allow 256 for forward compat (small ad-hoc list budget).
            int n = BoundedStreamCodec.readBoundedListLength(buf, 256);
            List<UUID> upIds = new ArrayList<>(n);
            List<String> upNames = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                upIds.add(buf.readUUID());
                // P0-4 #7: upcoming station name; 128 bytes generic-name budget.
                upNames.add(BoundedStreamCodec.readBoundedUtf(buf, 128));
            }
            map.put(id, new SnapshotLite(id, name, cur, next, eta, upIds, upNames));
        }
        return new TransitSchedulePayload(map);
    }

    public static void handle(TransitSchedulePayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> {
            Map<UUID, TransitTerminalClientCache.ScheduleSnapshot> map = new LinkedHashMap<>();
            for (var e : payload.snapshots.entrySet()) {
                var s = e.getValue();
                map.put(e.getKey(), new TransitTerminalClientCache.ScheduleSnapshot(
                        s.trainId, s.trainName, s.currentGroupId, s.nextGroupId,
                        s.etaTicksToNext, s.upcomingGroupIds, s.upcomingStationNames));
            }
            TransitTerminalClientCache.replaceSchedules(map);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
