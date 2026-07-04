package com.trainsystemutilities.blockentity;

import com.trainsystemutilities.screendoor.ScreenDoorCondition;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link RailwayManagementBlockEntity} のホームドア (screen-door) 機能を保持するヘルパ。
 * god-class 分割 (v2) で BE から切り出した。 帯色 + 発火条件リスト + detection listener の
 * lifecycle を担う。 メモリーカード自体 (inventory slot) は BE が保持し、 本クラスは所有 BE を
 * 引数に受けて card / level を読み、 listener を register/unregister する。 条件発火は
 * {@link com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator} (be を読む) へ委ねる。
 * NBT key (ScreenDoorBandColor / ScreenDoorConditions) は抽出前と完全一致。
 */
public final class ScreenDoorController {

    private static final int MAX_CONDITIONS = 16;

    private int bandColorARGB = 0xFF66BB6A;
    private final List<ScreenDoorCondition> conditions = new ArrayList<>();
    private com.trainsystemutilities.detection.TrainDetectionManager.DetectionListener listener;
    private final List<net.minecraft.core.GlobalPos> registeredPoints = new ArrayList<>();

    public int getBandColorARGB() { return bandColorARGB; }
    public void setBandColorARGB(int argb) { this.bandColorARGB = argb; }

    public List<ScreenDoorCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }
    /** 追加できれば true (= 上限未満)。 BE 側で setChanged を起動する判断に使う。 */
    public boolean addCondition(ScreenDoorCondition c) {
        if (conditions.size() >= MAX_CONDITIONS) return false;
        conditions.add(c);
        return true;
    }
    /** 削除できれば true (= idx 有効)。 */
    public boolean removeCondition(int idx) {
        if (idx < 0 || idx >= conditions.size()) return false;
        conditions.remove(idx);
        return true;
    }
    /** 更新できれば true (= idx 有効)。 */
    public boolean updateCondition(int idx, ScreenDoorCondition c) {
        if (idx < 0 || idx >= conditions.size()) return false;
        conditions.set(idx, c);
        return true;
    }

    /** メモリーカード group の全 BlockPos を detection 点として register/unregister。 server-side のみ。
     *  card / level は所有 BE から読み、 条件発火 (STOP/DEPART) は Evaluator へ be を渡す。 */
    public void refreshListener(RailwayManagementBlockEntity be) {
        Level level = be.getLevel();
        if (level == null || level.isClientSide()) return;
        unregisterListeners();
        ItemStack card = be.getScreenDoorCard();
        if (card.isEmpty()) return;
        if (!card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return;
        CompoundTag tag = card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        if (!com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                .equals(tag.getString("Type"))) return;
        long[] members = readCardMembers(tag);
        if (members.length == 0) return;

        final net.minecraft.resources.ResourceKey<Level> dim = level.dimension();
        listener = new com.trainsystemutilities.detection.TrainDetectionManager.DetectionListener() {
            @Override
            public void onTrainStopped(com.simibubi.create.content.trains.entity.Train train,
                                       net.minecraft.core.GlobalPos gp) {
                com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator.fire(
                        be, ScreenDoorCondition.EVENT_STOP);
            }
            @Override
            public void onTrainDeparted(com.simibubi.create.content.trains.entity.Train train,
                                        net.minecraft.core.GlobalPos gp) {
                com.trainsystemutilities.screendoor.ScreenDoorConditionEvaluator.fire(
                        be, ScreenDoorCondition.EVENT_DEPART);
            }
        };
        for (long packed : members) {
            net.minecraft.core.GlobalPos gp = net.minecraft.core.GlobalPos.of(
                    dim, net.minecraft.core.BlockPos.of(packed));
            com.trainsystemutilities.detection.TrainDetectionManager.register(gp, listener);
            registeredPoints.add(gp);
        }
    }

    public void unregisterListeners() {
        if (listener == null) return;
        for (net.minecraft.core.GlobalPos gp : registeredPoints) {
            com.trainsystemutilities.detection.TrainDetectionManager.unregister(gp, listener);
        }
        registeredPoints.clear();
        listener = null;
    }

    private static long[] readCardMembers(CompoundTag tag) {
        net.minecraft.nbt.Tag raw = tag.get(
                com.trainsystemutilities.item.MemoryCardItem.TAG_MEMBERS);
        if (raw instanceof net.minecraft.nbt.LongArrayTag lat) return lat.getAsLongArray();
        if (raw instanceof net.minecraft.nbt.ListTag lt) {
            long[] arr = new long[lt.size()];
            for (int i = 0; i < lt.size(); i++) {
                if (lt.get(i) instanceof net.minecraft.nbt.LongTag longTag) arr[i] = longTag.getAsLong();
            }
            return arr;
        }
        return new long[0];
    }

    public void writeNbt(CompoundTag tag) {
        tag.putInt("ScreenDoorBandColor", bandColorARGB);
        if (!conditions.isEmpty()) {
            net.minecraft.nbt.ListTag condList = new net.minecraft.nbt.ListTag();
            for (var cond : conditions) condList.add(cond.save());
            tag.put("ScreenDoorConditions", condList);
        }
    }

    public void readNbt(CompoundTag tag) {
        bandColorARGB = tag.contains("ScreenDoorBandColor")
                ? tag.getInt("ScreenDoorBandColor") : 0xFF66BB6A;
        conditions.clear();
        if (tag.contains("ScreenDoorConditions", net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag condList = tag.getList(
                    "ScreenDoorConditions", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < condList.size(); i++) {
                conditions.add(ScreenDoorCondition.load(condList.getCompound(i)));
            }
        }
    }
}
