package com.trainsystemutilities.electrification.wire;

import com.trainsystemutilities.network.WireSyncPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 架線接続の S2C 同期ヘルパ。
 *
 * <p>SavedData の現在状態をスナップショット化して {@link WireSyncPayload} にまとめ、
 * 単一プレイヤーまたはレベル全プレイヤーへ送信する。差分送信は実装せず full sync で統一
 * (= 1000 接続でも 18KB なので毎回 full でも実用的)。
 */
public final class WireSyncBroadcaster {

    private WireSyncBroadcaster() {}

    public static WireSyncPayload snapshot(ServerLevel level) {
        WireNetworkSavedData data = WireNetworkSavedData.get(level);
        EnergizedWireState energizedState = EnergizedWireState.get(level);
        List<WireSyncPayload.WireData> wires = new ArrayList<>(data.size());
        for (WireConnection c : data.all()) {
            wires.add(new WireSyncPayload.WireData(
                    c.nodeA(), c.nodeB(), c.facingA(), c.facingB(),
                    energizedState.isEnergized(c), c.type(), c.sag(),
                    c.customThickness(), c.customTrolleyOffset(),
                    c.customDropperInterval(), c.customRowCount()));
        }
        return new WireSyncPayload(wires);
    }

    /** 1 プレイヤーへ送信 (= login / dimension change 時)。 */
    public static void sendTo(ServerPlayer player) {
        if (player == null) return;
        PacketDistributor.sendToPlayer(player, snapshot(player.serverLevel()));
    }

    /** レベル全プレイヤーへ broadcast (= 接続の追加/削除時)。 */
    public static void broadcast(ServerLevel level) {
        if (level == null) return;
        WireSyncPayload payload = snapshot(level);
        for (ServerPlayer p : level.players()) {
            PacketDistributor.sendToPlayer(p, payload);
        }
    }
}
