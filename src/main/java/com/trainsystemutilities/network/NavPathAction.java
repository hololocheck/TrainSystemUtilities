package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.routing.StationWalkTargetSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最寄り駅 (もしくは指定駅グループ) までの徒歩経路 — the player's transit host's {@code nav} action ({@link TransitData};
 * NavPathRequestPayload until MANTA_7_CONCEPT C4). A null target is the nearest station. The body is the payload's
 * handler: {@code WalkingPathfinder}'s 3D voxel A* through {@code NavPathDispatcher}, whose callback runs on the server
 * thread; the answer (NavPathPayload until then) is the host's nav fields.
 */
public final class NavPathAction {

    /** P0-4 #13: per-player rate limit。 徒歩経路探索は 3D voxel A* で重く、 連射されると
     *  server thread が詰まる。 inflight 数ではなく request 間隔で抑制 (= 単純 / 確実)。
     *  本格的な async pile-up 防御 (= dispatcher 内部 cap) は P0-5 の routing rework で実施。 */
    private static final ConcurrentHashMap<UUID, Long> LAST_REQUEST_NS = new ConcurrentHashMap<>();
    /** 同一プレイヤーは 2 秒に 1 回まで。 transit-state.json の ratePerSecond はこれより緩い: 超過はここで
     *  "rate limited" と答え、 client の pending を解く (manta の rate は黙って捨てるので、 決めるのはこちら)。 */
    private static final long REQUEST_INTERVAL_NS = 2L * 1_000_000_000L;

    private NavPathAction() {}

    public static void apply(ServerPlayer player, @Nullable UUID targetGroupId, int targetPlatform) {
        // P0-4 #13: atomic check-then-set で 2 秒 rate limit。 async flooding 防御。
        long now = System.nanoTime();
        Long updated = LAST_REQUEST_NS.compute(player.getUUID(), (k, prev) -> {
            if (prev != null && now - prev < REQUEST_INTERVAL_NS) return prev;
            return now;
        });
        if (updated != now) {
            TrainSystemUtilities.LOGGER.info(
                    "[NavPath] rate-limited from {}", player.getName().getString());
            sendError(player, "rate limited");
            return;
        }
        handleInner(player, targetGroupId, targetPlatform);
    }

    private static void handleInner(ServerPlayer player, @Nullable UUID targetGroupId, int targetPlatform) {
            BlockPos playerPos = player.blockPosition();
            String dim = player.serverLevel().dimension().location().toString();
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                    "[NavPath] req from={} target={} platform={} pos={} dim={}",
                    player.getGameProfile().getName(), targetGroupId,
                    targetPlatform, playerPos, dim);

            StationGroup target = null;
            var data = StationGroupSavedData.get(player.server);

            if (targetGroupId != null) {
                target = data.get(targetGroupId);
                if (target == null) { sendError(player, "group not found"); return; }
                if (!target.dimensionId().equals(dim)) { sendError(player, "different dimension"); return; }
                if (!hasStationBlock(target)) { sendError(player, "no station block"); return; }
                final StationGroup t = target;
                com.trainsystemutilities.station.routing.NavPathDispatcher.submit(
                        player, player.serverLevel(), playerPos, t, targetPlatform,
                        (r, err) -> {
                            if (err != null) { sendError(player, err); return; }
                            if (r == null || !r.reachable()) {
                                sendError(player, "not reachable on foot");
                                return;
                            }
                            sendResult(player, t, r);
                        });
                return;
            }

            // targetGroupId == null → 最寄り駅検索 (こちらは候補リストを順次試すため
            // 同期的なまま実行。1 候補ごとに非同期化はオーバーヘッドが大きい)。
            java.util.List<StationGroup> candidates = new java.util.ArrayList<>();
            for (StationGroup g : data.all()) {
                if (g.dimensionId().equals(dim)) candidates.add(g);
            }
            candidates.sort((a, b) -> Double.compare(squaredDist(playerPos, a), squaredDist(playerPos, b)));
            // 最寄り 1 駅だけ非同期で試行 (5 駅試すと遅すぎる)
            StationGroup nearest = null;
            for (StationGroup g : candidates) {
                if (hasStationBlock(g)) { nearest = g; break; }
            }
            if (nearest == null) { sendError(player, "no station with stationBlocks"); return; }
            final StationGroup nf = nearest;
            com.trainsystemutilities.station.routing.NavPathDispatcher.submit(
                    player, player.serverLevel(), playerPos, nf, 0,
                    (r, err) -> {
                        if (err != null) { sendError(player, err); return; }
                        if (r == null || !r.reachable()) {
                            sendError(player, "no reachable station");
                            return;
                        }
                        sendResult(player, nf, r);
                    });
    }

    private static void sendError(ServerPlayer player, String reason) {
        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                "[NavPath] failed from={} reason={}",
                player.getGameProfile().getName(), reason);
        TransitData.answerNavError(player, reason);
    }

    private static void sendResult(ServerPlayer player, StationGroup target, StationWalkTargetSelector.PathResult result) {
        java.util.List<BlockPos> path = result.path().path();
        String targetName = result.platform() > 0 ? target.formatStationName(result.platform()) : target.name();
        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                "[NavPath] sending {} blocks to '{}' (ticks={}, goal={}, platform={})",
                path.size(), targetName, result.path().approxTicks(), result.goal(), result.platform());
        TransitData.answerNav(player, target.id(), targetName, path, result.path().approxTicks());
    }

    private static double squaredDist(BlockPos p, StationGroup g) {
        double cx = (g.minPos().getX() + g.maxPos().getX()) / 2.0;
        double cz = (g.minPos().getZ() + g.maxPos().getZ()) / 2.0;
        double dx = cx - p.getX(), dz = cz - p.getZ();
        return dx * dx + dz * dz;
    }
    private static boolean hasStationBlock(StationGroup g) {
        return g.stationBlockPositions() != null && !g.stationBlockPositions().isEmpty();
    }
}
