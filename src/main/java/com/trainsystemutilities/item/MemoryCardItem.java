package com.trainsystemutilities.item;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.ManagementComputerSettings;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.network.TrackNetworkScanner;
import com.trainsystemutilities.structure.block.PlatformFenceBlock;
import com.trainsystemutilities.structure.block.PlatformScreenDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
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
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * メモリーカード：管理コンピュータと鉄道管理ブロック/線路ネットワークをリンクするアイテム。
 * 鉄道管理ブロック右クリック → 位置保存（railway_manager）
 * 線路右クリック → ネットワーク位置保存（track_network）
 * ホーム柵 / ホームドア右クリック → メンバー追加（screen_door_group）
 * 管理コンピュータ右クリック → リンク完了
 * シフト右クリック（どこでも） → クリア
 * シフト右クリック（ホーム柵/ドア）→ そのメンバーを削除
 */
public class MemoryCardItem extends Item {

    public static final String TYPE_SCREEN_DOOR_GROUP = "screen_door_group";
    public static final String TAG_MEMBERS = "Members";

    public MemoryCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();
        boolean shift = player != null && player.isCrouching();

        // ホーム柵 / ホームドアへの操作: 隣接連結 group 一括登録 / 削除
        var clickedBlock = level.getBlockState(pos).getBlock();
        boolean isDoorMember = clickedBlock instanceof PlatformFenceBlock
                || clickedBlock instanceof PlatformScreenDoorBlock;

        if (level.isClientSide()) {
            // Shift + right-click (= non-door block): 初期化 → 確認ダイアログ (1.0.10)
            if (shift && !isDoorMember) {
                com.trainsystemutilities.client.gui.MemoryCardResetScreenOpener.open(context.getHand());
            }
            return InteractionResult.SUCCESS;
        }

        if (isDoorMember) {
            java.util.Set<BlockPos> connected = findConnectedGroup(level, pos);
            if (shift) {
                removeGroup(stack, connected, player);
            } else {
                addGroup(stack, connected, player);
            }
            return InteractionResult.SUCCESS;
        }

        // Shift + right-click (= non-door block): 実消去は確認ダイアログが送る
        // MemoryCardResetPayload が行う。 ここでは何もしない。
        if (shift) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);

        // 鉄道管理ブロック右クリック → 位置保存
        if (be instanceof RailwayManagementBlockEntity rmbe) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", "railway_manager");
            tag.putLong("Pos", pos.asLong());
            String stationName = rmbe.getLinkedStationName();
            tag.putString("StationName", stationName != null ? stationName : "");
            setCardData(stack, tag);
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "\u00a7b\u9244\u9053\u7ba1\u7406\u30d6\u30ed\u30c3\u30af\u3092\u767b\u9332\u3057\u307e\u3057\u305f"), true);
            }
            return InteractionResult.SUCCESS;
        }

        // 管理コンピュータ右クリック → リンク
        if (be instanceof ManagementComputerBlockEntity computer) {
            if (stack.has(DataComponents.CUSTOM_DATA)) {
                CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
                String type = tag.getString("Type");
                if ("railway_manager".equals(type)) {
                    BlockPos managerPos = BlockPos.of(tag.getLong("Pos"));
                    computer.setLinkedRailwayManagerPos(managerPos);
                    if (player != null) {
                        player.displayClientMessage(Component.literal(
                                "\u00a7a\u9244\u9053\u7ba1\u7406\u30d6\u30ed\u30c3\u30af\u3092\u30ea\u30f3\u30af\u3057\u307e\u3057\u305f"), true);
                    }
                    return InteractionResult.SUCCESS;
                }
                if ("track_network".equals(type)) {
                    BlockPos trackPos = BlockPos.of(tag.getLong("Pos"));
                    computer.setLinkedTrackNetworkPos(trackPos);
                    if (player != null) {
                        player.displayClientMessage(Component.literal(
                                "\u00a7a\u7dda\u8def\u30cd\u30c3\u30c8\u30ef\u30fc\u30af\u3092\u30ea\u30f3\u30af\u3057\u307e\u3057\u305f"), true);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "\u00a7c\u5148\u306b\u7dda\u8def\u307e\u305f\u306f\u9244\u9053\u7ba1\u7406\u30d6\u30ed\u30c3\u30af\u3092\u767b\u9332\u3057\u3066\u304f\u3060\u3055\u3044"), true);
            }
            return InteractionResult.FAIL;
        }

        // 線路ブロック右クリック → ネットワーク位置保存
        // Create modの線路かどうかを判定（TrackNetworkScannerでグラフ検索）
        try {
            var graph = TrackNetworkScanner.findGraphForPosition(pos);
            if (graph != null) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Type", "track_network");
                tag.putLong("Pos", pos.asLong());
                // ネットワーク上の駅数を取得
                var data = TrackNetworkScanner.scanFromPosition(level, pos);
                tag.putInt("Stations", data.stations().size());
                tag.putInt("Signals", data.signals().size());
                tag.putInt("Trains", data.trains().size());
                setCardData(stack, tag);
                if (player != null) {
                    player.displayClientMessage(Component.literal(
                            "\u00a7b\u7dda\u8def\u30cd\u30c3\u30c8\u30ef\u30fc\u30af\u3092\u767b\u9332 (\u99c5:" + data.stations().size()
                            + " \u4fe1\u53f7:" + data.signals().size() + " \u5217\u8eca:" + data.trains().size() + ")"), true);
                }
                return InteractionResult.SUCCESS;
            }
        } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MemoryCard] track network scan failed", ignored); }

        return InteractionResult.PASS;
    }

    // Shift + right-click in air: \u521d\u671f\u5316 (\u78ba\u8a8d\u30c0\u30a4\u30a2\u30ed\u30b0\u3092\u7d4c\u7531\u3059\u308b)
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isCrouching()) {
            if (level.isClientSide()) {
                com.trainsystemutilities.client.gui.MemoryCardResetScreenOpener.open(hand);
            }
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    /**
     * \u30ab\u30fc\u30c9\u306e\u8a18\u9332\u5185\u5bb9\u3092\u5168\u6d88\u53bb\u3059\u308b (server)\u3002
     *
     * <p>{@link com.trainsystemutilities.network.MemoryCardResetPayload} \u304b\u3089\u306e\u307f\u547c\u3070\u308c\u308b =
     * \u5fc5\u305a\u78ba\u8a8d\u30c0\u30a4\u30a2\u30ed\u30b0\u3092\u901a\u308b\u3002 \u7ba1\u7406\u7528\u30b3\u30f3\u30d4\u30e5\u30fc\u30bf\u30fc\u306e\u8a2d\u5b9a\u4e00\u5f0f\u3082\u3053\u3053\u3067\u6d88\u3048\u308b\u305f\u3081\u3001
     * \u3053\u308c\u304c\u300c\u60c5\u5831\u304c\u6d88\u3048\u308b\u30bf\u30a4\u30df\u30f3\u30b0\u300d\u306e 1 \u3064 (\u3082\u3046 1 \u3064\u306f\u30ab\u30fc\u30c9\u81ea\u4f53\u306e\u6d88\u6ec5)\u3002
     */
    public static void resetCard(ItemStack stack, Player player) {
        stack.remove(DataComponents.CUSTOM_DATA);
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable("tsu.memory_card.reset_done"), true);
        }
    }

    /**
     * \u30ea\u30f3\u30af\u767b\u9332\u306e tag \u3092\u66f8\u304d\u63db\u3048\u308b\u3068\u304d\u306f\u3001 \u7ba1\u7406\u7528\u30b3\u30f3\u30d4\u30e5\u30fc\u30bf\u30fc\u306e\u8a2d\u5b9a sub-tag \u3092\u6301\u3061\u8d8a\u3059\u3002
     *
     * <p>\u767b\u9332\u7cfb\u306f CUSTOM_DATA \u3092\u65b0\u3057\u3044 tag \u3067\u4e38\u3054\u3068\u7f6e\u304d\u63db\u3048\u308b\u306e\u3067\u3001 \u7d20\u76f4\u306b\u66f8\u304f\u3068
     * {@code TsuMcSettings} \u304c\u78ba\u8a8d\u30c0\u30a4\u30a2\u30ed\u30b0\u7121\u3057\u3067\u6d88\u3048\u308b\u3002 \u552f\u4e00\u306e\u8a2d\u5b9a\u30b3\u30d4\u30fc\u304c\u305d\u306e\u30ab\u30fc\u30c9\u306b\u3042\u308b\u72b6\u614b\u3067
     * \u7dda\u8def\u3092\u767b\u9332\u3057\u76f4\u3057\u305f\u3060\u3051\u3067\u5fa9\u5143\u4e0d\u80fd\u306b\u306a\u308a\u3001 \u300c\u60c5\u5831\u304c\u6d88\u3048\u308b\u306e\u306f\u30ab\u30fc\u30c9\u6d88\u6ec5\u304b\u30ea\u30bb\u30c3\u30c8\u306e\u3068\u304d\u3060\u3051\u300d\u306b
     * \u53cd\u3059\u308b (\u72ec\u7acb\u30ec\u30d3\u30e5\u30fc 2026-08-29 \u306e\u6307\u6458)\u3002
     */
    private static void setCardData(ItemStack stack, CompoundTag next) {
        CompoundTag settings = ManagementComputerSettings.readSettings(stack);
        if (settings != null) next = ManagementComputerSettings.writeIntoCustomData(next, settings);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(next));
    }

    /** 4 \u65b9\u5411 + \u4e0a\u4e0b (= multi-block dummy \u304c\u7e26\u306b\u4e26\u3076\u5834\u5408\u3042\u308a) \u3067 BFS \u3057\u3066\u9023\u7d50\u3059\u308b \u30db\u30fc\u30e0\u67f5 / \u30c9\u30a2 block \u3092\u5168\u90e8\u53ce\u96c6\u3002 */
    private static java.util.Set<BlockPos> findConnectedGroup(Level level, BlockPos start) {
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        java.util.Deque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (visited.contains(n)) continue;
                var b = level.getBlockState(n).getBlock();
                if (b instanceof PlatformFenceBlock || b instanceof PlatformScreenDoorBlock) {
                    visited.add(n);
                    queue.add(n);
                }
            }
        }
        return visited;
    }

    /** group set \u3092 \u30ab\u30fc\u30c9\u306b\u8ffd\u52a0 (= \u65e2\u5b58\u3068 union)\u3002 */
    private void addGroup(ItemStack stack, java.util.Set<BlockPos> group, Player player) {
        CompoundTag tag = stack.has(DataComponents.CUSTOM_DATA)
                ? stack.get(DataComponents.CUSTOM_DATA).copyTag()
                : new CompoundTag();
        if (!TYPE_SCREEN_DOOR_GROUP.equals(tag.getString("Type"))) {
            tag = new CompoundTag();
            tag.putString("Type", TYPE_SCREEN_DOOR_GROUP);
            tag.put(TAG_MEMBERS, new ListTag());
        }
        ListTag members = tag.getList(TAG_MEMBERS, Tag.TAG_LONG);
        java.util.Set<Long> existing = new java.util.HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            existing.add(((LongTag) members.get(i)).getAsLong());
        }
        int added = 0;
        for (BlockPos p : group) {
            long key = p.asLong();
            if (existing.add(key)) {
                members.add(LongTag.valueOf(key));
                added++;
            }
        }
        tag.put(TAG_MEMBERS, members);
        setCardData(stack, tag);
        if (player != null) {
            player.displayClientMessage(Component.literal(
                    String.format("\u00a7b\u30db\u30fc\u30e0\u30c9\u30a2\u7fa4\u306b %d \u500b\u8ffd\u52a0 (\u8a08 %d \u500b)",
                            added, members.size())), true);
        }
    }

    /** group set \u3092 \u30ab\u30fc\u30c9\u304b\u3089\u524a\u9664\u3002 */
    private void removeGroup(ItemStack stack, java.util.Set<BlockPos> group, Player player) {
        if (!stack.has(DataComponents.CUSTOM_DATA)) return;
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        if (!TYPE_SCREEN_DOOR_GROUP.equals(tag.getString("Type"))) return;
        ListTag members = tag.getList(TAG_MEMBERS, Tag.TAG_LONG);
        java.util.Set<Long> remove = new java.util.HashSet<>();
        for (BlockPos p : group) remove.add(p.asLong());
        int removed = 0;
        for (int i = members.size() - 1; i >= 0; i--) {
            if (remove.contains(((LongTag) members.get(i)).getAsLong())) {
                members.remove(i);
                removed++;
            }
        }
        if (removed == 0) return;
        if (members.isEmpty()) {
            // \u7ba1\u7406\u8a2d\u5b9a\u306f\u6b8b\u3059 (\u30b0\u30eb\u30fc\u30d7\u3092\u7a7a\u306b\u3057\u305f\u3060\u3051\u3067\u8a2d\u5b9a\u307e\u3067\u6d88\u3055\u306a\u3044)\u3002
            CompoundTag settings = ManagementComputerSettings.readSettings(stack);
            if (settings != null) {
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(
                        ManagementComputerSettings.writeIntoCustomData(null, settings)));
            } else {
                stack.remove(DataComponents.CUSTOM_DATA);
            }
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "\u00a77\u30db\u30fc\u30e0\u30c9\u30a2\u7fa4\u3092\u30af\u30ea\u30a2"), true);
            }
        } else {
            tag.put(TAG_MEMBERS, members);
            setCardData(stack, tag);
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        String.format("\u00a77%d \u500b\u524a\u9664 (\u6b8b\u308a %d \u500b)",
                                removed, members.size())), true);
            }
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (stack.has(DataComponents.CUSTOM_DATA)) {
            CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            String type = tag.getString("Type");
            if ("railway_manager".equals(type)) {
                tooltip.add(Component.literal("\u00a7b\u9244\u9053\u7ba1\u7406\u30d6\u30ed\u30c3\u30af\u767b\u9332\u6e08"));
                String station = tag.getString("StationName");
                if (!station.isEmpty()) {
                    tooltip.add(Component.literal("\u00a77\u99c5: \u00a7f" + station));
                }
            } else if ("track_network".equals(type)) {
                tooltip.add(Component.literal("\u00a7b\u7dda\u8def\u30cd\u30c3\u30c8\u30ef\u30fc\u30af\u767b\u9332\u6e08"));
                tooltip.add(Component.literal("\u00a77\u99c5: \u00a7f" + tag.getInt("Stations")
                        + " \u00a77\u4fe1\u53f7: \u00a7f" + tag.getInt("Signals")
                        + " \u00a77\u5217\u8eca: \u00a7f" + tag.getInt("Trains")));
            } else if (TYPE_SCREEN_DOOR_GROUP.equals(type)) {
                int count = tag.getList(TAG_MEMBERS, Tag.TAG_LONG).size();
                tooltip.add(Component.literal("\u00a7b\u30db\u30fc\u30e0\u30c9\u30a2\u7fa4\u767b\u9332\u6e08"));
                tooltip.add(Component.literal("\u00a77\u30e1\u30f3\u30d0\u30fc: \u00a7f" + count + " \u500b"));
            }
            // \u7ba1\u7406\u7528\u30b3\u30f3\u30d4\u30e5\u30fc\u30bf\u30fc\u306e\u8a2d\u5b9a (1.0.10)\u3002 \u30ea\u30f3\u30af\u767b\u9332\u306e\u7a2e\u5225\u3068\u306f\u72ec\u7acb\u306b\u8f09\u308b\u3002
            if (com.trainsystemutilities.blockentity.ManagementComputerSettings.hasSettings(stack)) {
                tooltip.add(Component.translatable("tsu.memory_card.tooltip_settings")
                        .withStyle(net.minecraft.ChatFormatting.AQUA));
                tooltip.add(Component.translatable("tsu.memory_card.tooltip_settings_detail",
                        String.valueOf(com.trainsystemutilities.blockentity.ManagementComputerSettings.symbolCount(stack)),
                        String.valueOf(com.trainsystemutilities.blockentity.ManagementComputerSettings.assignmentCount(stack)))
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        } else {
            tooltip.add(Component.literal("\u00a78\u7dda\u8def\u307e\u305f\u306f\u7ba1\u7406\u30d6\u30ed\u30c3\u30af\u3092\u53f3\u30af\u30ea\u30c3\u30af\u3067\u767b\u9332"));
            tooltip.add(Component.literal("\u00a78\u30b7\u30d5\u30c8\u53f3\u30af\u30ea\u30c3\u30af\u3067\u521d\u671f\u5316"));
        }
    }
}
