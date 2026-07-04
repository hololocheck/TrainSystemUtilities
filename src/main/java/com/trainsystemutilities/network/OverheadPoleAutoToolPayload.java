package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 架線柱自動配置ツールのモード切替 / 値増減 (ホイール入力)。
 * action: 0 = edit mode 循環 (HEIGHT/CLEARANCE/SPAN)、
 *         1 = 現在 edit mode の値増減、
 *         2 = tool mode 循環 (GUI/SELECTION)。
 * delta: ホイール方向 +1/-1。
 *
 * <p>StationRangeToolModePayload と同じパターン (FriendlyByteBuf + StreamCodec.of)。
 */
public record OverheadPoleAutoToolPayload(int action, int delta) implements CustomPacketPayload {

    public static final Type<OverheadPoleAutoToolPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "overhead_pole_auto_tool"));

    public static final StreamCodec<FriendlyByteBuf, OverheadPoleAutoToolPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeVarInt(p.action); buf.writeVarInt(p.delta); },
                    buf -> {
                        int action = buf.readVarInt();
                        int delta = buf.readVarInt();
                        if (action < 0 || action > 16) {
                            throw new IllegalArgumentException("action " + action + " out of [0, 16]");
                        }
                        if (delta < -1000 || delta > 1000) {
                            throw new IllegalArgumentException("delta " + delta + " out of [-1000, 1000]");
                        }
                        return new OverheadPoleAutoToolPayload(action, delta);
                    });

    public static void handle(OverheadPoleAutoToolPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) return;
            int delta = payload.delta > 0 ? 1 : -1;
            switch (payload.action) {
                case 0 -> AutoPlaceConfig.setSubMode(stack,
                        AutoPlaceConfig.getSubMode(stack) + delta);
                case 1 -> AutoPlaceConfig.adjustCurrentMode(stack, delta);
                case 2 -> AutoPlaceConfig.setToolMode(stack,
                        AutoPlaceConfig.getToolMode(stack) + delta);
                case 3 -> AutoPlaceConfig.setManualRotation(stack,
                        AutoPlaceConfig.getManualRotation(stack) + delta);
            }
        });
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
