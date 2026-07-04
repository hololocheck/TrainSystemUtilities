package com.trainsystemutilities.announce;

import net.minecraft.nbt.CompoundTag;

/**
 * 1 つのアナウンスエントリ: 単一の条件 + (BE スロットに格納された) 記憶媒体。
 *
 * <p>記憶媒体 (ItemStack) はこの POJO には含めない: BE 側のスロットに index で
 * 紐付ける。
 */
public final class AnnouncementEntry {

    /** 再生回数の上限 (UI でのホイール clamp と整合)。 */
    public static final int MAX_PLAY_COUNT = 10;

    private AnnouncementCondition condition = AnnouncementCondition.none();
    /** このエントリの音声を続けて再生する回数 (1..{@link #MAX_PLAY_COUNT})。次エントリは全回数完了後。 */
    private int playCount = 1;

    public AnnouncementCondition condition() {
        return condition;
    }

    public void setCondition(AnnouncementCondition c) {
        this.condition = c != null ? c : AnnouncementCondition.none();
    }

    public int playCount() { return playCount; }
    public void setPlayCount(int n) {
        this.playCount = Math.max(1, Math.min(MAX_PLAY_COUNT, n));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("condition", condition.save());
        if (playCount != 1) tag.putInt("playCount", playCount);
        return tag;
    }

    public static AnnouncementEntry load(CompoundTag tag) {
        AnnouncementEntry e = new AnnouncementEntry();
        if (tag == null) return e;
        if (tag.contains("condition", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            e.condition = AnnouncementCondition.load(tag.getCompound("condition"));
        }
        if (tag.contains("playCount")) e.setPlayCount(tag.getInt("playCount"));
        return e;
    }
}
