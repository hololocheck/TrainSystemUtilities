package com.trainsystemutilities.event;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.electrification.autoplace.OverheadPoleSupply;
import com.trainsystemutilities.preset.TrainPresetMaterials;
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

/**
 * 架線柱配置ツール所持者のリンク済み倉庫 (chest+ME) の在庫を 20 tick ごとに監視し、
 * {@code AUTO_POLE_STOCK} DataComponent に encode して書き込む。
 * ItemStack の自動 sync で client HUD ({@code OverheadPoleAutoToolHudRenderer}) が
 * 「あと何個 / 何ユニット設置できるか」 をリアルタイム表示する。
 *
 * <p>列車プリセットの {@link TrainPresetMaterialMonitor} と同じ 20-tick + creative skip パターン。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class OverheadPoleMaterialMonitor {

    private static final int TICK_INTERVAL = 20;
    private static int tickCounter = 0;

    private OverheadPoleMaterialMonitor() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack tool = belugalab.tsu.api.HeldTools.find(player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
            if (tool.isEmpty()) continue;
            // 設置 (SELECTION) モードのみ監視。 GUI モードは不要。
            if (AutoPlaceConfig.getToolMode(tool) != AutoPlaceConfig.TOOL_MODE_SELECTION) continue;

            // クリエイティブは消費なし → 在庫表示も不要なのでクリア
            if (player.getAbilities().instabuild) {
                if (tool.get(ModDataComponents.AUTO_POLE_STOCK.get()) != null) {
                    tool.remove(ModDataComponents.AUTO_POLE_STOCK.get());
                }
                continue;
            }

            try {
                checkOne(player, tool);
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn("Overhead pole material monitor failed: {}", t.getMessage());
            }
        }
    }

    private static void checkOne(ServerPlayer player, ItemStack tool) {
        ServerLevel level = player.serverLevel();
        Item pole = ModItems.OVERHEAD_POLE.get();
        Item truss = ModItems.OVERHEAD_TRUSS.get();
        Item ins = ModItems.INSULATOR.get();

        LinkedHashMap<Item, Integer> stock = new LinkedHashMap<>();
        int p = OverheadPoleSupply.available(player, tool, level, pole);
        int t = OverheadPoleSupply.available(player, tool, level, truss);
        int i = OverheadPoleSupply.available(player, tool, level, ins);
        if (p > 0) stock.put(pole, p);
        if (t > 0) stock.put(truss, t);
        if (i > 0) stock.put(ins, i);

        String encoded = TrainPresetMaterials.encode(stock);
        String prev = tool.get(ModDataComponents.AUTO_POLE_STOCK.get());
        if (prev == null) prev = "";
        if (!encoded.equals(prev)) {
            if (encoded.isBlank()) {
                tool.remove(ModDataComponents.AUTO_POLE_STOCK.get());
            } else {
                tool.set(ModDataComponents.AUTO_POLE_STOCK.get(), encoded);
            }
        }
    }
}
