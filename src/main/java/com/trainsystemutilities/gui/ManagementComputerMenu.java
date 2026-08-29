package com.trainsystemutilities.gui;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.registry.ModMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

public class ManagementComputerMenu extends AbstractContainerMenu {

    private final ManagementComputerBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    public ManagementComputerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, extraData));
    }

    public ManagementComputerMenu(int containerId, Inventory playerInventory,
                                   ManagementComputerBlockEntity blockEntity) {
        super(ModMenuTypes.MANAGEMENT_COMPUTER_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        // Slot 0: メモリーカードスロット (CSS レイアウトで位置自動同期)
        // mayPlace は必須。 vanilla の Slot.mayPlace は container.canPlaceItem を見ずに
        // 無条件 true を返すので、 フィルタが無いと quickMoveStack の
        // moveItemStackTo(stack, 0, 4, false) が先頭のこのスロットに何でも入れてしまう
        // = モニター連携カードを shift クリックするとメモリーカード側に入る (2026-08-29 報告)。
        addSlot(new Slot(blockEntity, 0, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem;
            }
        });
        // Slot 1: モニター連携カードスロット (CSS レイアウトで位置自動同期、JsonLayoutScreen.syncSlotPositions 経由)
        addSlot(new Slot(blockEntity, 1, 0, 0) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem;
            }
        });
        // Slot 2: 時刻表書き出し 入力 (= 空 create:schedule のみ)。詳細ビュー時のみ有効化。
        addSlot(new Slot(blockEntity, 2, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return isBlankSchedule(stack); }
            @Override public boolean isActive() { return exportSlotActive(blockEntity); }
        });
        // Slot 3: 時刻表書き出し 出力 (= 書込済み、 取り出しのみ)。
        addSlot(new Slot(blockEntity, 3, 0, 0) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean isActive() { return exportSlotActive(blockEntity); }
        });

        // Player inventory (CSSレイアウトで位置自動同期)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 0, 0));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 0, 0));
        }
    }

    private static ManagementComputerBlockEntity getBlockEntity(Inventory inv, FriendlyByteBuf data) {
        BlockPos pos = data.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof ManagementComputerBlockEntity mcbe) return mcbe;
        throw new IllegalStateException("Block entity at " + pos + " is not ManagementComputerBlockEntity");
    }

    public ManagementComputerBlockEntity getBlockEntity() { return blockEntity; }

    /** client: 詳細ビュー表示中のみ書き出しスロットを有効化 (描画/hover/click を gate)。 screen が毎フレーム設定。 */
    public static boolean exportSlotsVisible = false;
    private static boolean exportSlotActive(ManagementComputerBlockEntity be) {
        var lvl = be.getLevel();
        if (lvl != null && !lvl.isClientSide()) return true; // server は常時有効
        return exportSlotsVisible;
    }
    /** create:schedule で、まだ schedule 未書込 (= 空) のアイテムか。
     *  定義は BlockEntity 側 1 本 (mayPlace / beSlotFor / canPlaceItem がずれないように)。 */
    private static boolean isBlankSchedule(ItemStack stack) {
        return ManagementComputerBlockEntity.isBlankSchedule(stack);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        // 9000: プライベートモード切替
        if (id == 9000) { blockEntity.togglePrivateMode(); return true; }
        // 9001: 選択列車を緊急停止
        if (id == 9001) {
            for (UUID tid : blockEntity.getSelectedTrains()) {
                blockEntity.emergencyStop(tid);
            }
            return true;
        }
        // 9002: 選択列車の運行再開
        if (id == 9002) {
            for (UUID tid : blockEntity.getSelectedTrains()) {
                blockEntity.resumeTrain(tid);
            }
            return true;
        }
        // 10000-10999: 色設定 (colorIndex * 100 + presetIndex), presetIndex 99 = reset
        String[] colorKeys = ManagementComputerBlockEntity.getColorKeys();
        String[] presets = {"#4fc3f7", "#80deea", "#ff8a65", "#ffc107", "#66bb6a",
                "#ef5350", "#ab47bc", "#ffffff", "#888888", "#555555", "#444444", "#333333"};
        if (id >= 10000 && id < 11000) {
            int encoded = id - 10000;
            int colorIdx = encoded / 100;
            int presetIdx = encoded % 100;
            if (colorIdx >= 0 && colorIdx < colorKeys.length) {
                if (presetIdx == 99) {
                    blockEntity.setColor(colorKeys[colorIdx], "");
                } else if (presetIdx >= 0 && presetIdx < presets.length) {
                    blockEntity.setColor(colorKeys[colorIdx], presets[presetIdx]);
                }
            }
            return true;
        }
        // 11000: 全リセット
        if (id == 11000) {
            for (String key : colorKeys) blockEntity.setColor(key, "");
            return true;
        }
        // 20500: 時刻表編集適用（pendingScheduleNbt はBlockEntityに事前セット済み）
        if (id == 20500) {
            blockEntity.applyPendingSchedule();
            return true;
        }
        // 20600: 「すべて書き出し」トグル
        if (id == 20600) {
            blockEntity.toggleExportAll();
            return true;
        }
        return false;
    }

    /** BE スロット番号。 レイアウトの並び順ではなく Container の index。 */
    public static final int SLOT_MEMORY_CARD = 0;
    public static final int SLOT_MONITOR_CARD = 1;
    public static final int SLOT_EXPORT_IN = 2;
    public static final int SLOT_EXPORT_OUT = 3;

    /**
     * shift クリックでこのアイテムが入るべき BE スロット。 該当が無ければ -1。
     *
     * <p>各スロットの {@code mayPlace} と<b>同じ述語</b>をここに 1 本で持つ。
     * 書き出し出力 ({@link #SLOT_EXPORT_OUT}) は取り出し専用なので行き先にならない。
     */
    static int beSlotFor(ItemStack stack) {
        if (stack.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem) {
            return SLOT_MEMORY_CARD;
        }
        if (stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem) {
            return SLOT_MONITOR_CARD;
        }
        if (isBlankSchedule(stack)) return SLOT_EXPORT_IN;
        return -1;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        // メモリーカードを shift クリックで抜くと、 moveItemStackTo が live stack を split して
        // count 0 にしてから setByPlayer が走るため、 Container.setItem 側の
        // flushSettingsToCard() が「もう空」と見て何も書けない。 split の前に流し込む
        // (独立レビュー 2026-08-29 の指摘。 removeItem / clearContent / onRemove は正常)。
        if (index == 0 && blockEntity != null) blockEntity.flushSettingsToCard();
        // Slot 0..3 = BE slots (memory/monitor card + 書き出し in/out)、Slot 4..30 = inventory、Slot 31..39 = hotbar
        if (index < 4) {
            // BE スロット → プレイヤーインベントリ全体
            if (!moveItemStackTo(stack, 4, 40, true)) return ItemStack.EMPTY;
        } else {
            // プレイヤー → BE: **アイテム種別で行き先を決める** (鉄道管理ブロックと同じ形)。
            //
            // 範囲スキャン (moveItemStackTo(stack, 0, 4, false)) に任せてはいけない。
            // vanilla の moveItemStackTo は 2 段構成で、 **先の「既存スタックへの統合」段は
            // mayPlace を一切見ない** (AbstractContainerMenu l.637-663)。 そのため
            // slot 0 に既に同種のスタック可能アイテムが入っていると、 mayPlace を付けても
            // そこへ吸い込まれ続ける。 旧版の不具合で slot 0 に空の時刻表が入ってしまった
            // ワールドでは、 mayPlace だけでは症状が直らない (独立レビュー 2026-08-29)。
            int target = beSlotFor(stack);
            boolean moved = target >= 0 && moveItemStackTo(stack, target, target + 1, false);
            if (!moved) {
                // 行き先が無い / 埋まっている → インベントリ ↔ ホットバーの既定挙動を保つ
                if (index < 31) {
                    if (!moveItemStackTo(stack, 31, 40, false)) return ItemStack.EMPTY;
                } else {
                    if (!moveItemStackTo(stack, 4, 31, false)) return ItemStack.EMPTY;
                }
            }
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        // SECURITY (TSU-AUTH-001): open 中に owner が private 化した場合、非 owner の
        // 既存 menu を再認可で閉じる (open 時のみの canAccess では TOCTOU が残る)。
        return stillValid(this.access, player, blockEntity.getBlockState().getBlock())
                && blockEntity.canAccess(player);
    }
}
