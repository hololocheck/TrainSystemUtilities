package com.trainsystemutilities.item;

import com.trainsystemutilities.block.MonitorBlock;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * モニター連携カード：モニターを右クリックで登録（複数可）。
 * 鉄道管理ブロックのスロットに入れるとモニターに情報を表示。
 * 連結モニターは1グループとしてカウント。
 */
public class MonitorLinkCardItem extends Item {

    public MonitorLinkCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();

        // Shift + right-click: clear
        if (player != null && player.isCrouching()) {
            clearCard(stack, player);
            return InteractionResult.SUCCESS;
        }

        // Right-click on monitor: toggle registration
        BlockState state = level.getBlockState(pos);
        if (MonitorBlock.isMonitorBlock(state)) {
            List<BlockPos> positions = getRegisteredPositions(stack);

            // Find all connected monitors from clicked position
            net.minecraft.core.Direction facing = state.getValue(
                    MonitorBlock.FACING);
            List<BlockPos> connected = findConnectedMonitors(level, pos, facing);

            // 既に登録済みのグループなら解除
            if (positions.contains(pos)) {
                positions.removeAll(connected);
                savePositions(stack, positions);
                int groupCount = positions.isEmpty() ? 0 : countMonitorGroups(level, positions);
                if (player != null) {
                    Component remaining = positions.isEmpty() ? Component.empty()
                            : Component.translatable("tsu.monitor_card.unregister_remaining", groupCount);
                    player.displayClientMessage(Component.translatable(
                            "tsu.monitor_card.unregister", connected.size(), remaining), true);
                }
                return InteractionResult.SUCCESS;
            }

            // 未登録なら追加
            int added = 0;
            for (BlockPos cp : connected) {
                if (!positions.contains(cp)) {
                    positions.add(cp);
                    added++;
                }
            }
            savePositions(stack, positions);

            int groupCount = countMonitorGroups(level, positions);
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "tsu.monitor_card.register", connected.size(), groupCount), true);
            }
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player.isCrouching()) {
            clearCard(stack, player);
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    private void clearCard(ItemStack stack, Player player) {
        stack.remove(DataComponents.CUSTOM_DATA);
        player.displayClientMessage(Component.translatable("tsu.monitor_card.cleared"), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        List<BlockPos> positions = getRegisteredPositions(stack);
        if (!positions.isEmpty()) {
            tooltip.add(Component.translatable("tsu.monitor_card.tooltip_registered", positions.size()));
        } else {
            tooltip.add(Component.translatable("tsu.monitor_card.tooltip_hint_register"));
            tooltip.add(Component.translatable("tsu.monitor_card.tooltip_hint_clear"));
        }
    }

    // --- Data access ---

    public static List<BlockPos> getRegisteredPositions(ItemStack stack) {
        List<BlockPos> positions = new ArrayList<>();
        if (!stack.has(DataComponents.CUSTOM_DATA)) return positions;
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        ListTag list = tag.getList("Monitors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            positions.add(BlockPos.of(list.getCompound(i).getLong("P")));
        }
        return positions;
    }

    private void savePositions(ItemStack stack, List<BlockPos> positions) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (BlockPos p : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("P", p.asLong());
            list.add(entry);
        }
        tag.put("Monitors", list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** Find all connected monitors from a starting position (BFS) */
    private static List<BlockPos> findConnectedMonitors(Level level, BlockPos start, net.minecraft.core.Direction facing) {
        List<BlockPos> connected = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> queue = new ArrayList<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.remove(0);
            if (visited.contains(current)) continue;
            BlockState state = level.getBlockState(current);
            if (!MonitorBlock.isMonitorBlock(state)) continue;
            if (state.getValue(MonitorBlock.FACING) != facing) continue;

            visited.add(current);
            connected.add(current);

            queue.add(current.above());
            queue.add(current.below());
            queue.add(current.relative(facing.getClockWise()));
            queue.add(current.relative(facing.getCounterClockWise()));
        }
        return connected;
    }

    /**
     * Count monitor groups (connected monitors = 1 group).
     * Uses flood-fill to group adjacent monitors.
     */
    public static int countMonitorGroups(Level level, List<BlockPos> positions) {
        Set<BlockPos> visited = new HashSet<>();
        int groups = 0;

        for (BlockPos pos : positions) {
            if (visited.contains(pos)) continue;

            // Check if block still exists as monitor
            BlockState state = level.getBlockState(pos);
            if (!MonitorBlock.isMonitorBlock(state)) {
                visited.add(pos);
                continue;
            }

            // Flood fill from this position
            groups++;
            floodFill(level, pos, positions, visited);
        }

        return groups;
    }

    private static void floodFill(Level level, BlockPos start, List<BlockPos> allPositions, Set<BlockPos> visited) {
        List<BlockPos> queue = new ArrayList<>();
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.remove(0);
            if (visited.contains(current)) continue;
            visited.add(current);

            // Check adjacent positions (up, down, and horizontal neighbors)
            BlockPos[] neighbors = {
                    current.above(), current.below(),
                    current.north(), current.south(),
                    current.east(), current.west()
            };

            for (BlockPos neighbor : neighbors) {
                if (!visited.contains(neighbor) && allPositions.contains(neighbor)) {
                    BlockState state = level.getBlockState(neighbor);
                    if (MonitorBlock.isMonitorBlock(state)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
    }
}
