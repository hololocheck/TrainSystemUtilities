package com.trainsystemutilities.client.electrification;

import com.manta.api.hud.HeldTools;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.client.ToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 架線柱自動配置ツール所持時のクライアント入力処理。 列車プリセットツール / 駅範囲指定ツール
 * と同じ修飾子割当 + 値増減用 shift+wheel:
 * <ul>
 *   <li><b>alt + wheel</b>: tool mode 循環 (GUI / 選択) — 列車プリセットと統一。 「配置する」 サブモード中は手動回転 (8 方向)</li>
 *   <li><b>ctrl + wheel</b>: 選択モード内のサブモード循環 (GUIへ戻る / 配置する)</li>
 *   <li><b>shift + wheel</b>: 高さを増減</li>
 * </ul>
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class OverheadPoleAutoToolClientHandler {

    /** スクロール 2 重発火対策 (= 高頻度の 8 方向回転操作のため標準 180ms より短い 120ms)。 */
    private static final com.manta.api.hud.ScrollCooldown SCROLL_COOLDOWN =
            new com.manta.api.hud.ScrollCooldown(120_000_000L);

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;

        // R3.3.1 の優先順 (Alt > Ctrl > Shift) は部品側が持つ。
        // 下の if 連鎖が既に同じ順だったので振る舞いは変わらない。
        com.manta.api.hud.WheelInput.Mods mods = com.manta.api.hud.WheelInput.mods(mc.getWindow().getWindow());
        boolean alt = mods.alt();
        boolean ctrl = mods.ctrl();
        if (mods.none()) return;

        double dy = event.getScrollDeltaY();
        if (com.manta.api.hud.WheelInput.isDead(dy)) return;

        if (!SCROLL_COOLDOWN.tryAccept()) {
            event.setCanceled(true);
            return;
        }

        int delta = com.manta.api.hud.WheelInput.direction(dy);
        // alt + wheel:
        //   PLACE モード時 → 手動回転 (= 8 方向 ANGLE_8 増減)
        //   それ以外 → tool mode 切替 (= GUI ⇔ SELECTION)
        // ctrl + wheel: SELECTION 内のサブモード切替 (= GUIへ戻る / 配置する)
        if (alt) {
            int subMode = AutoPlaceConfig.getSubMode(stack);
            int toolMode = AutoPlaceConfig.getToolMode(stack);
            boolean inPlaceMode = (toolMode == AutoPlaceConfig.TOOL_MODE_SELECTION
                    && subMode == AutoPlaceConfig.SUB_MODE_PLACE);
            int action = inPlaceMode ? 3 : 2;
            ToolsClient.poleTool(action, delta);
        } else if (ctrl) {
            ToolsClient.poleTool(0, delta);
        } else {
            // shift + wheel: 高さの増減 (= R3.2.3 / 本クラス Javadoc の契約)。
            // 旧実装は setCanceled せず return しており、 cooldown を消費したうえ vanilla の
            // hotbar 切替が発火していた (= R3.5 違反)。
            ToolsClient.poleTool(1, delta);
        }
        event.setCanceled(true);
    }

    /** shift+中クリックで視線先のチェストを資材倉庫としてリンク (= 列車プリセットと同操作)。
     *  ME 倉庫は AE2 の Wireless Access Point linkable slot にツールを入れてリンクする。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        ItemStack stack = findHeldTool();
        if (stack.isEmpty()) return;
        if (event.getButton() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE && mc.player.isShiftKeyDown()) {
            net.minecraft.core.BlockPos chestPos =
                    com.trainsystemutilities.client.TrainPresetToolClientHandler.findLookedAtChest(mc);
            if (chestPos != null) {
                ToolsClient.poleLinkChest(chestPos);
                event.setCanceled(true);
            }
        }
    }

    private static ItemStack findHeldTool() {
        return HeldTools.find(Minecraft.getInstance().player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }
}
