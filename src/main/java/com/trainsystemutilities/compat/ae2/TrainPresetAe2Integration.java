package com.trainsystemutilities.compat.ae2;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.features.GridLinkables;
import appeng.api.features.IGridLinkableHandler;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TrainPresetAe2Integration {

    private TrainPresetAe2Integration() {
    }

    /** P0-12: idempotency guard (= 二度目以降の registerGridLinkable() は no-op)。
     *  AE2 の GridLinkables は内部 Map で二度 put すると上書きされるが、 log を二度出すのは
     *  冗長 + 万一 AE2 側の仕様変更で例外化する可能性があるため明示的に guard する。 */
    private static volatile boolean gridLinkableRegistered = false;

    /** mod 起動時に呼ぶ。AE2 の GridLinkables へ「列車プリセットツール」を登録し、
     *  Wireless Access Point の linkable slot にツールを入れられるようにする。 */
    public static void registerGridLinkable() {
        if (gridLinkableRegistered) return;
        gridLinkableRegistered = true;
        GridLinkables.register(ModItems.TRAIN_PRESET_TOOL.get(), TrainPresetLinkableHandler.INSTANCE);
        GridLinkables.register(ModItems.OVERHEAD_POLE_AUTO_TOOL.get(), OverheadPoleLinkableHandler.INSTANCE);
        TrainSystemUtilities.LOGGER.info("Registered train preset tool + overhead pole tool with AE2 GridLinkables");
    }

    /** AE2 が Wireless Access Point の slot へツールを入れたとき呼ぶ handler。
     *  link 時に WAP の GlobalPos をツールの DataComponent に保存する。 */
    public static final class TrainPresetLinkableHandler implements IGridLinkableHandler {
        public static final TrainPresetLinkableHandler INSTANCE = new TrainPresetLinkableHandler();

        @Override
        public boolean canLink(ItemStack stack) {
            return stack.is(ModItems.TRAIN_PRESET_TOOL.get());
        }

        @Override
        public void link(ItemStack stack, GlobalPos pos) {
            stack.set(ModDataComponents.PLACE_LINKED_WAP_POS.get(), pos);
            String label = "WAP " + pos.dimension().location()
                    + " (" + pos.pos().getX() + ", " + pos.pos().getY() + ", " + pos.pos().getZ() + ")";
            stack.set(ModDataComponents.PLACE_LINKED_WAP_LABEL.get(), label);
            // ME ソースモードへ自動切替 (リンク後すぐに ME 抽出が使えるように)
            TrainPresetToolItem.setMaterialSourceMode(stack, TrainPresetToolItem.SOURCE_ME);
            TrainPresetToolItem.clearPlacementStatus(stack);
        }

        @Override
        public void unlink(ItemStack stack) {
            stack.remove(ModDataComponents.PLACE_LINKED_WAP_POS.get());
            stack.remove(ModDataComponents.PLACE_LINKED_WAP_LABEL.get());
            TrainPresetToolItem.clearPlacementStatus(stack);
        }
    }

    /** 架線柱配置ツールを WAP の linkable slot に入れたとき呼ぶ handler。
     *  preset ツールと共用の PLACE_LINKED_WAP_POS に WAP 位置を保存する
     *  (= source mode 切替等の preset 固有処理はしない)。 */
    public static final class OverheadPoleLinkableHandler implements IGridLinkableHandler {
        public static final OverheadPoleLinkableHandler INSTANCE = new OverheadPoleLinkableHandler();

        @Override
        public boolean canLink(ItemStack stack) {
            return stack.is(ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
        }

        @Override
        public void link(ItemStack stack, GlobalPos pos) {
            stack.set(ModDataComponents.PLACE_LINKED_WAP_POS.get(), pos);
            String label = "WAP " + pos.dimension().location()
                    + " (" + pos.pos().getX() + ", " + pos.pos().getY() + ", " + pos.pos().getZ() + ")";
            stack.set(ModDataComponents.PLACE_LINKED_WAP_LABEL.get(), label);
        }

        @Override
        public void unlink(ItemStack stack) {
            stack.remove(ModDataComponents.PLACE_LINKED_WAP_POS.get());
            stack.remove(ModDataComponents.PLACE_LINKED_WAP_LABEL.get());
        }
    }

    /** リンク済み WAP からアクセスできる ME inventory を返す (取得失敗で null)。
     *  WAP がアクティブで grid に接続されている必要がある。 */
    public static MEStorage getLinkedWapInventory(Player player, ItemStack tool) {
        if (player == null || tool == null) return null;
        GlobalPos linkedPos = tool.get(ModDataComponents.PLACE_LINKED_WAP_POS.get());
        if (linkedPos == null) return null;

        MinecraftServer server = player.getServer();
        if (server == null) return null;

        ServerLevel wapLevel = server.getLevel(linkedPos.dimension());
        if (wapLevel == null) return null;

        BlockEntity be = wapLevel.getBlockEntity(linkedPos.pos());
        if (!(be instanceof IWirelessAccessPoint wap)) return null;
        if (!wap.isActive()) return null;

        IGrid grid = wap.getGrid();
        if (grid == null) return null;
        return getInventory(grid);
    }

    public static boolean canLinkAt(Level level, BlockPos pos) {
        return getGrid(level, pos) != null;
    }

    public static LinkedHashMap<Item, Integer> getMissing(Level level, BlockPos pos, Map<Item, Integer> requirements) {
        IGrid grid = getGrid(level, pos);
        return getMissingFromInventory(grid == null ? null : getInventory(grid), requirements);
    }

    public static boolean consume(Level level, BlockPos pos, Map<Item, Integer> requirements) {
        IGrid grid = getGrid(level, pos);
        if (grid == null) return false;
        var inventory = getInventory(grid);
        return inventory != null && consumeFromInventory(inventory, requirements);
    }

    /** リンク済み WAP 経由で ME ストレージに対する不足量を返す。 */
    public static LinkedHashMap<Item, Integer> getMissingFromWap(Player player, ItemStack tool,
                                                                 Map<Item, Integer> requirements) {
        return getMissingFromInventory(getLinkedWapInventory(player, tool), requirements);
    }

    /** リンク済み WAP 経由で ME ストレージから消費。成功で true。 */
    public static boolean consumeFromWap(Player player, ItemStack tool, Map<Item, Integer> requirements) {
        MEStorage inventory = getLinkedWapInventory(player, tool);
        return inventory != null && consumeFromInventory(inventory, requirements);
    }

    private static LinkedHashMap<Item, Integer> getMissingFromInventory(MEStorage inventory,
                                                                        Map<Item, Integer> requirements) {
        LinkedHashMap<Item, Integer> missing = new LinkedHashMap<>();
        if (inventory == null) {
            missing.putAll(requirements);
            return missing;
        }

        IActionSource source = IActionSource.empty();
        for (var entry : requirements.entrySet()) {
            long have = simulateExtract(inventory, entry.getKey(), entry.getValue(), source);
            if (have < entry.getValue()) {
                missing.put(entry.getKey(), (int) (entry.getValue() - have));
            }
        }
        return missing;
    }

    private static boolean consumeFromInventory(MEStorage inventory, Map<Item, Integer> requirements) {
        if (inventory == null) return false;

        if (!getMissingFromInventory(inventory, requirements).isEmpty()) {
            return false;
        }

        IActionSource source = IActionSource.empty();
        for (var entry : requirements.entrySet()) {
            long extracted = extract(inventory, entry.getKey(), entry.getValue(), source);
            if (extracted < entry.getValue()) {
                TrainSystemUtilities.LOGGER.warn("AE2 extraction shortfall for {}: {} / {}",
                        entry.getKey(), extracted, entry.getValue());
                return false;
            }
        }
        return true;
    }

    private static IGrid getGrid(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }

        IInWorldGridNodeHost host = level.getCapability(AECapabilities.IN_WORLD_GRID_NODE_HOST, pos);
        if (host == null) {
            return null;
        }

        for (Direction direction : Direction.values()) {
            IGridNode node = host.getGridNode(direction);
            if (node != null && node.getGrid() != null) {
                return node.getGrid();
            }
        }

        return null;
    }

    private static MEStorage getInventory(IGrid grid) {
        IStorageService storageService = grid.getService(IStorageService.class);
        return storageService == null ? null : storageService.getInventory();
    }

    private static long simulateExtract(MEStorage inventory, Item item, int count,
                                        IActionSource source) {
        AEItemKey direct = AEItemKey.of(new ItemStack(item));
        if (direct != null) {
            long extracted = inventory.extract(direct, count, Actionable.SIMULATE, source);
            if (extracted > 0) {
                return extracted;
            }
        }

        long found = 0;
        for (var key : inventory.getAvailableStacks().keySet()) {
            if (key instanceof AEItemKey itemKey && matchesItem(itemKey, item)) {
                long amount = inventory.extract(key, count, Actionable.SIMULATE, source);
                if (amount > found) {
                    found = amount;
                }
            }
        }
        return found;
    }

    private static long extract(MEStorage inventory, Item item, int count,
                                IActionSource source) {
        AEItemKey direct = AEItemKey.of(new ItemStack(item));
        if (direct != null) {
            long extracted = inventory.extract(direct, count, Actionable.MODULATE, source);
            if (extracted > 0) {
                return extracted;
            }
        }

        long remaining = count;
        long extractedTotal = 0;
        for (var key : inventory.getAvailableStacks().keySet()) {
            if (!(key instanceof AEItemKey itemKey) || !matchesItem(itemKey, item)) {
                continue;
            }

            long extracted = inventory.extract(key, remaining, Actionable.MODULATE, source);
            if (extracted <= 0) {
                continue;
            }

            extractedTotal += extracted;
            remaining -= extracted;
            if (remaining <= 0) {
                break;
            }
        }
        return extractedTotal;
    }

    private static boolean matchesItem(AEItemKey key, Item item) {
        if (key.getItem() == item) {
            return true;
        }
        if (key.getItem() instanceof BlockItem blockItem && item instanceof BlockItem neededBlock) {
            return blockItem.getBlock() == neededBlock.getBlock();
        }
        return false;
    }
}
