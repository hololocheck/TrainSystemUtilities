package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.schedule.CouplingSignalController;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: 連結/切り離し時の信号オーバーライド (赤青/赤白点滅) を同期。
 *
 * <p>{@link CouplingSignalController} の override は従来サーバー側の static map にだけ書かれ、
 * 描画 mixin (client) がそこから読んでいた。 SP は同一 JVM のため読めるが、 専用サーバー (MP) では
 * client の map が空 → 点滅しない (= #8「連結信号が MP で無動作」)。 本 payload で server が override の
 * 変化時 + active 中 (5tick 毎) に当該 chunk の tracking client へ配信し、 client 側 map を埋める。
 *
 * <p>{@code stateOrdinal} は {@code SignalOverrideState.ordinal()} (0=NONE / 1=RED_BLUE_BLINK /
 * 2=RED_WHITE_SIMULTANEOUS)。
 */
public record CouplingSignalOverridePayload(BlockPos pos, int stateOrdinal) implements CustomPacketPayload {

    public static final Type<CouplingSignalOverridePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "coupling_signal_override"));

    public static final StreamCodec<FriendlyByteBuf, CouplingSignalOverridePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeVarInt(p.stateOrdinal);
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        int ord = buf.readVarInt();
                        // NONE / RED_BLUE_BLINK / RED_WHITE_SIMULTANEOUS の 3 値のみ。範囲外は NONE 扱い。
                        if (ord < 0 || ord > 2) ord = 0;
                        return new CouplingSignalOverridePayload(pos, ord);
                    });

    public static void handle(CouplingSignalOverridePayload p, IPayloadContext context) {
        // S→C: client thread で override map へ反映 (描画 mixin はそこを読む)
        context.enqueueWork(() -> CouplingSignalController.putClientSignalOverride(
                p.pos, p.stateOrdinal, System.currentTimeMillis()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
