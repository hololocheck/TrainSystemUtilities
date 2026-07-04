package com.trainsystemutilities.client.gui;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 21: 鉄道管理ブロック ごとに、 ホームドア group の world highlight (= 緑線枠)
 * を表示するかをユーザーが client-only にトグル。 server 同期不要。
 *
 * <p>デフォルト ON (= popup 開いている時に highlight 出る)。
 */
public final class ScreenDoorHighlightToggleState {

    private ScreenDoorHighlightToggleState() {}

    private static final Map<BlockPos, Boolean> states = new ConcurrentHashMap<>();

    public static boolean isEnabled(BlockPos pos) {
        if (pos == null) return false;
        return Boolean.TRUE.equals(states.get(pos));
    }

    public static void toggle(BlockPos pos) {
        if (pos == null) return;
        states.put(pos.immutable(), !isEnabled(pos));
    }
}
