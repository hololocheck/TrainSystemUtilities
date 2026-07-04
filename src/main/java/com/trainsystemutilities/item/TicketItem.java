package com.trainsystemutilities.item;

import com.trainsystemutilities.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 切符 — 発駅 / 着駅 / 経路を DataComponent に記録した情報アイテム。
 *
 * <p>券売機 UI でクリックすると発券される (= {@code TICKET_FROM} / {@code TICKET_TO} /
 * {@code TICKET_TO_ID} / {@code TICKET_VIA} がセットされる)。 tooltip に「発 / 着 / 経由」を表示。
 * 本 v1 では情報アイテムのみ (= 改札検証は将来)。
 */
public class TicketItem extends Item {

    public TicketItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String from = stack.get(ModDataComponents.TICKET_FROM.get());
        String to = stack.get(ModDataComponents.TICKET_TO.get());
        String via = stack.get(ModDataComponents.TICKET_VIA.get());
        boolean any = false;
        if (from != null && !from.isEmpty()) {
            tooltip.add(Component.translatable("tsu.ticket.from", from).withStyle(ChatFormatting.GRAY));
            any = true;
        }
        if (to != null && !to.isEmpty()) {
            tooltip.add(Component.translatable("tsu.ticket.to", to).withStyle(ChatFormatting.AQUA));
            any = true;
        }
        if (via != null && !via.isEmpty()) {
            tooltip.add(Component.translatable("tsu.ticket.via", via).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (Boolean.TRUE.equals(stack.get(ModDataComponents.TICKET_ENTERED.get()))) {
            tooltip.add(Component.translatable("tsu.ticket.entered").withStyle(ChatFormatting.GREEN));
        }
        if (!any) {
            tooltip.add(Component.translatable("tsu.ticket.blank").withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }
}
