package com.trainsystemutilities.event;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.neoforged.neoforge.common.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 列車プリセットツールを手に持って右クリックした際、
 * クリックされたブロックの {@code use()} を抑止する。
 *
 * 理由:
 *  - クリエイティブで Create のレールにクリックすると車輪 (Bogey) が出現する。
 *  - Origin モードでマーカー位置を選ぶ際にこの干渉が起きると意図と違うアクションが発火する。
 *
 * 仕組み:
 *  - {@link PlayerInteractEvent.RightClickBlock} で {@code event.setUseBlock(TriState.FALSE)} を呼ぶ
 *  - これにより block.use() は呼ばれなくなり、Item.useOn() のみが処理される
 *  - Item.useOn() 内では tool_mode = PLACE 時に origin 設定や placement を行う
 *
 * tool_mode == PLACE の時のみ抑止する。GUI / Selection モードでは通常通り動作。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public class TrainPresetToolBlockGuard {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player == null) return;
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !stack.is(ModItems.TRAIN_PRESET_TOOL.get())) return;
        if (TrainPresetToolItem.getToolMode(stack) != TrainPresetToolItem.TOOL_MODE_PLACE) return;
        // ブロックの use() を呼ばない (= Create の bogey 生成等を防ぐ)。
        // Item.useOn() は引き続き呼ばれて Origin / Place 処理が走る。
        event.setUseBlock(TriState.FALSE);
    }
}
