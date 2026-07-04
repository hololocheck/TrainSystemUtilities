package com.trainsystemutilities.client;

import belugalab.tsu.api.HeldTools;
import belugalab.tsu.api.ModifierKeys;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.network.TrainPresetCancelPlacePayload;
import com.trainsystemutilities.network.TrainPresetLinkChestPayload;
import com.trainsystemutilities.network.TrainPresetRetryPayload;
import com.trainsystemutilities.network.TrainPresetToolModePayload;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 列車プリセットツール所持時のクライアント入力処理。
 *
 *  - alt + ホイール          : tool_mode 切替 (GUI / Selection)、または PLACE モード時は sub-mode 切替
 *  - alt + shift + ホイール  : range_edit_mode 切替、または PLACE+Y回転時は 90° ずつ回転
 *  - shift + 中クリック       : PLACE モードをキャンセル (GUI モードへ戻る、起点クリア)
 *
 * action bar への hint メッセージは HUD と重複するため出さない。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class TrainPresetToolClientHandler {

    /** スクロールイベントの 2 重発火対策 (= BelugaExperience 標準 ScrollCooldown 180ms)。 */
    private static final belugalab.tsu.api.ScrollCooldown SCROLL_COOLDOWN =
            new belugalab.tsu.api.ScrollCooldown();

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;

        long window = mc.getWindow().getWindow();
        boolean alt = ModifierKeys.alt(window);
        boolean ctrl = ModifierKeys.ctrl(window);
        if (!alt && !ctrl) return;

        double dy = event.getScrollDeltaY();
        if (Math.abs(dy) < 0.0001) return;

        // スクロールクールダウン: 高速スクロール時のスキップ防止
        if (!SCROLL_COOLDOWN.tryAccept()) {
            event.setCanceled(true);
            return;
        }

        int delta = dy > 0 ? 1 : -1;
        // ctrl + ホイール: action 1 (回転値) 優先
        // alt + ホイール (ctrl なし): action 0 (モード循環)
        int action = ctrl ? 1 : 0;
        PacketDistributor.sendToServer(new TrainPresetToolModePayload(action, delta));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;

        // shift + 中クリック (button 2): PLACE モード中はキャンセル、それ以外でチェスト視線中はリンク
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && mc.player.isShiftKeyDown()) {
            if (TrainPresetToolItem.getToolMode(stack) == TrainPresetToolItem.TOOL_MODE_PLACE) {
                PacketDistributor.sendToServer(new TrainPresetCancelPlacePayload());
                event.setCanceled(true);
                return;
            }

            BlockPos chestPos = findLookedAtChest(mc);
            if (chestPos != null) {
                PacketDistributor.sendToServer(new TrainPresetLinkChestPayload(chestPos));
                event.setCanceled(true);
            }
            return;
        }

        // 中クリック (shift なし) 単体: PLACE モード + 再開可能なら再試行
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                && TrainPresetToolItem.getToolMode(stack) == TrainPresetToolItem.TOOL_MODE_PLACE
                && TrainPresetToolItem.isPlaceResumeReady(stack)) {
            PacketDistributor.sendToServer(new TrainPresetRetryPayload());
            event.setCanceled(true);
        }
    }

    /** プレイヤーがカーソルを合わせているブロックがチェスト系 (Container) であれば座標を返す。 */
    public static BlockPos findLookedAtChest(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bhr.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, mc.level, pos, true) != null ? pos : null;
        }
        BlockEntity be = mc.level.getBlockEntity(pos);
        return be instanceof Container ? pos : null;
    }

    private static ItemStack findHeldTool() {
        return HeldTools.find(Minecraft.getInstance().player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
