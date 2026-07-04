package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.blockentity.MonitorLayoutPanel;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side static cache shared between {@link ManagementComputerScreenV2}
 * (writer) and {@link com.trainsystemutilities.client.renderer.MonitorWorldRenderer}
 * (reader). 旧 V1 ManagementComputerScreen の static フィールドだったものを
 * V1 削除に伴い独立 util に移動。
 *
 * <p>HOTFIX N+0.5 #3: 旧 {@code HashMap} は writer (= GUI thread) と reader (= render
 * thread) から無同期で触られ、 ConcurrentModificationException と torn read が常時発火
 * しうる (WF-G client-state-3/4/20/21)。 {@link ConcurrentHashMap} 化し、 value List も
 * {@link List#copyOf} で immutable に固定する。
 *
 * <p>HOTFIX N+0.5 #6: server 切替時に前 server の state が新 server に流入するのを防ぐ
 * {@link #clear()} メソッドを追加。 {@link com.trainsystemutilities.event.ClientLifecycleHotfixHandler}
 * から {@code ClientPlayerNetworkEvent.LoggingOut} 経由で呼ばれる。
 */
public final class MonitorClientCache {

    /** モニター BE 単位の有効/無効フラグ。Renderer はこれを参照して描画スキップ判断。 */
    public static final Map<BlockPos, Boolean> monitorEnabledCache = new ConcurrentHashMap<>();

    /** モニター BE 単位のレイアウト (パネル) スナップショット。
     *  Server BE / Client BE と並んで triple-write される 1 つ。
     *  value は {@link #putLayout} 経由で {@link List#copyOf} 化した immutable List。 */
    public static final Map<BlockPos, List<MonitorLayoutPanel>> layoutCache = new ConcurrentHashMap<>();

    private MonitorClientCache() {}

    /** layoutCache への書込みヘルパー: caller の mutable list を immutable snapshot に固定する。
     *  これにより renderer 側 iteration 中に writer 側が同 list を mutate しても torn read が発生しない。 */
    public static void putLayout(BlockPos pos, List<MonitorLayoutPanel> panels) {
        if (pos == null) return;
        layoutCache.put(pos, panels == null ? List.of() : List.copyOf(panels));
    }

    /** HOTFIX N+0.5 #6: ClientDisconnect / 別 server 接続時の全 cache クリア。 */
    public static void clear() {
        monitorEnabledCache.clear();
        layoutCache.clear();
    }
}
