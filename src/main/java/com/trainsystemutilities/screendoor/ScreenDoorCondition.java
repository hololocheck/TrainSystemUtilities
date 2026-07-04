package com.trainsystemutilities.screendoor;

import net.minecraft.nbt.CompoundTag;

/**
 * Phase 21: ホームドア制御の発火条件 1 件。
 *
 * <p>条件は「特定番線に対し、 列車が STOP / DEPART した瞬間にドアを OPEN / CLOSE / 帯色変更」 する。
 * 実際の発火は Phase 3 で実装。 ここではデータ + NBT のみ。
 */
public record ScreenDoorCondition(int trackNumber, int eventType, int actionType) {

    public static final int EVENT_STOP = 0;
    public static final int EVENT_DEPART = 1;

    public static final int ACTION_OPEN = 0;
    public static final int ACTION_CLOSE = 1;
    public static final int ACTION_COLOR = 2;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("Track", trackNumber);
        t.putInt("Event", eventType);
        t.putInt("Action", actionType);
        return t;
    }

    public static ScreenDoorCondition load(CompoundTag t) {
        return new ScreenDoorCondition(
                t.getInt("Track"),
                t.getInt("Event"),
                t.getInt("Action"));
    }

    public ScreenDoorCondition withTrack(int v) { return new ScreenDoorCondition(v, eventType, actionType); }
    public ScreenDoorCondition withEvent(int v) { return new ScreenDoorCondition(trackNumber, v, actionType); }
    public ScreenDoorCondition withAction(int v) { return new ScreenDoorCondition(trackNumber, eventType, v); }

    public static ScreenDoorCondition defaultEntry() {
        return new ScreenDoorCondition(1, EVENT_STOP, ACTION_OPEN);
    }
}
