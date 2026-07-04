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

/**
 * Server → Client: 全 trackNetworks の 2D ポリラインセグメント。
 *
 * <p>各セグメントは int[]{x1, z1, x2, z2}。Y 座標は省略 (MAP タブは 2D 平面)。
 */
public record TransitMapPayload(List<int[]> segments) implements CustomPacketPayload {

    public static final Type<TransitMapPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "transit_map"));

    public static final StreamCodec<FriendlyByteBuf, TransitMapPayload> STREAM_CODEC =
            StreamCodec.of(TransitMapPayload::write, TransitMapPayload::read);

    private static void write(FriendlyByteBuf buf, TransitMapPayload p) {
        int n = p.segments.size();
        buf.writeVarInt(n);
        for (int[] s : p.segments) {
            buf.writeVarInt(s[0]);
            buf.writeVarInt(s[1]);
            buf.writeVarInt(s[2]);
            buf.writeVarInt(s[3]);
        }
    }

    private static TransitMapPayload read(FriendlyByteBuf buf) {
        // P0-4 #7: segments list length capped at 4096 (matches server-side packet size guard in TransitMapRequestPayload).
        int n = BoundedStreamCodec.readBoundedListLength(buf, 4096);
        List<int[]> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // P0-4 #7 hotfix3: write は writeVarInt なので read も readVarInt が必要 (= 固定 4byte
            // readInt との encoding ミスマッチで buffer 枯渇 IndexOutOfBoundsException 発生)。
            // VarInt は負値も 5 byte で encode 可能なので server の sentinel も透過する。
            int x1 = buf.readVarInt();
            int z1 = buf.readVarInt();
            int x2 = buf.readVarInt();
            int z2 = buf.readVarInt();
            list.add(new int[]{x1, z1, x2, z2});
        }
        return new TransitMapPayload(list);
    }

    public static void handle(TransitMapPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> TransitTerminalClientCache.replaceMapSegments(payload.segments));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
