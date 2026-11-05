package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.trainsystemutilities.blockentity.ManagementComputerSettings;
import com.trainsystemutilities.item.MemoryCardItem;
import com.trainsystemutilities.client.ToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * メモリーカードの初期化確認ダイアログ。
 *
 * <p>1.0.10 でカードが管理用コンピューターの設定一式を持つようになったため、 シフト右クリック
 * 1 回で全部消えるのは危険になった。 消える中身 (路線記号 / 駅割り当て / リンク登録) を数えて
 * 見せてから確認する。 確認ダイアログなので R4.17.1 に従い × ボタンは置かない (ESC で閉じる)。
 */
@OnlyIn(Dist.CLIENT)
public class MemoryCardResetScreen extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    private final InteractionHand hand;

    /** 共通の確認ダイアログ (R4.27.1)。この画面はダイアログそのものなので開いたまま。 */
    private final com.manta.api.screen.ConfirmDialog<Void> resetConfirm = com.manta.api.screen.ConfirmDialog.<Void>danger()
            .title("tsu.memory_card.reset_title")
            .yes("tsu.memory_card.reset_action")
            .no("tsu.memory_card.reset_cancel")
            .warning("tsu.memory_card.reset_warning")
            .body(ignored -> symbolSummary())
            .detail(ignored -> linkSummary());

    public MemoryCardResetScreen(InteractionHand hand) {
        super(Component.translatable("tsu.memory_card.reset_title"));
        this.hand = hand;
        this.resetConfirm.ask();
    }

    /**
     * The wiki's stand-in's card: a demo one linked to a railway management block, carrying three line symbols and four
     * station assignments - not the card in the player's hand (改善1, 2026-10-07). Null: the card in hand.
     */
    private ItemStack wikiCard = null;

    /** The wiki's stand-in (WikiLiveCapture): the reset confirmation for the demo card. */
    public static MemoryCardResetScreen wikiCreate() {
        MemoryCardResetScreen s = new MemoryCardResetScreen(InteractionHand.MAIN_HAND);
        ItemStack card = new ItemStack(com.trainsystemutilities.registry.ModItems.MEMORY_CARD.get());
        CompoundTag link = new CompoundTag();
        link.putString("Type", "railway_manager");
        link.putString("StationName", "東京");
        card.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(link));
        CompoundTag settings = new CompoundTag();
        net.minecraft.nbt.ListTag symbols = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 3; i++) symbols.add(new CompoundTag());
        settings.put("LineSymbols", symbols);
        CompoundTag map = new CompoundTag();
        for (String n : new String[] {"東京", "品川", "新宿", "上野"}) map.putString(n, "JY");
        settings.put("StationSymbolMap", map);
        ManagementComputerSettings.writeSettings(card, settings);
        s.wikiCard = card;
        return s;
    }

    @Override
    protected String wikiPageId() { return null; }

    @Override
    protected String layoutJson() {
        return com.manta.api.screen.MantaLayouts.load(com.manta.api.screen.ConfirmDialog.LAYOUT);
    }

    // ===== Manta 7 push (Phase 4, TSU batch 2, 2026-09-23) =====
    // The dialog IS the page: ConfirmDialog.push writes its texts and the action button's colour. The
    // counts read the card in hand, so it runs each frame too (an unchanged value costs nothing).
    private MantaState pushed;

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        resetConfirm.push(pushed);
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (pushed != null && pushed.isOpen()) resetConfirm.push(pushed);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private ItemStack card() {
        if (wikiCard != null) return wikiCard;
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getItemInHand(hand);
    }

    private String symbolSummary() {
        ItemStack stack = card();
        return Component.translatable("tsu.memory_card.reset_summary_symbols",
                String.valueOf(ManagementComputerSettings.symbolCount(stack)),
                String.valueOf(ManagementComputerSettings.assignmentCount(stack))).getString();
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
                    String.valueOf(MemoryCardItem.readMembers(tag).length)).getString();
        }
        return Component.translatable("tsu.memory_card.reset_summary_link_none").getString();
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // この画面はダイアログ自体なので、どちらを押しても画面ごと閉じる。
        if (resetConfirm.handleClick(classes, ignored -> {
                    if (wikiCard == null) ToolsClient.memoryCardReset(hand.ordinal());
                })) {
            if (!resetConfirm.isOpen()) onClose();
            return;
        }
    }

    @Override
    protected void performClose() { Minecraft.getInstance().setScreen(null); }
}
