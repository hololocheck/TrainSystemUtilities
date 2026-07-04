package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.network.TrainPresetListResponsePayload;
import com.trainsystemutilities.preset.TrainPreset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * クライアント側プリセットキャッシュ。
 * - 一覧 metadata は {@link #setList} で更新
 * - 個別プリセット完全データ (3D プレビュー用) は {@link #setPresetData} で蓄積、
 *   LRU で最大 32 件保持
 */
public final class TrainPresetClientCache {

    private static List<TrainPresetListResponsePayload.Item> list = Collections.emptyList();
    private static long lastUpdateMs = 0L;

    private static final int PRESET_DATA_LIMIT = 32;
    private static final Map<String, TrainPreset> presetData =
            Collections.synchronizedMap(new LinkedHashMap<String, TrainPreset>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TrainPreset> eldest) {
                    return size() > PRESET_DATA_LIMIT;
                }
            });

    private TrainPresetClientCache() {}

    /** P0-1 #7: ClientDisconnect で前 server の preset list/data が残らないよう clear。 */
    public static void clear() {
        list = Collections.emptyList();
        lastUpdateMs = 0L;
        synchronized (presetData) {
            presetData.clear();
        }
    }

    public static void setList(List<TrainPresetListResponsePayload.Item> items) {
        list = new ArrayList<>(items);
        lastUpdateMs = System.currentTimeMillis();
    }

    public static List<TrainPresetListResponsePayload.Item> getList() { return list; }

    public static long lastUpdateMs() { return lastUpdateMs; }

    /** preset 詳細データの保存 (Network 層から呼ばれる)。 */
    public static void setPresetData(String key, TrainPreset preset) {
        presetData.put(key, preset);
        lastUpdateMs = System.currentTimeMillis();
    }

    public static TrainPreset getPresetData(String key) { return presetData.get(key); }

    /** preset key → 不足アイテム encoded 文字列。サーバ tick / 明示要求の応答で更新。 */
    private static final Map<String, String> missingByPreset =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > PRESET_DATA_LIMIT;
                }
            });

    public static void setMissingForPreset(String key, String encoded) {
        missingByPreset.put(key, encoded == null ? "" : encoded);
        lastUpdateMs = System.currentTimeMillis();
    }

    /** 直近の不足アイテム encoded 文字列を返す。null = まだ問い合わせ中、"" = 不足なし。 */
    public static String getMissingForPreset(String key) {
        return missingByPreset.get(key);
    }
}
