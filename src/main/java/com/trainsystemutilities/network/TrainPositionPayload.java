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
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 列車のリアルタイム位置 + 速度。
 *
 * <p>2 Hz (= 10 ticks 毎) でブロードキャストして、乗り換え案内端末の MAP / 列車位置 ETA
 * 外挿に使う。
 *
 * <p>各列車について `(trainId, x, z, speed_blocks_per_tick, etaToNext_ticks, dayTime)` を送る。
 */
public record TrainPositionPayload(List<Position> positions, long serverDayTime) implements CustomPacketPayload {

    public record Position(UUID trainId, float x, float z, float speed, int etaToNextTicks) {}

    public static final Type<TrainPositionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_positions"));

    public static final StreamCodec<FriendlyByteBuf, TrainPositionPayload> STREAM_CODEC =
            StreamCodec.of(TrainPositionPayload::write, TrainPositionPayload::read);

    private static void write(FriendlyByteBuf buf, TrainPositionPayload p) {
        buf.writeLong(p.serverDayTime);
        buf.writeVarInt(p.positions.size());
        for (Position pos : p.positions) {
            buf.writeUUID(pos.trainId);
            buf.writeFloat(pos.x);
            buf.writeFloat(pos.z);
            buf.writeFloat(pos.speed);
            buf.writeVarInt(pos.etaToNextTicks);
        }
    }

    private static TrainPositionPayload read(FriendlyByteBuf buf) {
        long dt = buf.readLong();
        // P0-4 #7: 全列車の位置 snapshot (= 大規模ネットワーク想定)
        int n = BoundedStreamCodec.readBoundedListLength(buf, 4096);
        List<Position> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            float x = buf.readFloat();
            float z = buf.readFloat();
            float sp = buf.readFloat();
            // P0-4 #7 hotfix2: server→client。 server は負値 sentinel も送る。
            int eta = buf.readVarInt();
            list.add(new Position(id, x, z, sp, eta));
        }
        return new TrainPositionPayload(list, dt);
    }

    public static void handle(TrainPositionPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() ->
                TransitTerminalClientCache.replaceTrainPositions(payload.positions, payload.serverDayTime));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
