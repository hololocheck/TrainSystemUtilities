package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 乗換案内端末の経路検索 — the player's transit host's {@code search} action ({@link TransitData}; TransitSearchPayload
 * until MANTA_7_CONCEPT C4). The body is the payload's handler; the answer (TransitResultPayload until then) is the
 * host's route fields.
 */
public final class TransitSearchAction {

    /** P0-4 #13: 経路探索 DoS 防御。 player UUID → 直近 search 開始時刻 (nano)。
     *  ComposedRouteFinder は重い処理 (= 全駅 BFS + 時刻表 join) で連射されると server tick lag。 */
    private static final ConcurrentHashMap<UUID, Long> LAST_SEARCH_NS = new ConcurrentHashMap<>();
    /** 同一プレイヤーは 5 秒に 1 回まで。 transit-state.json の ratePerSecond はこれより緩く、 決めるのはこちら。 */
    private static final long SEARCH_INTERVAL_NS = 5L * 1_000_000_000L;

    private TransitSearchAction() {}

    public static void apply(ServerPlayer player, UUID fromGroupId, UUID toGroupId) {
        // P0-4 #13: atomic check-then-set で per-player 5 秒 rate limit (DoS 防御)。
        long now = System.nanoTime();
        Long updated = LAST_SEARCH_NS.compute(player.getUUID(), (k, prev) -> {
            if (prev != null && now - prev < SEARCH_INTERVAL_NS) return prev;
            return now;
        });
        if (updated != now) {
            TrainSystemUtilities.LOGGER.info(
                    "[TransitSearch] rate-limited from {}", player.getName().getString());
            return;
        }
        long dayTime = player.serverLevel().getDayTime();
        // 上位 3 候補を取得
        List<ComposedRouteFinder.ComposedRoute> multi = ComposedRouteFinder.findMultiple(
                player.server, player.serverLevel(), player.blockPosition(),
                fromGroupId, toGroupId, 3);
        String reason = "";
        if (multi.isEmpty()) {
            // フォールバック: 単一探索でメッセージ取得
            var single = ComposedRouteFinder.find(player.server, player.serverLevel(),
                    player.blockPosition(), fromGroupId, toGroupId);
            reason = single.reason() == null ? "" : single.reason();
            if (single.found()) multi = List.of(single);
        }
        TransitData.answerRoutes(player, multi, reason, dayTime);
    }
}
