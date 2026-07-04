package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 列車プリセットツールのモードを切り替える (alt+ホイールから送信)。
 * action: 0=tool_mode 切替、1=range_edit_mode 切替。
 * delta: ホイール方向 (+1 / -1)。
 */
public record TrainPresetToolModePayload(int action, int delta) implements CustomPacketPayload {

    public static final Type<TrainPresetToolModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_preset_tool_mode"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetToolModePayload> STREAM_CODEC =
            StreamCodec.of(TrainPresetToolModePayload::write, TrainPresetToolModePayload::read);

    private static void write(FriendlyByteBuf buf, TrainPresetToolModePayload p) {
        buf.writeVarInt(p.action);
        buf.writeVarInt(p.delta);
    }

    private static TrainPresetToolModePayload read(FriendlyByteBuf buf) {
        // C2S (untrusted): 範囲チェックは OverheadPoleAutoToolPayload と同じ規約 (action∈[0,16], delta∈[-1000,1000])。
        int action = buf.readVarInt();
        int delta = buf.readVarInt();
        if (action < 0 || action > 16) {
            throw new IllegalArgumentException("action " + action + " out of [0, 16]");
        }
        if (delta < -1000 || delta > 1000) {
            throw new IllegalArgumentException("delta " + delta + " out of [-1000, 1000]");
        }
        return new TrainPresetToolModePayload(action, delta);
    }

    public static void handle(TrainPresetToolModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) return;

            int curMode = TrainPresetToolItem.getToolMode(stack);
            if (payload.action == 0) {
                // PLACE モード中: alt+wheel 下回し で 起点→回転→設置 の順に進む。
                // (client は scrollY > 0 を delta=+1 にして送る = ホイール上)。下回しを「forward」に揃える。
                if (curMode == TrainPresetToolItem.TOOL_MODE_PLACE) {
                    int sub = TrainPresetToolItem.getPlaceSubMode(stack);
                    int next = sub + (payload.delta > 0 ? -1 : 1);
                    TrainPresetToolItem.setPlaceSubMode(stack, next);
                } else {
                    // GUI/Selection 間トグル (こちらも下回し = forward)
                    int next = (curMode + (payload.delta > 0 ? -1 : 1)) & 1;
                    TrainPresetToolItem.setToolMode(stack, next);
                }
            } else if (payload.action == 1) {
                // PLACE モード中: 現在の sub-mode に応じて回転値を更新
                if (curMode == TrainPresetToolItem.TOOL_MODE_PLACE) {
                    int sub = TrainPresetToolItem.getPlaceSubMode(stack);
                    if (sub == TrainPresetToolItem.SUB_ROT_Y) {
                        int cur = TrainPresetToolItem.getPlaceRotY(stack);
                        TrainPresetToolItem.setPlaceRotY(stack, cur + (payload.delta > 0 ? 1 : -1));
                    }
                    // X/Z 回転は将来対応
                } else {
                    // Selection モード: range_edit_mode を 0/1/2 で循環
                    int cur = TrainPresetToolItem.getEditMode(stack);
                    int next = ((cur + (payload.delta > 0 ? 1 : -1)) % 3 + 3) % 3;
                    TrainPresetToolItem.setEditMode(stack, next);
                }
            }
        });
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
