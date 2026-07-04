package com.trainsystemutilities.electrification.wire;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 架線接続ネットワークの永続化 (per-dimension)。
 *
 * <p>Phase A2: 接続の追加・削除・列挙 + ノード入射インデックス。
 * 給電伝播 (= どの接続が通電中か) と空間インデックスは Phase C1 / A4 で別レイヤに分離する。
 *
 * <p>シリアライズ: 接続 1 本につき (a-packed, b-packed, length) の単純リスト。
 * 数千本でも数十 KB レベルなのでフォーマットは素朴で OK。
 */
public final class WireNetworkSavedData extends SavedData {

    private static final String FILE_NAME = "tsu_wire_network";

    /** NodePair (= canonicalized pair) → connection。 */
    private final Map<NodePair, WireConnection> connections = new ConcurrentHashMap<>();
    /** node packed pos → そのノードに入射する全 NodePair。 */
    private final Map<Long, Set<NodePair>> incidentIndex = new ConcurrentHashMap<>();
    /** chunk バケット空間インデックス (Phase A4)。near-query / induction / pickup で参照。 */
    private final WireSpatialIndex spatialIndex = new WireSpatialIndex();

    /** トポロジ変更世代。 add/remove/removeAllAt で increment。 SubstationTickHandler の
     *  到達ネットワーク cache 無効化に使う (= 配線が変わらない限り BFS を再計算しない)。 */
    private volatile long generation = 0;

    /** 現在のトポロジ世代。 配線が 1 本でも add/remove されると変化する。 */
    public long generation() { return generation; }

    public Collection<WireConnection> all() {
        return Collections.unmodifiableCollection(connections.values());
    }

    public WireConnection get(BlockPos a, BlockPos b) {
        return connections.get(NodePair.of(a, b));
    }

    public boolean exists(BlockPos a, BlockPos b) {
        return connections.containsKey(NodePair.of(a, b));
    }

    /** 既存ペアと衝突する場合は false を返し変更しない。 */
    public boolean add(WireConnection conn) {
        if (conn == null) return false;
        NodePair pair = NodePair.of(conn.nodeA(), conn.nodeB());
        if (connections.putIfAbsent(pair, conn) != null) return false;
        addIncident(conn.nodeA(), pair);
        addIncident(conn.nodeB(), pair);
        spatialIndex.add(conn);
        generation++;
        setDirty();
        return true;
    }

    public boolean remove(BlockPos a, BlockPos b) {
        NodePair pair = NodePair.of(a, b);
        WireConnection removed = connections.remove(pair);
        if (removed == null) return false;
        removeIncident(a, pair);
        removeIncident(b, pair);
        spatialIndex.remove(removed);
        generation++;
        setDirty();
        return true;
    }

    /** {@code node} に入射する全接続を削除し、削除した接続のリストを返す。 */
    public List<WireConnection> removeAllAt(BlockPos node) {
        Set<NodePair> pairs = incidentIndex.remove(node.asLong());
        if (pairs == null || pairs.isEmpty()) return List.of();
        List<WireConnection> removed = new ArrayList<>(pairs.size());
        for (NodePair p : pairs) {
            WireConnection c = connections.remove(p);
            if (c != null) {
                removed.add(c);
                spatialIndex.remove(c);
                BlockPos other = p.a().equals(node) ? p.b() : p.a();
                removeIncident(other, p);
            }
        }
        if (!removed.isEmpty()) {
            generation++;
            setDirty();
        }
        return removed;
    }

    /** {@code node} に入射する接続のリスト (空集合の場合は空 List)。 */
    public List<WireConnection> incident(BlockPos node) {
        Set<NodePair> pairs = incidentIndex.get(node.asLong());
        if (pairs == null || pairs.isEmpty()) return List.of();
        List<WireConnection> result = new ArrayList<>(pairs.size());
        for (NodePair p : pairs) {
            WireConnection c = connections.get(p);
            if (c != null) result.add(c);
        }
        return result;
    }

    public int size() { return connections.size(); }

    public WireSpatialIndex spatialIndex() { return spatialIndex; }

    /**
     * グラフ BFS: {@code start} から接続を辿って到達できる全ノードを返す。
     * 給電伝播 (Phase C1) で「変電所 anchor から到達可能な全碍子」の取得に使う。
     */
    public Set<BlockPos> reachableNodes(BlockPos start) {
        Set<BlockPos> visited = new HashSet<>();
        if (start == null) return visited;
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (WireConnection c : incident(cur)) {
                BlockPos other = c.other(cur);
                if (other != null && visited.add(other)) {
                    queue.add(other);
                }
            }
        }
        return visited;
    }

    private void addIncident(BlockPos node, NodePair pair) {
        incidentIndex.computeIfAbsent(node.asLong(), k -> ConcurrentHashMap.newKeySet()).add(pair);
    }

    private void removeIncident(BlockPos node, NodePair pair) {
        Set<NodePair> set = incidentIndex.get(node.asLong());
        if (set != null) {
            set.remove(pair);
            if (set.isEmpty()) incidentIndex.remove(node.asLong());
        }
    }

    /** P0-5 #3: NBT schema version。 0 = legacy。 */
    private static final int SCHEMA_VERSION = 1;

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag list = new ListTag();
        for (WireConnection c : connections.values()) {
            CompoundTag t = new CompoundTag();
            t.putLong("a", c.nodeA().asLong());
            t.putLong("b", c.nodeB().asLong());
            t.putByte("fa", (byte) c.facingA().get3DDataValue());
            t.putByte("fb", (byte) c.facingB().get3DDataValue());
            t.putDouble("L", c.length());
            t.putByte("ty", (byte) c.type().id);
            t.putBoolean("sg", c.sag());
            // CUSTOM 用パラメータ (= 他タイプでも保存しても害なし)
            t.putFloat("ct", c.customThickness());
            t.putFloat("cto", c.customTrolleyOffset());
            t.putFloat("cdi", c.customDropperInterval());
            t.putByte("crc", (byte) c.customRowCount());
            list.add(t);
        }
        tag.put("Connections", list);
        TrainSystemUtilities.LOGGER.info(
                "[WireNetworkSavedData] saved {} connections", connections.size());
        return tag;
    }

    public static WireNetworkSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.contains("schemaVersion") ? tag.getInt("schemaVersion") : 0;
        if (version > SCHEMA_VERSION) {
            TrainSystemUtilities.LOGGER.warn(
                    "[WireNetworkSavedData] future schemaVersion={} (current={}); best-effort load",
                    version, SCHEMA_VERSION);
        }
        WireNetworkSavedData d = new WireNetworkSavedData();
        ListTag list = tag.getList("Connections", Tag.TAG_COMPOUND);
        int loaded = 0;
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag t = list.getCompound(i);
                BlockPos a = BlockPos.of(t.getLong("a"));
                BlockPos b = BlockPos.of(t.getLong("b"));
                Direction fa = Direction.from3DDataValue(t.getByte("fa"));
                Direction fb = Direction.from3DDataValue(t.getByte("fb"));
                double len = t.getDouble("L");
                WireType ty = t.contains("ty") ? WireType.fromId(t.getByte("ty")) : WireType.TWO_TIER;
                boolean sg = t.contains("sg") && t.getBoolean("sg");
                float cth = t.contains("ct") ? t.getFloat("ct") : WireConnection.DEFAULT_CUSTOM_THICKNESS;
                float cto = t.contains("cto") ? t.getFloat("cto") : WireConnection.DEFAULT_CUSTOM_TROLLEY_OFFSET;
                float cdi = t.contains("cdi") ? t.getFloat("cdi") : WireConnection.DEFAULT_CUSTOM_DROPPER_INTERVAL;
                int crc = t.contains("crc") ? t.getByte("crc") : WireConnection.DEFAULT_CUSTOM_ROW_COUNT;
                WireConnection conn = WireConnection.of(a, b, fa, fb, len, ty, sg, cth, cto, cdi, crc);
                NodePair pair = NodePair.of(a, b);
                d.connections.put(pair, conn);
                d.addIncident(a, pair);
                d.addIncident(b, pair);
                loaded++;
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn(
                        "[WireNetworkSavedData] entry {} load failed: {}", i, e.toString());
            }
        }
        // load 後に spatial index を一括構築。
        d.spatialIndex.rebuild(d.connections.values());
        TrainSystemUtilities.LOGGER.info(
                "[WireNetworkSavedData] loaded {} connections ({} chunk buckets)",
                loaded, d.spatialIndex.bucketCount());
        return d;
    }

    public static final SavedData.Factory<WireNetworkSavedData> FACTORY =
            new SavedData.Factory<>(WireNetworkSavedData::new,
                    WireNetworkSavedData::load, null);

    /** 各 dimension ごとに独立した接続セットを保持する。 */
    public static WireNetworkSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
