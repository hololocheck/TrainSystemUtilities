package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.block.SubstationMultiblock;
import com.trainsystemutilities.electrification.blockentity.SubstationBlockEntity;
import com.trainsystemutilities.electrification.wire.EnergizedWireState;
import com.trainsystemutilities.electrification.wire.SubstationRegistry;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireNetworkSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 変電所の給電 tick をグローバルに実行するハンドラ。
 *
 * <p><b>設計理由:</b>
 * 変電所 BE を BlockEntityTicker で動かすと、所属チャンクが非ロードの間は tick が止まり
 * 架線網が「非通電」と判定されてしまう (= {@link EnergizedWireState} が空になる)。
 * 走行中の列車から見ると「変電所はあるのに架線が通電していない」状態になり、給電できず
 * FE バッファが枯渇して停止する致命的バグ。
 *
 * <p>これを回避するため、{@link SubstationRegistry} (per-dimension SavedData) に登録された
 * 全変電所を {@link ServerTickEvent.Post} で走査し、{@link WireNetworkSavedData} (= 常時メモリ
 * 常駐の SavedData) と {@link EnergizedWireState} のみを参照してチャンクロード無関係に給電する。
 *
 * <p>必要な情報はすべて SavedData にあるため、変電所 BE 自体は不要。
 * BE がロードされている場合のみ、表示用の接続数と FE 残量を BE 側にも書き戻す。
 *
 * <p>呼び出し順: {@link ElectrificationTickHandler#onPre} → pending クリア
 * → 本ハンドラ {@link #tickAll(MinecraftServer)} (= ElectrificationTickHandler.onPost
 * の冒頭から呼ばれる) → markEnergized() → 同 onPost 末尾で commit() という流れ。 */
public final class SubstationTickHandler {

    private SubstationTickHandler() {}

    /** 直前 tick で「FE 不足/未接続だった変電所」のログを抑制するためのカウンタ。 */
    private static int logCountdown = 0;
    private static final int LOG_INTERVAL_TICKS = 200; // 10 秒に 1 回

    /** 変電所 1 個の到達ネットワーク (= reachable nodes + 該当 connections)。
     *  配線トポロジ (= WireNetworkSavedData.generation) と facing が同じなら毎 tick 再利用し、
     *  collectReachable() の BFS と wires.all() 全走査を skip する。 FE 依存ロジックは毎 tick 実行。 */
    private record CachedNet(long wireGen, Direction facing,
                             Set<BlockPos> reachable, List<WireConnection> netConnections) {}

    /** dimension → (変電所 packed pos → cache)。 server 停止時に {@link #clearCache()} で破棄。 */
    private static final Map<ResourceKey<Level>, Map<Long, CachedNet>> NET_CACHE = new HashMap<>();

    /** 統合サーバ再入で stale topology cache が残らないよう server 停止時に呼ぶ。 */
    public static void clearCache() { NET_CACHE.clear(); }

    /** {@link ElectrificationTickHandler#onPost} の冒頭から呼ばれる。 */
    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        boolean shouldLog = (--logCountdown <= 0);
        if (shouldLog) logCountdown = LOG_INTERVAL_TICKS;

        for (ServerLevel level : server.getAllLevels()) {
            tickLevel(level, shouldLog);
        }
    }

    private static void tickLevel(ServerLevel level, boolean shouldLog) {
        SubstationRegistry reg = SubstationRegistry.get(level);
        WireNetworkSavedData wires = WireNetworkSavedData.get(level);

        if (shouldLog) {
        }

        if (reg.size() == 0 || wires.size() == 0) return;

        EnergizedWireState energized = EnergizedWireState.get(level);
        int totalActive = 0, totalConnections = 0, skippedNoFe = 0, skippedNoNet = 0, skippedCost = 0;

        long wireGen = wires.generation();
        Map<Long, CachedNet> levelCache = NET_CACHE.computeIfAbsent(level.dimension(), k -> new HashMap<>());

        for (long packed : reg.allPackedPositions()) {
            BlockPos pos = BlockPos.of(packed);
            int fe = reg.getFe(pos);
            if (fe <= 0) {
                skippedNoFe++;
                writeBackBeStats(level, pos, 0, fe);
                if (shouldLog) TrainSystemUtilities.LOGGER.info(
                        "[SubsTick]   sub@{} SKIP fe={} (no power)", pos, fe);
                continue;
            }

            Direction facing = reg.getFacing(pos);
            // 到達ネットワークはトポロジ (wireGen) と facing にのみ依存。 変化が無ければ cache 再利用し
            // BFS (collectReachable) と wires.all() 全走査を skip する。 FE 依存ロジックは下で毎 tick 実行。
            CachedNet cn = levelCache.get(packed);
            Set<BlockPos> reachable;
            List<WireConnection> netConnections;
            if (cn != null && cn.wireGen() == wireGen && cn.facing() == facing) {
                reachable = cn.reachable();
                netConnections = cn.netConnections();
            } else {
                reachable = collectReachable(wires, pos, facing);
                if (reachable.isEmpty()) {
                    netConnections = List.of();
                } else {
                    netConnections = new ArrayList<>();
                    for (WireConnection c : wires.all()) {
                        if (reachable.contains(c.nodeA()) && reachable.contains(c.nodeB())) {
                            netConnections.add(c);
                        }
                    }
                }
                levelCache.put(packed, new CachedNet(wireGen, facing, reachable, netConnections));
            }
            if (reachable.isEmpty()) {
                skippedNoNet++;
                writeBackBeStats(level, pos, 0, fe);
                if (shouldLog) TrainSystemUtilities.LOGGER.info(
                        "[SubsTick]   sub@{} SKIP fe={} (no insulator/wire adjacent)", pos, fe);
                continue;
            }

            if (netConnections.isEmpty()) {
                skippedNoNet++;
                writeBackBeStats(level, pos, 0, fe);
                if (shouldLog) TrainSystemUtilities.LOGGER.info(
                        "[SubsTick]   sub@{} SKIP fe={} reachableNodes={} (no connections in network)",
                        pos, fe, reachable.size());
                continue;
            }

            // P0-11 Critical fix: int overflow で cost が負値 → fe < cost が常に false 化
            // し、 大量 wire (~2B) 構築で FE 無制限抽出可能 (WF-B security-19)。 SafeMath で
            // saturating 化、 overflow 時は Integer.MAX_VALUE に頭打ちで cost check は確実に reject。
            int cost = belugalab.mcss3.util.math.SafeMath.saturatingMul(
                    netConnections.size(), SubstationBlockEntity.FE_PER_CONNECTION_PER_TICK);
            if (fe < cost) {
                skippedCost++;
                writeBackBeStats(level, pos, 0, fe);
                if (shouldLog) TrainSystemUtilities.LOGGER.info(
                        "[SubsTick]   sub@{} SKIP fe={} cost={} (insufficient)", pos, fe, cost);
                continue;
            }

            energized.markEnergized(netConnections);
            int newFe = fe - cost;
            reg.setFe(pos, newFe);
            writeBackBeStats(level, pos, netConnections.size(), newFe);

            totalActive++;
            totalConnections += netConnections.size();

            if (shouldLog) TrainSystemUtilities.LOGGER.info(
                    "[SubsTick]   sub@{} ACTIVE fe={}→{} conns={} reachable={}",
                    pos, fe, newFe, netConnections.size(), reachable.size());
        }

        if (shouldLog) {
        }
    }

    /** 変電所キュービクル (3×4×2 マルチブロック) の **全 24 マス** それぞれの外面から BFS して
     *  到達ノードを集める。マルチブロック内部の面 (= 隣も同じマルチブロック) はスキップして
     *  内部誤検出を防ぐ。
     *
     *  <p>これによりプレイヤーがダミーブロックの面に碍子を取り付けても、コアブロックと同じ
     *  ネットワークとして検出される。 */
    private static Set<BlockPos> collectReachable(WireNetworkSavedData wires, BlockPos corePos,
                                                    Direction facing) {
        Set<BlockPos> multiBlockSet = new HashSet<>(
                SubstationMultiblock.getAllPositions(corePos, facing));
        Set<BlockPos> reachable = new HashSet<>();
        for (BlockPos mbPos : multiBlockSet) {
            for (Direction d : Direction.values()) {
                BlockPos adj = mbPos.relative(d);
                // 自分自身のマルチブロック内部の面はスキップ
                if (multiBlockSet.contains(adj)) continue;
                // 接続を持たない adj は碍子ではない
                if (wires.incident(adj).isEmpty()) continue;
                reachable.addAll(wires.reachableNodes(adj));
            }
        }
        return reachable;
    }

    /** BE がロードされていれば「直前計算した接続数」と「最新 FE」を表示用に書き戻す。
     *  非ロード時は GUI が見えないので noop で問題なし。 */
    private static void writeBackBeStats(ServerLevel level, BlockPos pos, int connections, int fe) {
        if (!level.hasChunkAt(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SubstationBlockEntity sub) {
            sub.setLastNetworkConnections(connections);
            sub.syncFromRegistry(fe);
        }
    }
}
