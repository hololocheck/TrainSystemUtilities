package com.trainsystemutilities.preset;

import com.trainsystemutilities.compat.ae2.TrainPresetAe2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TrainPresetSupply {

    private TrainPresetSupply() {
    }

    public static LinkedHashMap<Item, Integer> getChestMissing(Level level, BlockPos pos, Map<Item, Integer> requirements) {
        LinkedHashMap<Item, Integer> missing = new LinkedHashMap<>();
        Container container = resolveContainer(level, pos);
        if (container == null) {
            missing.putAll(requirements);
            return missing;
        }

        LinkedHashMap<Item, Integer> available = countAvailable(container);
        for (var entry : requirements.entrySet()) {
            int have = available.getOrDefault(entry.getKey(), 0);
            if (have < entry.getValue()) {
                missing.put(entry.getKey(), entry.getValue() - have);
            }
        }
        return missing;
    }

    public static boolean consumeFromChest(Level level, BlockPos pos, Map<Item, Integer> requirements) {
        Container container = resolveContainer(level, pos);
        if (container == null) {
            return false;
        }

        if (!getChestMissing(level, pos, requirements).isEmpty()) {
            return false;
        }

        for (var entry : requirements.entrySet()) {
            int remaining = entry.getValue();
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.getItem() != entry.getKey()) {
                    continue;
                }

                int take = Math.min(remaining, stack.getCount());
                container.removeItem(slot, take);
                remaining -= take;
            }
        }

        container.setChanged();
        return true;
    }

    public static LinkedHashMap<Item, Integer> getMeMissing(Level level, BlockPos pos, Map<Item, Integer> requirements) {
        return TrainPresetAe2Integration.getMissing(level, pos, requirements);
    }

    public static boolean consumeFromMe(Level level, BlockPos pos, Map<Item, Integer> requirements) {
        return TrainPresetAe2Integration.consume(level, pos, requirements);
    }

    /** AE2 WAP リンク経由の不足量。AE2 未導入なら全要件が不足扱い。 */
    public static LinkedHashMap<Item, Integer> getWapMissing(Player player, ItemStack tool, Map<Item, Integer> requirements) {
        return TrainPresetAe2Integration.getMissingFromWap(player, tool, requirements);
    }

    /** AE2 WAP リンク経由で消費。AE2 未導入またはリンク無効なら false。 */
    public static boolean consumeFromWap(Player player, ItemStack tool, Map<Item, Integer> requirements) {
        return TrainPresetAe2Integration.consumeFromWap(player, tool, requirements);
    }

    public static boolean canLinkChest(Level level, BlockPos pos) {
        return resolveContainer(level, pos) != null;
    }

    private static LinkedHashMap<Item, Integer> countAvailable(Container container) {
        LinkedHashMap<Item, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static Container resolveContainer(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }

        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, true);
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }
}
