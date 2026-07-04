package com.trainsystemutilities.blockentity;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * リンク済 Create StationBlockEntity の {@code doorControls} (DoorControlBehaviour) を
 * 反射で制御する。
 *
 * <p>B (god-class 本体縮小): {@code RailwayManagementBlockEntity} から Create 内部への
 * 脆い reflection + {@code getName().endsWith} 文字列判定をこのクラスに隔離。 DoorSide enum と
 * その mapping は RM 側に残し、本クラスは Create DoorControl enum 名 (NONE/ALL/NORTH/...) を
 * 引数で受け取る (= DoorSide 非依存)。
 */
public final class StationDoorControl {

    private StationDoorControl() {}

    /**
     * {@code stationPos} の Create 駅 BE の doorControls に、 与えた DoorControl enum 名を反射適用する。
     * server 側のみ。 失敗は全てログ化 (旧: RM 内で同様にログ済)。
     */
    public static void applyDoorControl(Level level, BlockPos stationPos, String createDoorControlEnumName) {
        if (level == null || level.isClientSide() || stationPos == null) return;
        var stationBE = level.getBlockEntity(stationPos);
        if (stationBE == null) {
            TrainSystemUtilities.LOGGER.warn("[DoorControl] {} has no BlockEntity", stationPos);
            return;
        }
        if (!stationBE.getClass().getName().endsWith(".StationBlockEntity")) {
            TrainSystemUtilities.LOGGER.warn(
                    "[DoorControl] Expected StationBlockEntity but got {}", stationBE.getClass().getName());
            return;
        }
        try {
            // 1) doorControls フィールドを反射取得 (継承親も含めて検索)
            java.lang.reflect.Field f = findField(stationBE.getClass(), "doorControls");
            if (f == null) {
                TrainSystemUtilities.LOGGER.warn(
                        "[DoorControl] doorControls field not found on {}", stationBE.getClass().getName());
                return;
            }
            f.setAccessible(true);
            Object dcb = f.get(stationBE);
            if (dcb == null) {
                TrainSystemUtilities.LOGGER.warn("[DoorControl] doorControls field is null");
                return;
            }
            TrainSystemUtilities.LOGGER.info(
                    "[DoorControl] applying value={} via doorControls type={}",
                    createDoorControlEnumName, dcb.getClass().getName());

            // 2) DoorControl enum の対応値を取得
            Class<?> doorControlClass = Class.forName(
                    "com.simibubi.create.content.decoration.slidingDoor.DoorControl");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object doorControlValue = Enum.valueOf((Class<Enum>) doorControlClass, createDoorControlEnumName);

            // 3) DoorControlBehaviour.set(DoorControl) を呼び出し
            int count = 0;
            if (dcb instanceof java.util.Map<?, ?> map) {
                for (Object v : map.values()) { invokeSet(v, doorControlClass, doorControlValue); count++; }
            } else if (dcb instanceof Iterable<?> iter) {
                for (Object v : iter) { invokeSet(v, doorControlClass, doorControlValue); count++; }
            } else {
                invokeSet(dcb, doorControlClass, doorControlValue);
                count = 1;
            }
            // 駅 BE 自身を notifyUpdate / setChanged
            stationBE.setChanged();
            level.sendBlockUpdated(stationPos, stationBE.getBlockState(), stationBE.getBlockState(), 3);
            TrainSystemUtilities.LOGGER.info(
                    "[DoorControl] applied to {} behaviour(s), value={}", count, createDoorControlEnumName);
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.error("[DoorControl] reflection failed", t);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static void invokeSet(Object behaviour, Class<?> doorControlClass, Object value) {
        if (behaviour == null) return;
        try {
            var m = behaviour.getClass().getMethod("set", doorControlClass);
            m.invoke(behaviour, value);
        } catch (Throwable ignored) {
            // setter 名が違う場合に備えて mode フィールド直接代入も試行
            try {
                var fld = behaviour.getClass().getDeclaredField("mode");
                fld.setAccessible(true);
                fld.set(behaviour, value);
            } catch (Throwable ignored2) {}
        }
    }
}
