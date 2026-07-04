package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;

/**
 * P0-5 #2: FE storedEnergy persistence の server lifecycle 結線。
 *
 * <p>従来 {@link ContraptionElectrificationState#PERSISTENT_ENERGY} は
 * {@link java.util.concurrent.ConcurrentHashMap} の in-memory state で、 server
 * 再起動でリセットされていた (WF-B Critical "PERSISTENT_ENERGY 完全消失")。
 *
 * <p>本 handler の責務:
 * <ul>
 *   <li>{@link ServerStartedEvent}: {@link TsuPersistentEnergyData} を load して
 *     in-memory map に hydrate。 旧 world (= SavedData 不在) は no-op。</li>
 *   <li>{@link ServerStoppingEvent}: in-memory map snapshot を SavedData に書き戻す。
 *     server save flow が tag を disk へ flush する。</li>
 *   <li>{@link ServerTickEvent.Post} (= 6000 tick 毎 = 5 分): 同 snapshot 保存。
 *     crash 等の異常停止対策。</li>
 * </ul>
 *
 * <p>hot path は変更しない (= {@code Info.receive}/{@code drain} は in-memory に書き続ける)。
 * 永続性のみが追加される。 NBT schema v0 = SavedData 不在 (旧 in-memory only) →
 * v1 = ListTag with schemaVersion=1 への移行は initial load 時に no-op として透過。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class PersistentEnergyServerLifecycle {

    /** 定期 save の間隔 (server tick 単位)。 6000 tick = 5 分。 */
    private static final int SAVE_INTERVAL_TICKS = 6000;
    private static int tickCounter = 0;

    private PersistentEnergyServerLifecycle() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            TsuPersistentEnergyData data = TsuPersistentEnergyData.get(server);
            Map<Long, Integer> entries = data.snapshot();
            ContraptionElectrificationState.hydrateFromSavedData(entries);
            TrainSystemUtilities.LOGGER.info(
                    "[PersistentEnergy] hydrated {} FE entries from SavedData on server start",
                    entries.size());
        } catch (Throwable t) {
            // failure to hydrate は致命傷ではない (= in-memory なら旧挙動と等価)
            TrainSystemUtilities.LOGGER.warn(
                    "[PersistentEnergy] hydrate failed (server will run with empty cache): {}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        saveSnapshotToSavedData(event.getServer(), "shutdown");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < SAVE_INTERVAL_TICKS) return;
        tickCounter = 0;
        saveSnapshotToSavedData(event.getServer(), "periodic-5min");
    }

    /** in-memory PERSISTENT_ENERGY snapshot を SavedData に書き戻し dirty マークする。 */
    private static void saveSnapshotToSavedData(MinecraftServer server, String reason) {
        if (server == null) return;
        try {
            TsuPersistentEnergyData data = TsuPersistentEnergyData.get(server);
            Map<Long, Integer> snapshot = ContraptionElectrificationState.persistentEnergySnapshot();
            // SavedData に diff を反映 (= 旧 entry の prune も含めて putAll で上書き)
            // SavedData snapshot() は unmodifiable、 内部 entries を直接更新する API を使う。
            int before = data.snapshot().size();
            int prunedOrUpdated = applyToSavedData(data, snapshot);
            TrainSystemUtilities.LOGGER.info(
                    "[PersistentEnergy] saved {} FE entries to SavedData ({} prior, reason={})",
                    snapshot.size(), before, reason);
            // setDirty は put / remove で発火するので明示呼出不要だが、 server side で
            // 即時 flush したい場合は dataStorage の save をトリガする方法も別途検討。
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn(
                    "[PersistentEnergy] save failed (reason={}): {}", reason, t.toString());
        }
    }

    /** SavedData の entries を新 snapshot で上書きする (= 追加 + 更新 + 削除)。 */
    private static int applyToSavedData(TsuPersistentEnergyData data, Map<Long, Integer> snapshot) {
        // 旧 key を集めて、 snapshot に無いものは remove。
        Map<Long, Integer> prev = data.snapshot();
        int changes = 0;
        for (Long oldKey : prev.keySet()) {
            if (!snapshot.containsKey(oldKey)) {
                data.remove(oldKey);
                changes++;
            }
        }
        // 新 / 更新 を put。
        for (Map.Entry<Long, Integer> e : snapshot.entrySet()) {
            Integer existing = data.get(e.getKey());
            if (existing == null || !existing.equals(e.getValue())) {
                data.put(e.getKey(), e.getValue());
                changes++;
            }
        }
        return changes;
    }
}
