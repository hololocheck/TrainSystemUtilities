package com.trainsystemutilities.client.gui;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only: 鉄道管理 BE ごとの「範囲指定ボードの範囲枠を表示する」トグル状態。
 * インメモリのみ (セッションを跨いで保持しない)。
 *
 * <p>アナウンス設定 GUI のトグルがこの集合に対して toggle/set を行い、
 * {@link com.trainsystemutilities.client.renderer.RmbeRangeFrameRenderer} が
 * 集合に含まれる rmbe について世界に範囲枠を描画する。
 *
 * <p>減衰モードのフラグは {@link com.trainsystemutilities.announce.AnnouncementConfig#isAttenuationMode()}
 * (server-synced) を参照する。
 */
public final class RangeFrameToggleState {

    private RangeFrameToggleState() {}

    /** 範囲枠の表示が ON な rmbe 集合。 */
    private static final Set<BlockPos> visible = ConcurrentHashMap.newKeySet();

    public static boolean isEnabled(BlockPos pos) {
        return pos != null && visible.contains(pos);
    }

    public static void set(BlockPos pos, boolean enabled) {
        if (pos == null) return;
        if (enabled) visible.add(pos.immutable());
        else visible.remove(pos);
    }

    public static void toggle(BlockPos pos) {
        if (pos == null) return;
        BlockPos imm = pos.immutable();
        if (!visible.remove(imm)) visible.add(imm);
    }

    public static Set<BlockPos> snapshot() {
        return Collections.unmodifiableSet(visible);
    }
}
