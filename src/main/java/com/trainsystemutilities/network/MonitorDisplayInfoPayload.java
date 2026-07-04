package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.contraption.MonitorMovementBehaviour;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: からくりモニター (走行中コントラプションのモニター) の表示データ配信。
 *
 * <p>{@link MonitorMovementBehaviour} の表示データ ({@code TrainDisplayInfo}) は従来サーバー側で
 * static cache に書き込み、 描画時にそこから読んでいた。 SP は同一 JVM のため読めるが、 専用サーバー
 * (MP) ではクライアントの cache が永久に空になり {@code info == null} → MCSS 仕様で全 {@code visibleKey}
 * 要素が表示される (= 列車種別バッジ 各停/快速/急行 が全種別出る等) 不具合になっていた。
 *
 * <p>本 payload で server が計算した表示データを、 当該 carriage entity を tracking 中のクライアントへ
 * 配信し、 クライアント側 displayCache を埋める。 描画経路は従来通り (= info を読むだけ) で MP/SP 共通。
 */
public record MonitorDisplayInfoPayload(int entityId, UUID trainId,
                                        String routeType, String trainType,
                                        String couplingStatus, String couplingPartner,
                                        List<Stop> stops) implements CustomPacketPayload {

    /** 次停車駅 1 件分 (= {@code MonitorMovementBehaviour.StopInfo} の wire 表現)。 */
    public record Stop(String stationName, int stopSec,
                       long estArrivalDayTime, long estDepartureDayTime, boolean atStation) {}

    public static final Type<MonitorDisplayInfoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "monitor_display_info"));

    public static final StreamCodec<FriendlyByteBuf, MonitorDisplayInfoPayload> STREAM_CODEC =
            StreamCodec.of(MonitorDisplayInfoPayload::write, MonitorDisplayInfoPayload::read);

    private static String nz(String s) { return s == null ? "" : s; }

    private static void write(FriendlyByteBuf buf, MonitorDisplayInfoPayload p) {
        buf.writeVarInt(p.entityId);
        buf.writeUUID(p.trainId);
        buf.writeUtf(nz(p.routeType));
        buf.writeUtf(nz(p.trainType));
        buf.writeUtf(nz(p.couplingStatus));
        buf.writeUtf(nz(p.couplingPartner));
        buf.writeVarInt(p.stops.size());
        for (Stop s : p.stops) {
            buf.writeUtf(nz(s.stationName()));
            buf.writeVarInt(s.stopSec());
            buf.writeVarLong(s.estArrivalDayTime());
            buf.writeVarLong(s.estDepartureDayTime());
            buf.writeBoolean(s.atStation());
        }
    }

    private static MonitorDisplayInfoPayload read(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID trainId = buf.readUUID();
        // 種別 / 路線 / 連結ステータスは短いコード、 連結相手 / 駅名は列車・駅名 (= ユーザー由来) のため緩めに bound
        String routeType = BoundedStreamCodec.readBoundedUtf(buf, 64);
        String trainType = BoundedStreamCodec.readBoundedUtf(buf, 64);
        String couplingStatus = BoundedStreamCodec.readBoundedUtf(buf, 64);
        String couplingPartner = BoundedStreamCodec.readBoundedUtf(buf, 256);
        // からくりモニターは「次の 2 駅」しか積まないが、 念のため余裕をもって 8 で bound
        int n = BoundedStreamCodec.readBoundedListLength(buf, 8);
        List<Stop> stops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = BoundedStreamCodec.readBoundedUtf(buf, 256);
            int stopSec = buf.readVarInt();
            long estArr = buf.readVarLong();
            long estDep = buf.readVarLong();
            boolean at = buf.readBoolean();
            stops.add(new Stop(name, stopSec, estArr, estDep, at));
        }
        return new MonitorDisplayInfoPayload(entityId, trainId,
                routeType, trainType, couplingStatus, couplingPartner, stops);
    }

    public static void handle(MonitorDisplayInfoPayload payload, IPayloadContext context) {
        // S→C: client thread で displayCache へ反映 (描画は従来通りそこを読む)
        context.enqueueWork(() -> MonitorMovementBehaviour.applyServerDisplayInfo(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
