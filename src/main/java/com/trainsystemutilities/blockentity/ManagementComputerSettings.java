package com.trainsystemutilities.blockentity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;
import java.util.Set;

/**
 * 管理用コンピューターの「設定」をメモリーカードへ載せるための語彙。
 *
 * <p>1.0.10 でカードが設定の権威になった。 管理用コンピューターを壊すと block entity の NBT は
 * まるごと消えるため、 路線記号もモニターレイアウトも色設定も一緒に失われていた。 カードに載せて
 * おけば、 壊して別の場所に置き直しても差し込むだけで復元できる。
 *
 * <p><b>キーの分類がこのクラスの本体。</b> {@code ManagementComputerBlockEntity.saveAdditional} が
 * 書く NBT キーは全て {@link #SETTING_KEYS} か {@link #RUNTIME_KEYS} のどちらかに属していなければ
 * ならない。 新しいキーを save に足して分類し忘れると
 * {@code ManagementComputerSettingsTest#everySavedKeyIsClassified} が赤くなる —
 * 「カードに載らない設定」が黙って増えるのを防ぐため。
 */
public final class ManagementComputerSettings {

    private ManagementComputerSettings() {}

    /** メモリーカードの {@code CUSTOM_DATA} 内で管理設定を格納する sub-tag 名。
     *  既存の {@code Type} (railway_manager / track_network / screen_door_group) とは独立で、
     *  1 枚のカードがリンク登録と設定保存を兼ねられる。 */
    public static final String CARD_TAG = "TsuMcSettings";

    /** カードへ載せる = 管理用コンピューターで設定できる項目。 */
    public static final List<String> SETTING_KEYS = List.of(
            // リンク先 (座標なので置き直しても有効)
            "MonitorX", "MonitorY", "MonitorZ",
            "ManagerX", "ManagerY", "ManagerZ",
            "TrackNetPos",
            // モニター (MonW / MonH は自動検出値なので載せない → RUNTIME_KEYS)
            "MonitorLayout", "MonEnabled",
            // 路線記号
            "LineSymbols", "StationSymbolMap",
            // 時刻表まわり
            "TimetableShares",
            // 書き出し
            "ExportAll",
            // アクセス制御 (所有者そのものは載せない。 下の RUNTIME_KEYS を参照)
            "PrivateMode"
    );

    /** {@link #applySettings 適用時}に、 カードが値を持っていなければ既定へ戻す必要があるキー。
     *
     * <p>{@code loadAdditional} は「tag に有るものだけ設定する」ので、 カードが持たないキーは
     * コンピューター側の値が残ってしまい、 <b>カード優先にならない</b>。 条件付きでしか
     * 書かれないキーがこれに当たる (色は空文字、 リンク先は削除で既定に戻る)。 */
    public static final List<String> CLEARED_WHEN_ABSENT = List.of(
            "MonitorX", "MonitorY", "MonitorZ",
            "ManagerX", "ManagerY", "ManagerZ",
            "TrackNetPos"
    );

    /** 前方一致で設定と判定するキー接頭辞。 色設定は {@code Color_<name>} で 12 件ある。 */
    public static final List<String> SETTING_KEY_PREFIXES = List.of("Color_");

    /** カードへ載せない = 実行時 cache / スロットの中身 / 所有権。
     *
     * <p>所有者 ({@code OwnerUUID} / {@code OwnerName}) を載せないのは意図的で、 所有権はブロックに
     * 従う。 載せると「他人のカードを差した瞬間に自分のブロックが自分のものでなくなる」。
     * スロットの中身 ({@code MemoryCard} 自身を含む) を載せないのはアイテム複製を作らないため。
     *
     * <p>「設定に見えるが載せない」ものが 5 つある。 いずれも<b>権威が別にある</b>ため、
     * カードから復元すると本来の権威と食い違う (独立レビュー 2026-08-29 の指摘):
     * <ul>
     *   <li>{@code MonW} / {@code MonH} — 接続モニターからの自動検出値。 古い寸法を復元すると
     *       {@code detectMonitorSize} の再検出条件 ({@code monitorW == 0}) を通らず、
     *       別のモニターに繋いでも旧寸法のままになる。</li>
     *   <li>{@code TrainTypes} — 権威は {@code TrainTypeState} (server-global)。
     *       {@code updateNetworkCache} が毎スキャンで global 値を書き戻す。</li>
     *   <li>{@code ElectronicTimetables} — 権威は {@code ElectronicTimetableState} (server-global)。
     *       local 集合から外しても global には {@code add} しかないので消えない。</li>
     *   <li>{@code TrainSchedViews} — 毎スキャン再構築される表示 cache。</li>
     * </ul>
     * これらはコンピューターを壊しても失われない (world / global 側に残る) ので、
     * カードに載せなくても「壊しても消えない」は満たす。 */
    public static final List<String> RUNTIME_KEYS = List.of(
            "MemoryCard", "MonitorLinkCard",
            "ExportIn", "ExportOut", "ExportTrain", "ExportProgress",
            "OwnerUUID", "OwnerName",
            "PendingStop", "PendingResume",
            "CachedSignals", "CachedStations", "CachedTrains",
            "TrainTimetableFlags", "StationManagerPosMap",
            "PublishedSymbolKeys", "CardSlotChanged",
            "MonW", "MonH", "TrainTypes", "ElectronicTimetables", "TrainSchedViews",
            "MapNodes", "MapEdges", "MapStations", "MapTrains", "MapSignals"
    );

    private static final Set<String> SETTING_SET = Set.copyOf(SETTING_KEYS);
    private static final Set<String> RUNTIME_SET = Set.copyOf(RUNTIME_KEYS);

    /** そのキーはカードへ載せる設定か。 */
    public static boolean isSetting(String key) {
        if (SETTING_SET.contains(key)) return true;
        for (String p : SETTING_KEY_PREFIXES) {
            if (key.startsWith(p)) return true;
        }
        return false;
    }

    /** そのキーは設定 / 実行時 のどちらかに分類済みか。 未分類 = 検査で赤。 */
    public static boolean isClassified(String key) {
        return isSetting(key) || RUNTIME_SET.contains(key);
    }

    /** save 済み BE NBT から設定キーだけを抜き出した新しい tag を作る。 */
    public static CompoundTag extract(CompoundTag full) {
        CompoundTag out = new CompoundTag();
        for (String key : full.getAllKeys()) {
            if (isSetting(key)) out.put(key, full.get(key).copy());
        }
        return out;
    }

    /**
     * 既定値が「ゼロ / 空」ではない設定キー。
     *
     * <p>{@link #isEmpty} は「ゼロ・空文字・空リストなら未設定」で判定するが、
     * {@code saveAdditional} は {@code MonEnabled} を<b>常に</b>書き、 その既定は {@code true}。
     * つまり<b>何も設定していないコンピューターの設定 tag も必ず {@code MonEnabled=1b} を含む</b>ので、
     * 素の判定では {@code isEmpty} が永久に false になり、 「空カードを汚さない」門番が
     * 一度も働かなかった (独立レビュー 2026-08-29 の指摘)。 既定値と一致する値は未設定として扱う。
     */
    private static final java.util.Map<String, Boolean> BOOLEAN_DEFAULTS =
            java.util.Map.of("MonEnabled", Boolean.TRUE);

    /** 既定値が非ゼロの設定キー (検査用に公開)。 */
    public static java.util.Set<String> nonZeroDefaultKeys() { return BOOLEAN_DEFAULTS.keySet(); }

    /** 「保存する価値が無い」設定か。 空のカードを設定済みコンピューターに差したときに
     *  中身を消してしまわないよう、 空の設定はカードへ書かない。 */
    public static boolean isEmpty(CompoundTag settings) {
        if (settings == null || settings.isEmpty()) return true;
        for (String key : settings.getAllKeys()) {
            Tag t = settings.get(key);
            if (t == null) continue;
            Boolean def = BOOLEAN_DEFAULTS.get(key);
            if (def != null) {
                // 既定と同じなら未設定扱い。 違えばそれは利用者が変えた設定。
                if (t.getId() == Tag.TAG_BYTE
                        && (((net.minecraft.nbt.ByteTag) t).getAsByte() != 0) == def) continue;
                return false;
            }
            switch (t.getId()) {
                case Tag.TAG_LIST -> { if (!((net.minecraft.nbt.ListTag) t).isEmpty()) return false; }
                case Tag.TAG_COMPOUND -> { if (!((CompoundTag) t).isEmpty()) return false; }
                case Tag.TAG_STRING -> { if (!t.getAsString().isEmpty()) return false; }
                case Tag.TAG_BYTE -> { if (((net.minecraft.nbt.ByteTag) t).getAsByte() != 0) return false; }
                case Tag.TAG_INT -> { if (((net.minecraft.nbt.IntTag) t).getAsInt() != 0) return false; }
                case Tag.TAG_LONG -> { if (((net.minecraft.nbt.LongTag) t).getAsLong() != 0L) return false; }
                default -> { return false; }
            }
        }
        return true;
    }

    // --- メモリーカード (ItemStack) 側の出し入れ ---

    // CUSTOM_DATA の CompoundTag だけを相手にする層。 ItemStack を作るには registry の
    // bootstrap が要るので、 検査はこちらに書ける (ItemStack 版は薄い wrapper)。

    /** CUSTOM_DATA tag から管理設定を読む。 無ければ null。 */
    public static CompoundTag readFromCustomData(CompoundTag customData) {
        if (customData == null) return null;
        if (!customData.contains(CARD_TAG, Tag.TAG_COMPOUND)) return null;
        return customData.getCompound(CARD_TAG);
    }

    /** CUSTOM_DATA tag へ管理設定を書いた新しい tag を返す。 既存の {@code Type} 等には触らない。 */
    public static CompoundTag writeIntoCustomData(CompoundTag customData, CompoundTag settings) {
        CompoundTag tag = customData != null ? customData.copy() : new CompoundTag();
        tag.put(CARD_TAG, settings.copy());
        return tag;
    }

    /** CUSTOM_DATA tag から管理設定だけを落とした新しい tag を返す。 空になったら null。 */
    public static CompoundTag clearFromCustomData(CompoundTag customData) {
        if (customData == null) return null;
        CompoundTag tag = customData.copy();
        tag.remove(CARD_TAG);
        return tag.isEmpty() ? null : tag;
    }

    /** カードに管理設定が載っているか。 */
    public static boolean hasSettings(ItemStack stack) {
        return readSettings(stack) != null;
    }

    /** カード上の管理設定を読む。 無ければ null。 返り値は copy なので自由に触ってよい。 */
    public static CompoundTag readSettings(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : readFromCustomData(data.copyTag());
    }

    /** カードへ管理設定を書く。 既存の {@code Type} 等の項目には触らない。 */
    public static void writeSettings(ItemStack stack, CompoundTag settings) {
        if (stack == null || stack.isEmpty() || settings == null) return;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = writeIntoCustomData(data != null ? data.copyTag() : null, settings);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** カード上の管理設定だけを消す (リンク登録は残す)。 */
    public static void clearSettings(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return;
        CompoundTag before = data.copyTag();
        if (!before.contains(CARD_TAG)) return;
        CompoundTag after = clearFromCustomData(before);
        if (after == null) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(after));
    }

    /** ツールチップ用: カードに載っている路線記号の数。 設定が無ければ 0。 */
    public static int symbolCount(ItemStack stack) {
        CompoundTag s = readSettings(stack);
        return s == null ? 0 : s.getList("LineSymbols", Tag.TAG_COMPOUND).size();
    }

    /** ツールチップ用: カードに載っている駅割り当ての数。 設定が無ければ 0。 */
    public static int assignmentCount(ItemStack stack) {
        CompoundTag s = readSettings(stack);
        return s == null ? 0 : s.getCompound("StationSymbolMap").getAllKeys().size();
    }
}
