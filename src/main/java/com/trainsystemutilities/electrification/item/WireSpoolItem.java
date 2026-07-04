package com.trainsystemutilities.electrification.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 架線スプール — 架線接続ツール (WireConnectorItem) に装填する巻線アイテム。
 * 1 個で {@link #METERS_PER_SPOOL} m (= block) 分の架線を張れる。GUI モードの
 * ロードスロットに入れるとツール内タンクに充填され、設置モードで距離分だけ消費される。
 */
public class WireSpoolItem extends Item {

    /** 1 スプールで張れる架線長 (m / block)。 */
    public static final int METERS_PER_SPOOL = 100;

    public WireSpoolItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltips, TooltipFlag flag) {
        tooltips.add(Component.translatable("tsu.wire_spool.tt_desc").withStyle(ChatFormatting.GRAY));
        tooltips.add(Component.translatable("tsu.wire_spool.tt_meters_fmt", METERS_PER_SPOOL)
                .withStyle(ChatFormatting.AQUA));
    }
}
