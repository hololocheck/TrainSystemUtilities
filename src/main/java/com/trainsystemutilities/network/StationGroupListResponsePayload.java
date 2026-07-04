package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: 駅グループ全件を送信。クライアントは
 * {@link StationGroupClientCache} に格納して view mode + 管理 GUI で使用。
 */
public record StationGroupListResponsePayload(List<StationGroup> groups) implements CustomPacketPayload {

    public static final Type<StationGroupListResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "station_group_list_resp"));

    public static final StreamCodec<FriendlyByteBuf, StationGroupListResponsePayload> STREAM_CODEC =
            StreamCodec.of(StationGroupListResponsePayload::write, StationGroupListResponsePayload::read);

    private static void write(FriendlyByteBuf buf, StationGroupListResponsePayload p) {
        buf.writeVarInt(p.groups.size());
        for (StationGroup g : p.groups) buf.writeNbt(g.save());
    }

    private static StationGroupListResponsePayload read(FriendlyByteBuf buf) {
        // P0-4 #7: station group count — small ad-hoc list, 256 elements max
        int n = BoundedStreamCodec.readBoundedListLength(buf, 256);
        List<StationGroup> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            CompoundTag tag = buf.readNbt();
            if (tag != null) {
                try { list.add(StationGroup.load(tag)); }
                catch (Exception ex) {
                    TrainSystemUtilities.LOGGER.warn("[StationGroup] failed to decode group: {}", SafeLog.sanitize(ex.getMessage()));
                }
            }
        }
        return new StationGroupListResponsePayload(list);
    }

    public static void handle(StationGroupListResponsePayload payload, IPayloadContext context) {
        // client-side のみ。Minecraft.execute で main thread に再投入。
        Minecraft.getInstance().execute(() -> StationGroupClientCache.replaceAll(payload.groups));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
