package com.trainsystemutilities.electrification.wire;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 各架線接続が「通電中か」のスナップショットを保持する per-dimension state。
 *
 * <p>Tick ライフサイクル:
 * <ol>
 *   <li>{@link #beginTick()} — pending クリア (= 各 substation tick 前)</li>
 *   <li>各 substation BE が {@link #markEnergized(Collection)} で自分のネットワークを OR</li>
 *   <li>{@link #commitTick()} — pending を current にスワップし、変化があれば true を返す</li>
 * </ol>
 *
 * <p>SavedData ではなく in-memory state (= 給電は走行中に毎 tick 計算される一時的なもので、
 * セーブする必要がない)。サーバ再起動後は変電所 BE のロード後に再計算される。
 */
public final class EnergizedWireState {

    private static final Map<ResourceKey<Level>, EnergizedWireState> INSTANCES = new ConcurrentHashMap<>();

    public static EnergizedWireState get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level.dimension(), k -> new EnergizedWireState());
    }

    public static void clearAll() {
        INSTANCES.clear();
    }

    private final Set<NodePair> energized = ConcurrentHashMap.newKeySet();
    private final Set<NodePair> pending = ConcurrentHashMap.newKeySet();

    public void beginTick() {
        pending.clear();
    }

    public void markEnergized(Collection<WireConnection> connections) {
        for (WireConnection c : connections) {
            pending.add(NodePair.of(c.nodeA(), c.nodeB()));
        }
    }

    /** pending と current を比較して、差分があれば current を更新し true を返す。 */
    public boolean commitTick() {
        if (pending.equals(energized)) return false;
        energized.clear();
        energized.addAll(pending);
        return true;
    }

    public boolean isEnergized(WireConnection c) {
        if (c == null) return false;
        return energized.contains(NodePair.of(c.nodeA(), c.nodeB()));
    }

    public boolean isEnergized(NodePair pair) {
        return energized.contains(pair);
    }

    public Set<NodePair> snapshot() {
        return new HashSet<>(energized);
    }

    public int size() { return energized.size(); }
}
