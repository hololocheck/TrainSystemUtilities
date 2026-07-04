package com.trainsystemutilities.station.routing;

import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「現在地 → 駅 (徒歩) → 列車 (複数 hop) → 駅 → 現在地以外目的地 (徒歩)」を統合する経路探索。
 *
 * <p>{@link WalkingPathfinder} + {@link TrainRouter} を合成。
 *
 * <p>出力: {@link ComposedRoute} = walk-to-from + train-legs + walk-to-target
 * (ここでは目的地は「駅グループ」自体として扱う; 駅グループ内の任意位置までの徒歩は MVP では省略)。
 */
public final class ComposedRouteFinder {

    private ComposedRouteFinder() {}

    public record WalkLeg(int approxTicks, int distance, BlockPos start, BlockPos end) {}
    public record ComposedRoute(
            boolean found,
            int totalTicks,
            WalkLeg walkToFrom,
            UUID fromGroupId,
            String fromGroupName,
            UUID toGroupId,
            String toGroupName,
            List<TrainRouter.Leg> trainLegs,
            String reason
    ) {
        public static ComposedRoute notFound(String reason) {
            return new ComposedRoute(false, Integer.MAX_VALUE, null, null, null, null, null,
                    List.of(), reason);
        }
    }

    /**
     * 上位 K 候補を返す版。findRoutes を呼んで walkToFrom + 各 train route で ComposedRoute を構築。
     */
    public static java.util.List<ComposedRoute> findMultiple(MinecraftServer server, ServerLevel level,
                                                              BlockPos playerPos, UUID fromGroupId,
                                                              UUID toGroupId, int k) {
        java.util.List<ComposedRoute> out = new java.util.ArrayList<>();
        if (server == null || level == null || playerPos == null || toGroupId == null) return out;
        var data = StationGroupSavedData.get(server);
        StationGroup toGroup = data.get(toGroupId);
        if (toGroup == null) return out;

        // walk-to-from: 一度だけ計算
        WalkLeg walk = null;
        StationGroup fromGroup = null;
        if (fromGroupId != null) {
            fromGroup = data.get(fromGroupId);
            if (fromGroup != null
                    && fromGroup.dimensionId().equals(level.dimension().location().toString())) {
                var wr = StationWalkTargetSelector.findPathToGroup(level, playerPos, fromGroup, 0);
                if (wr.reachable()) {
                    walk = new WalkLeg(wr.path().approxTicks(), wr.path().cost(), playerPos, wr.goal());
                }
            }
        } else {
            // 自動から最近駅
            String dimId = level.dimension().location().toString();
            for (StationGroup g : data.all()) {
                if (!g.dimensionId().equals(dimId)) continue;
                var wr = StationWalkTargetSelector.findPathToGroup(level, playerPos, g, 0);
                if (wr.reachable()) {
                    walk = new WalkLeg(wr.path().approxTicks(), wr.path().cost(), playerPos, wr.goal());
                    fromGroup = g;
                    fromGroupId = g.id();
                    break;
                }
            }
        }
        if (fromGroup == null) {
            ComposedRoute single = find(server, level, playerPos, fromGroupId, toGroupId);
            if (single.found()) out.add(single);
            return out;
        }

        var trainRoutes = TrainRouter.findRoutes(server, fromGroupId, toGroupId, k);
        int walkTicks = walk == null ? 0 : walk.approxTicks();
        for (var tr : trainRoutes) {
            int total = walkTicks + tr.totalTicks();
            out.add(new ComposedRoute(true, total, walk,
                    fromGroupId, fromGroup.name(),
                    toGroupId, toGroup.name(),
                    tr.legs(), "ok"));
        }
        return out;
    }

    /**
     * @param server         サーバ
     * @param level          現在地のレベル (徒歩経路用)
     * @param playerPos      プレイヤー現在地
     * @param fromGroupId    出発駅グループ ID (null = プレイヤー位置から自動選択)
     * @param toGroupId      目的駅グループ ID
     * @return ComposedRoute
     */
    public static ComposedRoute find(MinecraftServer server, ServerLevel level,
                                     BlockPos playerPos, UUID fromGroupId, UUID toGroupId) {
        if (server == null || level == null || playerPos == null || toGroupId == null) {
            return ComposedRoute.notFound("invalid args");
        }
        var data = StationGroupSavedData.get(server);
        StationGroup toGroup = data.get(toGroupId);
        if (toGroup == null) return ComposedRoute.notFound("destination group not found");

        // ルート 1: ユーザが明示的に from を指定した場合は徒歩計算をスキップして
        // 直接列車経路を引く (異 dim 等で徒歩できない場合も結果が返る)。
        if (fromGroupId != null) {
            StationGroup fromGroup = data.get(fromGroupId);
            if (fromGroup == null) return ComposedRoute.notFound("origin group not found");
            if (fromGroupId.equals(toGroupId)) {
                return new ComposedRoute(true, 0,
                        null, fromGroupId, fromGroup.name(), toGroupId, toGroup.name(),
                        List.of(), "same-station");
            }
            // 同じ dim にいて、明示 from が徒歩到達できればその時間を加算 (オプション)。
            WalkLeg walk = null;
            int walkTicks = 0;
            if (fromGroup.dimensionId().equals(level.dimension().location().toString())) {
                var wr = StationWalkTargetSelector.findPathToGroup(level, playerPos, fromGroup, 0);
                if (wr.reachable()) {
                    walk = new WalkLeg(wr.path().approxTicks(), wr.path().cost(), playerPos, wr.goal());
                    walkTicks = wr.path().approxTicks();
                }
            }
            TrainRouter.Route trainRoute = TrainRouter.findRoute(server, fromGroupId, toGroupId);
            if (!trainRoute.found()) {
                return ComposedRoute.notFound("no train route from "
                        + fromGroup.name() + " to " + toGroup.name());
            }
            return new ComposedRoute(true, walkTicks + trainRoute.totalTicks(),
                    walk, fromGroupId, fromGroup.name(), toGroupId, toGroup.name(),
                    trainRoute.legs(), "ok");
        }

        // ルート 2: from 未指定 → 徒歩で行ける最近の駅グループを自動選択。
        String dimId = level.dimension().location().toString();
        List<StationGroup> candidates = new ArrayList<>();
        for (StationGroup g : data.all()) {
            if (!g.dimensionId().equals(dimId)) continue;
            candidates.add(g);
        }
        candidates.sort((a, b) -> {
            double da = squaredDistanceToGroup(playerPos, a);
            double db = squaredDistanceToGroup(playerPos, b);
            return Double.compare(da, db);
        });

        WalkLeg bestWalk = null;
        StationGroup bestFrom = null;
        int tries = 0;
        for (StationGroup g : candidates) {
            if (tries++ >= 5) break;
            var wr = StationWalkTargetSelector.findPathToGroup(level, playerPos, g, 0);
            if (!wr.reachable()) continue;
            if (g.id().equals(toGroupId)) {
                return new ComposedRoute(true, wr.path().approxTicks(),
                        new WalkLeg(wr.path().approxTicks(), wr.path().cost(), playerPos, wr.goal()),
                        g.id(), g.name(), toGroupId, toGroup.name(),
                        List.of(), "walk-only");
            }
            bestWalk = new WalkLeg(wr.path().approxTicks(), wr.path().cost(), playerPos, wr.goal());
            bestFrom = g;
            break;
        }
        if (bestFrom == null) {
            return ComposedRoute.notFound("no reachable station within walking distance");
        }
        TrainRouter.Route trainRoute = TrainRouter.findRoute(server, bestFrom.id(), toGroupId);
        if (!trainRoute.found()) {
            return ComposedRoute.notFound("no train route from "
                    + bestFrom.name() + " to " + toGroup.name());
        }
        int total = bestWalk.approxTicks() + trainRoute.totalTicks();
        return new ComposedRoute(true, total,
                bestWalk, bestFrom.id(), bestFrom.name(), toGroupId, toGroup.name(),
                trainRoute.legs(), "ok");
    }

    private static double squaredDistanceToGroup(BlockPos p, StationGroup g) {
        double cx = (g.minPos().getX() + g.maxPos().getX()) / 2.0;
        double cy = (g.minPos().getY() + g.maxPos().getY()) / 2.0;
        double cz = (g.minPos().getZ() + g.maxPos().getZ()) / 2.0;
        double dx = cx - p.getX(), dy = cy - p.getY(), dz = cz - p.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

}
