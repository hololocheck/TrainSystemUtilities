package com.trainsystemutilities.announce;

import net.minecraft.nbt.CompoundTag;

/**
 * 1 つのアナウンスエントリのトリガー条件 (1 entry につき 1 つだけ)。
 *
 * <p>条件タイプ:
 * <ul>
 *   <li>{@link Type#NONE}: 何もしない (このエントリは再生されない)</li>
 *   <li>{@link Type#ON_DETECTION_PASS}: 検知カードを列車が踏んだ時</li>
 *   <li>{@link Type#ON_DETECTION_STOPPED}: 検知カード上で列車が停止した時</li>
 * </ul>
 *
 * <p>{@link #delaySeconds} は条件成立からの追加遅延 (整数秒、ホイールで調整)。
 */
public final class AnnouncementCondition {

    public enum Type {
        NONE,
        ON_DETECTION_PASS,
        ON_DETECTION_STOPPED,
    }

    public final Type type;
    public final int delaySeconds;

    public AnnouncementCondition(Type type, int delaySeconds) {
        this.type = type != null ? type : Type.NONE;
        // -3600..3600 (= ±1時間) で clamp
        this.delaySeconds = Math.max(-3600, Math.min(3600, delaySeconds));
    }

    public static AnnouncementCondition none() {
        return new AnnouncementCondition(Type.NONE, 0);
    }

    public AnnouncementCondition withType(Type t) {
        return new AnnouncementCondition(t, this.delaySeconds);
    }

    public AnnouncementCondition withDelay(int sec) {
        return new AnnouncementCondition(this.type, sec);
    }

    public int delayTicks() {
        return delaySeconds * 20;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type.name());
        if (delaySeconds != 0) tag.putInt("delay", delaySeconds);
        return tag;
    }

    public static AnnouncementCondition load(CompoundTag tag) {
        if (tag == null) return none();
        try {
            Type t = Type.valueOf(tag.getString("type"));
            int s = tag.getInt("delay");
            return new AnnouncementCondition(t, s);
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Announce] AnnouncementCondition NBT load failed", e);
            return none();
        }
    }
}
