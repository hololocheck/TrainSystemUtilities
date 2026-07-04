package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.transit.TransitNavClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 徒歩経路ペイロード。
 *
 * <p>{@code path} に経由 BlockPos 列を含み、クライアント側で
 * {@link com.trainsystemutilities.client.transit.TransitNavRenderer} がベクター線で地面に描画する。
 */
public record NavPathPayload(UUID targetGroupId, String targetName,
                              List<BlockPos> path, int approxTicks,
                              String error) implements CustomPacketPayload {

    public static final Type<NavPathPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "nav_path"));

    public static final StreamCodec<FriendlyByteBuf, NavPathPayload> STREAM_CODEC =
            StreamCodec.of(NavPathPayload::write, NavPathPayload::read);

    public static NavPathPayload empty(String reason) {
        return new NavPathPayload(null, "", List.of(), 0, reason);
    }

    private static void write(FriendlyByteBuf buf, NavPathPayload p) {
        buf.writeBoolean(p.targetGroupId != null);
        if (p.targetGroupId != null) buf.writeUUID(p.targetGroupId);
        buf.writeUtf(p.targetName == null ? "" : p.targetName, 64);
        buf.writeVarInt(p.approxTicks);
        buf.writeUtf(p.error == null ? "" : p.error, 128);
        // path は最大 1024 ブロックまで送信
        int n = Math.min(1024, p.path.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            BlockPos b = p.path.get(i);
            buf.writeVarInt(b.getX());
            buf.writeVarInt(b.getY());
            buf.writeVarInt(b.getZ());
        }
    }

    private static NavPathPayload read(FriendlyByteBuf buf) {
        UUID id = buf.readBoolean() ? buf.readUUID() : null;
        // P0-4 #7: 駅 / グループ名は generic label → 128 bytes
        String name = BoundedStreamCodec.readBoundedUtf(buf, 128);
        // P0-4 #7 hotfix2: server→client。 server は負値 sentinel (unreachable) も送る。
        int ticks = buf.readVarInt();
        // P0-4 #7: error message は long text → 1024 bytes
        String error = BoundedStreamCodec.readBoundedUtf(buf, 1024);
        // P0-4 #7: write 側で最大 1024 ブロックに clamp 済 → 同じ上限
        int n = BoundedStreamCodec.readBoundedListLength(buf, 1024);
        List<BlockPos> path = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // P0-4 #7: world 座標は varint で送られ負数可。 manual 範囲チェック (±3000 万を安全側に ±32M)
            int bx = buf.readVarInt();
            int by = buf.readVarInt();
            int bz = buf.readVarInt();
            if (Math.abs(bx) > 32_000_000 || Math.abs(by) > 32_000_000 || Math.abs(bz) > 32_000_000) {
                throw new IllegalArgumentException(
                        "NavPath BlockPos (" + bx + "," + by + "," + bz + ") out of world bounds");
            }
            path.add(new BlockPos(bx, by, bz));
        }
        return new NavPathPayload(id, name, path, ticks, error);
    }

    public static void handle(NavPathPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> {
            if (!payload.error.isEmpty() || payload.path.isEmpty()) {
                TransitNavClientState.setError(payload.error);
                return;
            }
            TransitNavClientState.setActivePath(
                    payload.targetGroupId, payload.targetName,
                    payload.path, payload.approxTicks);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
