package com.trainsystemutilities.announce;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Railway Management Block ごとのアナウンス設定。
 *
 * <p>{@link #enabled} が false の時は、検知トリガーで再生されない (master toggle, default OFF)。
 */
public final class AnnouncementConfig {

    public static final int MAX_ENTRIES = 16;

    private boolean enabled = false;
    /** 減衰モード (default ON)。SAS の playAudio に渡される (true=directional attenuation, false=spherical fade)。
     *  範囲枠表示と組み合わせて減衰範囲を可視化するためにも使う。 */
    private boolean attenuationMode = true;
    private final List<AnnouncementEntry> entries = new ArrayList<>();
    /** 検知カード共有先の駅名リスト。これらの駅の rmbe は source の検知イベントを受け取る。 */
    private final java.util.LinkedHashSet<String> sharedDetectionToStations = new java.util.LinkedHashSet<>();
    /** 範囲指定ボード共有先の駅名リスト。これらの駅の rmbe は source の range board を音声再生に使用する。 */
    private final java.util.LinkedHashSet<String> sharedRangeToStations = new java.util.LinkedHashSet<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { this.enabled = e; }

    public boolean isAttenuationMode() { return attenuationMode; }
    public void setAttenuationMode(boolean a) { this.attenuationMode = a; }

    // 検知カード共有
    public java.util.Set<String> sharedDetectionToStations() { return sharedDetectionToStations; }
    public boolean isDetectionSharedTo(String stationName) {
        return stationName != null && sharedDetectionToStations.contains(stationName);
    }
    public void toggleDetectionShared(String stationName) {
        if (stationName == null || stationName.isEmpty()) return;
        if (!sharedDetectionToStations.remove(stationName)) sharedDetectionToStations.add(stationName);
    }

    // 範囲指定ボード共有
    public java.util.Set<String> sharedRangeToStations() { return sharedRangeToStations; }
    public boolean isRangeSharedTo(String stationName) {
        return stationName != null && sharedRangeToStations.contains(stationName);
    }
    public void toggleRangeShared(String stationName) {
        if (stationName == null || stationName.isEmpty()) return;
        if (!sharedRangeToStations.remove(stationName)) sharedRangeToStations.add(stationName);
    }

    public List<AnnouncementEntry> entries() { return entries; }

    public AnnouncementEntry get(int index) {
        return (index >= 0 && index < entries.size()) ? entries.get(index) : null;
    }

    public boolean addEntry() {
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.add(new AnnouncementEntry());
        return true;
    }

    public boolean removeEntry(int index) {
        if (index < 0 || index >= entries.size()) return false;
        entries.remove(index);
        return true;
    }

    /** {@code from} 番目を {@code to} 番目に移動 (前後シフト)。 */
    public boolean moveEntry(int from, int to) {
        if (from < 0 || from >= entries.size()) return false;
        if (to < 0 || to >= entries.size()) return false;
        if (from == to) return false;
        AnnouncementEntry e = entries.remove(from);
        entries.add(to, e);
        return true;
    }

    public int size() { return entries.size(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enabled", enabled);
        tag.putBoolean("attenuationMode", attenuationMode);
        ListTag list = new ListTag();
        for (AnnouncementEntry e : entries) list.add(e.save());
        tag.put("entries", list);
        ListTag detList = new ListTag();
        for (String s : sharedDetectionToStations) detList.add(net.minecraft.nbt.StringTag.valueOf(s));
        tag.put("sharedDetectionTo", detList);
        ListTag rngList = new ListTag();
        for (String s : sharedRangeToStations) rngList.add(net.minecraft.nbt.StringTag.valueOf(s));
        tag.put("sharedRangeTo", rngList);
        return tag;
    }

    public static AnnouncementConfig load(CompoundTag tag) {
        AnnouncementConfig c = new AnnouncementConfig();
        if (tag == null) return c;
        c.enabled = tag.getBoolean("enabled");
        // 後方互換: 旧データには attenuationMode キーがないので、未設定時は true (default ON) に戻す。
        c.attenuationMode = !tag.contains("attenuationMode") || tag.getBoolean("attenuationMode");
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && c.entries.size() < MAX_ENTRIES; i++) {
            c.entries.add(AnnouncementEntry.load(list.getCompound(i)));
        }
        // 後方互換: 旧 "sharedTo" は detection のみだったので detection 側に読み込む
        ListTag legacy = tag.getList("sharedTo", Tag.TAG_STRING);
        for (int i = 0; i < legacy.size(); i++) {
            String s = legacy.getString(i);
            if (s != null && !s.isEmpty()) c.sharedDetectionToStations.add(s);
        }
        ListTag detList = tag.getList("sharedDetectionTo", Tag.TAG_STRING);
        for (int i = 0; i < detList.size(); i++) {
            String s = detList.getString(i);
            if (s != null && !s.isEmpty()) c.sharedDetectionToStations.add(s);
        }
        ListTag rngList = tag.getList("sharedRangeTo", Tag.TAG_STRING);
        for (int i = 0; i < rngList.size(); i++) {
            String s = rngList.getString(i);
            if (s != null && !s.isEmpty()) c.sharedRangeToStations.add(s);
        }
        return c;
    }
}
