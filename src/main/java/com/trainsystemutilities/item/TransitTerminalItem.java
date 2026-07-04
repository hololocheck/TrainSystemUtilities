package com.trainsystemutilities.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 乗り換え案内端末 (= 駅検索 + 経路表示のスマホ風 Screen アイテム)。
 *
 * <p>Phase 19 で旧 HUD-only から Screen ベースの interactive UI に刷新。
 * 右クリックで {@link com.trainsystemutilities.client.transit.TransitTerminalScreen}
 * が開き、4 タブ (検索 / 時刻表 / 路線マップ / 設定) で操作する。
 *
 * <p>Screen 開放中は vanilla の Screen 挙動により:
 * <ul>
 *   <li>マウスカーソル解放 → 検索ボックス・タブ・タイルがクリック可能</li>
 *   <li>移動キー (W/A/S/D) は vanilla により消費されない (= 移動しないで typing できる)</li>
 *   <li>EditBox にフォーカスがあるときだけ文字が入力される</li>
 * </ul>
 */
public class TransitTerminalItem extends Item {

    public TransitTerminalItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            // dedicated server で Screen が force-load されないよう client opener (@OnlyIn) 経由で開く
            com.trainsystemutilities.client.transit.TransitTerminalScreenOpener.open();
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tsu.transit_terminal.tooltip_line1")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tsu.transit_terminal.tooltip_line2")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
