package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetMaterials;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.registry.ModDataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client → Server: 「このプリセットを今のリンク先 (Chest / ME / 両方) で配置したら何が不足するか」を問い合わせ。
 * Server は計算結果を {@link TrainPresetMaterialCheckResponsePayload} で返す。
 * BrowseScreen の素材一覧 popup が開いている間に定期 polling される。
 */
public record TrainPresetMaterialCheckRequestPayload(String authorDir, String fileName)
        implements CustomPacketPayload {

    public static final Type<TrainPresetMaterialCheckRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_material_check_req"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetMaterialCheckRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeUtf(p.authorDir); buf.writeUtf(p.fileName); },
                    buf -> new TrainPresetMaterialCheckRequestPayload(
                            // P0-4 #7: authorDir — file name component, MAX_FILENAME_BYTES (255)
                            BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                            // P0-4 #7: fileName — file name, MAX_FILENAME_BYTES (255)
                            BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES)));

    // TSU-19: disk load + ME/chest scan で重い request。 client は self-throttle (1.5s) するが、 abuse client 向けに
    // server 側でも per-player の最小間隔を強制する。 250ms = legit polling を壊さず毎秒数百の spam だけ遮断。
    private static final java.util.Map<java.util.UUID, Long> LAST_REQ_NS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_NS = 250_000_000L; // 250ms

    public static void handle(TrainPresetMaterialCheckRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // TSU-19: per-player rate limit (atomic compute で TOCTOU 回避)。 超過は silently drop (client が再 poll)。
            long now = System.nanoTime();
            Long updated = LAST_REQ_NS.compute(player.getUUID(), (k, prev) ->
                    (prev != null && now - prev < MIN_INTERVAL_NS) ? prev : now);
            if (updated != now) return;
            ItemStack tool = TrainPresetToolItem.findHeldTool(player);
            if (tool.isEmpty()) return;

            // SECURITY: path traversal 防御 — 任意 NBT 読込 + 繰り返し DoS 抑止
            var path = TrainPresetStorage.safeResolveExisting(
                    player.getServer(), payload.authorDir, payload.fileName);
            if (path == null) {
                // P0-4 #7: sanitize user-controlled strings (authorDir/fileName) before logging
                com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected material_check with unsafe path from {}: authorDir={}, fileName={}",
                        player.getName().getString(),
                        SafeLog.sanitize(payload.authorDir),
                        SafeLog.sanitize(payload.fileName));
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
            PacketDistributor.sendToPlayer(player,
                    new TrainPresetMaterialCheckResponsePayload(payload.authorDir, payload.fileName, encoded));
        });
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

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
