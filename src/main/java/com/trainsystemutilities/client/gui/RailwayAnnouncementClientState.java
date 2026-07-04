package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.announce.AnnouncementConfig;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Client-side: server からの AnnouncementSyncPayload で受信した
 * 各 BE の {@link AnnouncementConfig} を保持するキャッシュ。
 *
 * <p>Screen は {@link #getConfig(BlockPos)} で表示データを取得する。
 */
public final class RailwayAnnouncementClientState {

    private RailwayAnnouncementClientState() {}

    private static final Map<BlockPos, AnnouncementConfig> cache = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Integer> playingEntries = new ConcurrentHashMap<>();

    public static void setConfig(BlockPos pos, AnnouncementConfig cfg) {
        if (pos == null) return;
        if (cfg == null) cache.remove(pos);
        else cache.put(pos.immutable(), cfg);
    }

    public static AnnouncementConfig getConfig(BlockPos pos) {
        if (pos == null) return null;
        return cache.get(pos);
    }

    public static void setPlayingEntry(BlockPos pos, int entryIdx) {
        if (pos == null) return;
        if (entryIdx < 0) playingEntries.remove(pos);
        else playingEntries.put(pos.immutable(), entryIdx);
    }

    public static int getPlayingEntry(BlockPos pos) {
        if (pos == null) return -1;
        return playingEntries.getOrDefault(pos, -1);
    }

    public static void clear() {
        cache.clear();
        playingEntries.clear();
    }
}
