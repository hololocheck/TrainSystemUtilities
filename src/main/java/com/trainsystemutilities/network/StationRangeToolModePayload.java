package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.StationRangeToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 駅範囲指定ツールのモード切替 (ホイール入力)。
 * action: 0 = TOOL_MODE 循環 (GUI/SELECTION/VIEW), 1 = RANGE_EDIT_MODE 循環 (0/1/2),
 *         2 = PLATFORM_NUMBERING_DIR 循環 (auto/left/right)。
 * delta: ホイール方向 (+1 / -1)。
 *
 * <p>列車プリセットツール ({@link TrainPresetToolModePayload}) と同じ規約。
 */
public record StationRangeToolModePayload(int action, int delta) implements CustomPacketPayload {

    public static final Type<StationRangeToolModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "station_range_tool_mode"));

    public static final StreamCodec<FriendlyByteBuf, StationRangeToolModePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeVarInt(p.action); buf.writeVarInt(p.delta); },
                    // P0-4 #7: action は 0..2 の固定 enum (writeVarInt 経由)
                    //   delta はホイール方向で負数可 → varint 経由のため manual 範囲チェック
                    buf -> {
                        int action = BoundedStreamCodec.readBoundedVarInt(buf, 16);
                        int delta = buf.readVarInt();
                        if (delta < -1000 || delta > 1000) {
                            throw new IllegalArgumentException(
                                    "StationRangeToolModePayload delta " + delta + " out of [-1000, 1000]");
                        }
                        return new StationRangeToolModePayload(action, delta);
                    });

    public static void handle(StationRangeToolModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) return;
            int delta = payload.delta > 0 ? 1 : -1;
            if (payload.action == 0) {
                int cur = StationRangeToolItem.getToolMode(stack);
                StationRangeToolItem.setToolMode(stack, cur + delta);
            } else if (payload.action == 1) {
                int cur = StationRangeToolItem.getEditMode(stack);
                StationRangeToolItem.setEditMode(stack, cur + delta);
            } else if (payload.action == 2) {
                int cur = StationRangeToolItem.getNumberingDir(stack);
                StationRangeToolItem.setNumberingDir(stack, cur + delta);
            }
        });
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.STATION_RANGE_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
