package com.trainsystemutilities.screendoor;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.item.MemoryCardItem;
import com.trainsystemutilities.structure.blockentity.BandColorable;
import com.trainsystemutilities.structure.blockentity.PlatformScreenDoorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Phase 21: ホームドア発火条件の evaluator。
 *
 * <p>列車検出 (= STOP / DEPART) を受けた {@link RailwayManagementBlockEntity} から呼ばれ、
 * 該当条件にマッチする entry のアクション (= OPEN / CLOSE / COLOR) を group メンバーに適用する。
 *
 * <p>MVP では番線判定はスキップ (= 全 entry を eventType でフィルタするのみ)。
 */
public final class ScreenDoorConditionEvaluator {

    private ScreenDoorConditionEvaluator() {}

    /** rmbe で eventType (= STOP/DEPART) が発生したとき、 一致する条件を発火。 */
    public static void fire(RailwayManagementBlockEntity rmbe, int eventType) {
        if (rmbe.getLevel() == null || rmbe.getLevel().isClientSide()) return;
        var conditions = rmbe.getScreenDoorConditions();
        if (conditions.isEmpty()) return;
        ItemStack card = rmbe.getScreenDoorCard();
        if (card.isEmpty() || !card.has(DataComponents.CUSTOM_DATA)) return;
        CompoundTag tag = card.get(DataComponents.CUSTOM_DATA).copyTag();
        if (!MemoryCardItem.TYPE_SCREEN_DOOR_GROUP.equals(tag.getString("Type"))) return;
        long[] members = readMembers(tag);
        if (members.length == 0) return;

        int bandColor = rmbe.getScreenDoorBandColorARGB();
        Level level = rmbe.getLevel();
        for (ScreenDoorCondition cond : conditions) {
            if (cond.eventType() != eventType) continue;
            applyAction(level, members, cond.actionType(), bandColor);
        }
    }

    /** rmbe からテスト発火 (= 列車イベント抜きで開閉/色変更を即時適用)。 */
    public static void testApply(RailwayManagementBlockEntity rmbe, int actionType) {
        if (rmbe.getLevel() == null || rmbe.getLevel().isClientSide()) return;
        ItemStack card = rmbe.getScreenDoorCard();
        if (card.isEmpty() || !card.has(DataComponents.CUSTOM_DATA)) return;
        CompoundTag tag = card.get(DataComponents.CUSTOM_DATA).copyTag();
        if (!MemoryCardItem.TYPE_SCREEN_DOOR_GROUP.equals(tag.getString("Type"))) return;
        long[] members = readMembers(tag);
        if (members.length == 0) return;
        applyAction(rmbe.getLevel(), members, actionType, rmbe.getScreenDoorBandColorARGB());
    }

    private static void applyAction(Level level, long[] members, int actionType, int bandColor) {
        for (long packed : members) {
            BlockPos p = BlockPos.of(packed);
            BlockEntity be = level.getBlockEntity(p);
            if (be == null) continue;
            switch (actionType) {
                case ScreenDoorCondition.ACTION_OPEN -> {
                    if (be instanceof PlatformScreenDoorBlockEntity door && door.isMaster()) {
                        door.setOpen(true);
                    }
                }
                case ScreenDoorCondition.ACTION_CLOSE -> {
                    if (be instanceof PlatformScreenDoorBlockEntity door && door.isMaster()) {
                        door.setOpen(false);
                    }
                }
                case ScreenDoorCondition.ACTION_COLOR -> {
                    if (be instanceof BandColorable colorable) {
                        colorable.setBandColorARGB(bandColor);
                    }
                }
                default -> {}
            }
        }
    }

    /** NBT sync で ListTag<LongTag> → LongArrayTag に自動圧縮されるため両方対応。 */
    private static long[] readMembers(CompoundTag tag) {
        Tag raw = tag.get(MemoryCardItem.TAG_MEMBERS);
        if (raw instanceof LongArrayTag lat) return lat.getAsLongArray();
        if (raw instanceof ListTag lt) {
            long[] arr = new long[lt.size()];
            for (int i = 0; i < lt.size(); i++) {
                if (lt.get(i) instanceof LongTag longTag) arr[i] = longTag.getAsLong();
            }
            return arr;
        }
        return new long[0];
    }
}
