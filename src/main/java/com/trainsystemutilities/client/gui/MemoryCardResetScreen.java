package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import com.trainsystemutilities.blockentity.ManagementComputerSettings;
import com.trainsystemutilities.item.MemoryCardItem;
import com.trainsystemutilities.network.MemoryCardResetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * メモリーカードの初期化確認ダイアログ。
 *
 * <p>1.0.10 でカードが管理用コンピューターの設定一式を持つようになったため、 シフト右クリック
 * 1 回で全部消えるのは危険になった。 消える中身 (路線記号 / 駅割り当て / リンク登録) を数えて
 * 見せてから確認する。 確認ダイアログなので R4.17.1 に従い × ボタンは置かない (ESC で閉じる)。
 */
@OnlyIn(Dist.CLIENT)
public class MemoryCardResetScreen extends JsonLayoutPlainScreen {

    private final InteractionHand hand;

    public MemoryCardResetScreen(InteractionHand hand) {
        super(Component.translatable("tsu.memory_card.reset_title"));
        this.hand = hand;
    }

    @Override
    protected String wikiPageId() { return null; }

    @Override
    protected String layoutJson() { return TsuLayouts.load("layouts/memory-card-reset.json"); }

    private ItemStack card() {
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        for (String c : classes) {
            if ("mcr-summary-symbols".equals(c)) {
                ItemStack stack = card();
                return Component.translatable("tsu.memory_card.reset_summary_symbols",
                        String.valueOf(ManagementComputerSettings.symbolCount(stack)),
                        String.valueOf(ManagementComputerSettings.assignmentCount(stack))).getString();
            }
            if ("mcr-summary-link".equals(c)) return linkSummary();
        }
        return null;
    }

    private String linkSummary() {
        ItemStack stack = card();
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Component.translatable("tsu.memory_card.reset_summary_link_none").getString();
        CompoundTag tag = data.copyTag();
        String type = tag.getString("Type");
        if ("railway_manager".equals(type)) {
            String station = tag.getString("StationName");
            return Component.translatable("tsu.memory_card.reset_summary_link_manager",
                    station.isEmpty() ? "-" : station).getString();
        }
        if ("track_network".equals(type)) {
            return Component.translatable("tsu.memory_card.reset_summary_link_track",
                    String.valueOf(tag.getInt("Stations"))).getString();
        }
        if (MemoryCardItem.TYPE_SCREEN_DOOR_GROUP.equals(type)) {
            return Component.translatable("tsu.memory_card.reset_summary_link_doors",
                    String.valueOf(tag.getList(MemoryCardItem.TAG_MEMBERS, Tag.TAG_LONG).size())).getString();
        }
        return Component.translatable("tsu.memory_card.reset_summary_link_none").getString();
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        for (String c : classes) {
            if ("mcr-confirm".equals(c)) {
                PacketDistributor.sendToServer(new MemoryCardResetPayload(hand.ordinal()));
                onClose();
                return;
            }
            if ("mcr-cancel".equals(c)) { onClose(); return; }
        }
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
