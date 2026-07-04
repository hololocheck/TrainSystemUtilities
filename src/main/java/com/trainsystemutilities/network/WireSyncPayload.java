package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.electrification.ClientWireStore;
import com.trainsystemutilities.electrification.wire.WireType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: 架線接続の全同期 (per-dimension full snapshot)。
 *
 * <p>送信タイミング:
 * <ul>
 *   <li>プレイヤー login 時 (= 現在のディメンションの全接続)</li>
 *   <li>ディメンション変更時</li>
 *   <li>架線追加 / 削除時 (= そのレベルの全プレイヤーへ broadcast)</li>
 * </ul>
 *
 * <p>サイズ: 1 接続あたり 18 bytes (a-packed 8 + b-packed 8 + 2 facings)。
 * 1000 接続でも 18KB なので変更ごと full sync で十分。
 */
public record WireSyncPayload(List<WireData> wires) implements CustomPacketPayload {

    public record WireData(BlockPos a, BlockPos b, Direction facingA, Direction facingB,
                            boolean energized, WireType type, boolean sag,
                            float customThickness, float customTrolleyOffset,
                            float customDropperInterval, int customRowCount) {}

    public static final Type<WireSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "wire_sync"));

    public static final StreamCodec<FriendlyByteBuf, WireSyncPayload> STREAM_CODEC =
            StreamCodec.of(WireSyncPayload::write, WireSyncPayload::read);

    private static void write(FriendlyByteBuf buf, WireSyncPayload p) {
        buf.writeVarInt(p.wires.size());
        for (WireData w : p.wires) {
            buf.writeLong(w.a.asLong());
            buf.writeLong(w.b.asLong());
            buf.writeByte(w.facingA.get3DDataValue());
            buf.writeByte(w.facingB.get3DDataValue());
            buf.writeBoolean(w.energized);
            buf.writeByte(w.type.id);
            buf.writeBoolean(w.sag);
            buf.writeFloat(w.customThickness);
            buf.writeFloat(w.customTrolleyOffset);
            buf.writeFloat(w.customDropperInterval);
            buf.writeByte(w.customRowCount);
        }
    }

    private static WireSyncPayload read(FriendlyByteBuf buf) {
        // P0-4 #7: 1 dimension 内の架線接続数 (= 大きい snapshot)。 16K まで許容
        int n = BoundedStreamCodec.readBoundedListLength(buf, 16_384);
        List<WireData> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos a = BlockPos.of(buf.readLong());
            BlockPos b = BlockPos.of(buf.readLong());
            Direction fa = Direction.from3DDataValue(buf.readByte());
            Direction fb = Direction.from3DDataValue(buf.readByte());
            boolean energized = buf.readBoolean();
            WireType ty = WireType.fromId(buf.readByte());
            boolean sag = buf.readBoolean();
            float cth = buf.readFloat();
            float cto = buf.readFloat();
            float cdi = buf.readFloat();
            int crc = buf.readByte();
            list.add(new WireData(a, b, fa, fb, energized, ty, sag, cth, cto, cdi, crc));
        }
        return new WireSyncPayload(list);
    }

    public static void handle(WireSyncPayload payload, IPayloadContext context) {
        net.minecraft.client.Minecraft.getInstance().execute(() ->
                ClientWireStore.set(payload.wires));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
