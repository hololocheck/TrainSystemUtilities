package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeLog;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetMaterials;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.registry.ModDataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「このプリセットを今のリンク先 (Chest / ME / 両方) で配置したら何が不足するか」 — the player's presets host's
 * {@code material} action ({@link PresetsData}; TrainPresetMaterialCheckRequestPayload until MANTA_7_CONCEPT C4).
 * BrowseScreen の素材一覧 popup が開いている間に定期 polling される。 The body is the payload's handler; the answer
 * (TrainPresetMaterialCheckResponsePayload until then) is the host's material fields.
 */
public final class TrainPresetMaterialCheckAction {

    // TSU-19: disk load + ME/chest scan で重い request。 client は self-throttle (1.5s) するが、 abuse client 向けに
    // server 側でも per-player の最小間隔を強制する。 250ms = legit polling を壊さず毎秒数百の spam だけ遮断。
    private static final java.util.Map<java.util.UUID, Long> LAST_REQ_NS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_NS = 250_000_000L; // 250ms

    private TrainPresetMaterialCheckAction() {}

    public static void apply(ServerPlayer player, String authorDir, String fileName) {
        // TSU-19: per-player rate limit (atomic compute で TOCTOU 回避)。 超過は silently drop (client が再 poll)。
        long now = System.nanoTime();
        Long updated = LAST_REQ_NS.compute(player.getUUID(), (k, prev) ->
                (prev != null && now - prev < MIN_INTERVAL_NS) ? prev : now);
        if (updated != now) return;
        ItemStack tool = TrainPresetToolItem.findHeldTool(player);
        if (tool.isEmpty()) return;

        // SECURITY: path traversal 防御 — 任意 NBT 読込 + 繰り返し DoS 抑止
        var path = TrainPresetStorage.safeResolveExisting(
                player.getServer(), authorDir, fileName);
        if (path == null) {
            // P0-4 #7: sanitize user-controlled strings (authorDir/fileName) before logging
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "[security] rejected material_check with unsafe path from {}: authorDir={}, fileName={}",
                    player.getName().getString(),
                    SafeLog.sanitize(authorDir),
                    SafeLog.sanitize(fileName));
            return;
        }
        TrainPreset preset;
        try {
            preset = TrainPresetStorage.load(path);
        } catch (Exception ex) {
            TrainSystemUtilities.LOGGER.debug("[Preset] material-check preset load failed", ex);
            return;
        }
        if (preset == null) return;

        var requirements = TrainPresetMaterials.collectBaseRequirements(player.serverLevel(), preset);
        LinkedHashMap<Item, Integer> missing = computeMissing(player, tool, requirements);

        String encoded = TrainPresetMaterials.encode(missing);
        PresetsData.answerMaterial(player, authorDir, fileName, encoded);
    }

    private static LinkedHashMap<Item, Integer> computeMissing(ServerPlayer player, ItemStack tool,
                                                                 Map<Item, Integer> requirements) {
        int sourceMode = TrainPresetToolItem.getMaterialSourceMode(tool);
        ServerLevel level = player.serverLevel();

        if (sourceMode == TrainPresetToolItem.SOURCE_BOTH) {
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
        if (sourceMode == TrainPresetToolItem.SOURCE_ME) {
            if (!net.neoforged.fml.ModList.get().isLoaded("ae2")
                    || tool.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) == null) {
                return new LinkedHashMap<>(requirements);
            }
            return TrainPresetSupply.getWapMissing(player, tool, requirements);
        }
        var chestPos = TrainPresetToolItem.getLinkedChestPos(tool);
        if (chestPos == null) return new LinkedHashMap<>(requirements);
        return TrainPresetSupply.getChestMissing(level, chestPos, requirements);
    }
}
