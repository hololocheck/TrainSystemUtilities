package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafePath;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: ブラウズ GUI の「設置」ボタンから送信。
 * tool_mode を Place に切替、selected_preset に対象プリセットのファイル名を保存。
 * authorDir は file path 解決用。
 */
public record TrainPresetSelectPayload(String authorDir, String fileName) implements CustomPacketPayload {

    public static final Type<TrainPresetSelectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_select"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetSelectPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
                buf.writeUtf(p.authorDir, 64);
                buf.writeUtf(p.fileName, 128);
            }, buf -> new TrainPresetSelectPayload(
                    // P0-4 #7: authorDir is a directory name (per-author bucket) → SafePath cap (255 bytes)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES),
                    // P0-4 #7: preset file name → SafePath cap (255 bytes)
                    BoundedStreamCodec.readBoundedUtf(buf, SafePath.MAX_FILENAME_BYTES)));

    public static void handle(TrainPresetSelectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            // SECURITY: 任意の authorDir/fileName が tool に書き込まれないよう厳格検証。
            // ここで通すと TrainPresetToolItem 経由で path traversal 由来の任意 NBT
            // 読込・設置誘導を許す。
            if (server == null
                    || com.trainsystemutilities.preset.TrainPresetStorage.safeResolveExisting(
                            server, payload.authorDir, payload.fileName) == null) {
                // P0-4 #7: sanitize user-controlled path components before logging (prevent log injection).
                TrainSystemUtilities.LOGGER.warn(
                        "[security] rejected preset_select with unsafe path from {}: authorDir={}, fileName={}",
                        player.getName().getString(),
                        SafeLog.sanitize(payload.authorDir),
                        SafeLog.sanitize(payload.fileName));
                player.displayClientMessage(Component.translatable("tsu.preset.delete_failed")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) {
                player.displayClientMessage(Component.translatable("tsu.preset.no_tool_held").withStyle(ChatFormatting.RED), true);
                return;
            }
            // selected_preset に "authorDir/fileName" 形式で保存
            String key = payload.authorDir + "/" + payload.fileName;
            stack.set(ModDataComponents.SELECTED_PRESET.get(), key);
            // 前プリセットの bogey 配置から算出された自動回転値を破棄。
            // 残したままだと新プリセットに古い回転が適用され、180° 反転して設置される。
            stack.remove(ModDataComponents.PLACE_AUTO_ROT_Y.get());
            stack.remove(ModDataComponents.PLACE_ORIGIN.get());
            stack.remove(ModDataComponents.PLACE_MARKER_DIRECTION.get());
            TrainPresetToolItem.setPlaceRotY(stack, 0);
            TrainPresetToolItem.setPlaceSubMode(stack, TrainPresetToolItem.SUB_ORIGIN);
            TrainPresetToolItem.setToolMode(stack, TrainPresetToolItem.TOOL_MODE_PLACE);
            player.displayClientMessage(
                    Component.translatable("tsu.preset.selected_for_place_fmt", payload.fileName)
                            .withStyle(ChatFormatting.AQUA), true);
        });
    }

    private static ItemStack findHeldTool(ServerPlayer player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
