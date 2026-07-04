package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record TrainPresetMaterialConfigPayload(int action, int value) implements CustomPacketPayload {

    public static final int ACTION_SET_SOURCE_MODE = 0;
    public static final int ACTION_CLEAR_CHEST = 1;
    public static final int ACTION_CLEAR_ME = 2;
    /** ホイール式切替: value > 0 で次へ、value < 0 で前へ。 */
    public static final int ACTION_CYCLE_SOURCE_MODE = 3;

    public static final Type<TrainPresetMaterialConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "preset_material_config"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetMaterialConfigPayload> STREAM_CODEC =
            StreamCodec.of(TrainPresetMaterialConfigPayload::write, TrainPresetMaterialConfigPayload::read);

    private static void write(FriendlyByteBuf buf, TrainPresetMaterialConfigPayload payload) {
        buf.writeVarInt(payload.action);
        buf.writeVarInt(payload.value);
    }

    private static TrainPresetMaterialConfigPayload read(FriendlyByteBuf buf) {
        // C2S (untrusted): 範囲チェックは OverheadPoleAutoToolPayload と同じ規約 (action∈[0,16], value∈[-1000,1000])。
        int action = buf.readVarInt();
        int value = buf.readVarInt();
        if (action < 0 || action > 16) {
            throw new IllegalArgumentException("action " + action + " out of [0, 16]");
        }
        if (value < -1000 || value > 1000) {
            throw new IllegalArgumentException("value " + value + " out of [-1000, 1000]");
        }
        return new TrainPresetMaterialConfigPayload(action, value);
    }

    public static void handle(TrainPresetMaterialConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            ItemStack tool = TrainPresetToolItem.findHeldTool(player);
            if (tool.isEmpty()) {
                return;
            }

            switch (payload.action) {
                case ACTION_SET_SOURCE_MODE -> {
                    TrainPresetToolItem.setMaterialSourceMode(tool, payload.value);
                    TrainPresetToolItem.clearPlacementStatus(tool);
                }
                case ACTION_CLEAR_CHEST -> {
                    TrainPresetToolItem.clearLinkedChest(tool);
                    TrainPresetToolItem.clearPlacementStatus(tool);
                }
                case ACTION_CLEAR_ME -> {
                    TrainPresetToolItem.clearLinkedMe(tool);
                    TrainPresetToolItem.clearPlacementStatus(tool);
                }
                case ACTION_CYCLE_SOURCE_MODE -> {
                    TrainPresetToolItem.cycleMaterialSourceMode(tool, payload.value);
                    TrainPresetToolItem.clearPlacementStatus(tool);
                }
                default -> {
                    return;
                }
            }

            player.displayClientMessage(
                    Component.translatable("tsu.preset.material_config_updated").withStyle(ChatFormatting.AQUA),
                    true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
