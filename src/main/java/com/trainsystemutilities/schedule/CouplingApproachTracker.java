package com.trainsystemutilities.schedule;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 連結モード列車の信号接近状態を追跡する。
 * NavigationMixinから使用。
 */
public class CouplingApproachTracker {

    public enum Phase {
        WAITING_SIGNAL, // 信号で停止中（Createの通常動作）
        PAUSING,        // 信号前で一時停止中（カウントダウン）
        CRAWLING        // 徐行侵入中
    }

    private static final Map<UUID, TrackState> states = new HashMap<>();
    private static final int PAUSE_TICKS = 40; // 2秒

    public static class TrackState {
        public Phase phase = Phase.WAITING_SIGNAL;
        public int pauseTicksRemaining = PAUSE_TICKS;
    }

    public static TrackState getOrCreate(UUID trainId) {
        return states.computeIfAbsent(trainId, k -> new TrackState());
    }

    public static void remove(UUID trainId) {
        states.remove(trainId);
    }

    public static int getPauseTicks() {
        return PAUSE_TICKS;
    }
}
