package com.trainsystemutilities.event;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetMaterials;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列車プリセットツール所持者の「資材待ち状態」を 20 tick ごとに監視。
 *  - 不足アイテムがあれば PLACE_MISSING_ITEMS を更新
 *  - 完全に揃ったら PLACE_RESUME_READY = true をセット (HUD に「再開できます」を表示するための信号)
 *  - 起点や preset 未設定なら何もしない
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class TrainPresetMaterialMonitor {

    private static final int TICK_INTERVAL = 20;
    private static int tickCounter = 0;

    private TrainPresetMaterialMonitor() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack tool = findHeldTool(player);
            if (tool.isEmpty()) continue;
            if (TrainPresetToolItem.getToolMode(tool) != TrainPresetToolItem.TOOL_MODE_PLACE) continue;
            if (player.getAbilities().instabuild
                    && !com.trainsystemutilities.command.TrainPresetCommand.isForceConsumeEnabled()) {
                // クリエイティブで強制消費 OFF なら監視不要
                continue;
            }

            try {
                checkOne(player, tool);
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Material monitor failed: {}", t.getMessage());
            }
        }
    }

    private static void checkOne(ServerPlayer player, ItemStack tool) {
        TrainPreset preset = loadSelectedPreset(player, tool);
        if (preset == null) return;

        var requirements = TrainPresetMaterials.collectBaseRequirements(player.serverLevel(), preset);
        if (requirements.isEmpty()) {
            // 資材不要 (空 preset 等) → クリア
            TrainPresetToolItem.clearPlacementStatus(tool);
            return;
        }

        LinkedHashMap<Item, Integer> missing = computeCurrentMissing(player, tool, requirements);
        String encoded = TrainPresetMaterials.encode(missing);
        String prevEncoded = TrainPresetToolItem.getMissingItems(tool);
        if (prevEncoded == null) prevEncoded = "";

        // 不足リストを最新に更新 (popup の色判定にも使うので常時)
        if (!encoded.equals(prevEncoded)) {
            if (encoded.isBlank()) {
                tool.remove(ModDataComponents.PLACE_MISSING_ITEMS.get());
            } else {
                TrainPresetToolItem.setMissingItems(tool, encoded);
            }
        }

        // 「直近で失敗した」状態 (STATUS_MESSAGE 設定済) で資材が揃ったら再開可能フラグを立てる
        boolean wasFailed = TrainPresetToolItem.getPlacementStatusMessage(tool) != null;
        boolean originSet = TrainPresetToolItem.getPlaceOrigin(tool) != null;
        boolean nowOk = missing.isEmpty();
        boolean wasReady = TrainPresetToolItem.isPlaceResumeReady(tool);
        boolean shouldReady = wasFailed && originSet && nowOk;
        if (shouldReady != wasReady) {
            TrainPresetToolItem.setPlaceResumeReady(tool, shouldReady);
        }
    }

    private static LinkedHashMap<Item, Integer> computeCurrentMissing(ServerPlayer player, ItemStack tool,
                                                                       Map<Item, Integer> requirements) {
        int sourceMode = TrainPresetToolItem.getMaterialSourceMode(tool);
        ServerLevel level = player.serverLevel();

        if (sourceMode == TrainPresetToolItem.SOURCE_BOTH) {
            return computeBothMissing(player, tool, level, requirements);
        }
        if (sourceMode == TrainPresetToolItem.SOURCE_ME) {
            if (!net.neoforged.fml.ModList.get().isLoaded("ae2")
                    || tool.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) == null) {
                return new LinkedHashMap<>(requirements);
            }
            return TrainPresetSupply.getWapMissing(player, tool, requirements);
        }
        // SOURCE_CHEST
        var chestPos = TrainPresetToolItem.getLinkedChestPos(tool);
        if (chestPos == null) return new LinkedHashMap<>(requirements);
        return TrainPresetSupply.getChestMissing(level, chestPos, requirements);
    }

    private static LinkedHashMap<Item, Integer> computeBothMissing(ServerPlayer player, ItemStack tool,
                                                                    ServerLevel level,
                                                                    Map<Item, Integer> requirements) {
        boolean ae2 = net.neoforged.fml.ModList.get().isLoaded("ae2");
        boolean wapLinked = ae2 && tool.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) != null;
        var chestPos = TrainPresetToolItem.getLinkedChestPos(tool);

        var meMissing = wapLinked
                ? TrainPresetSupply.getWapMissing(player, tool, requirements)
                : new LinkedHashMap<>(requirements);
        var chestRemainder = new LinkedHashMap<Item, Integer>();
        for (var e : requirements.entrySet()) {
            int meHave = e.getValue() - meMissing.getOrDefault(e.getKey(), 0);
            int chestNeed = Math.max(0, e.getValue() - meHave);
            if (chestNeed > 0) chestRemainder.put(e.getKey(), chestNeed);
        }
        return chestPos == null
                ? new LinkedHashMap<>(chestRemainder)
                : TrainPresetSupply.getChestMissing(level, chestPos, chestRemainder);
    }

    private static TrainPreset loadSelectedPreset(ServerPlayer player, ItemStack tool) {
        String key = tool.get(ModDataComponents.SELECTED_PRESET.get());
        if (key == null || key.isEmpty()) return null;
        int sep = key.indexOf('/');
        if (sep < 0) return null;
        String authorDir = key.substring(0, sep);
        String fileName = key.substring(sep + 1);
        var path = TrainPresetStorage.getRootDir(player.getServer()).resolve(authorDir).resolve(fileName);
        try {
            return TrainPresetStorage.load(path);
        } catch (Exception ignored) {
            TrainSystemUtilities.LOGGER.debug("[MaterialMonitor] selected preset load failed", ignored);
            return null;
        }
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
