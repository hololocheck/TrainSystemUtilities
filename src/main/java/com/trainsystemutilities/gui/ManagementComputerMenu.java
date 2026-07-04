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
        addSlot(new Slot(blockEntity, 0, 0, 0));
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
    /** create:schedule で、まだ schedule 未書込 (= 空) のアイテムか。 */
    private static boolean isBlankSchedule(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof com.simibubi.create.content.trains.schedule.ScheduleItem;
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        // Slot 0..3 = BE slots (memory/monitor card + 書き出し in/out)、Slot 4..30 = inventory、Slot 31..39 = hotbar
        if (index < 4) {
            // BE スロット → プレイヤーインベントリ全体
            if (!moveItemStackTo(stack, 4, 40, true)) return ItemStack.EMPTY;
        } else if (index < 31) {
            // インベントリ → BE スロット or ホットバー
            if (!moveItemStackTo(stack, 0, 4, false) && !moveItemStackTo(stack, 31, 40, false))
                return ItemStack.EMPTY;
        } else {
            // ホットバー → BE スロット or インベントリ
            if (!moveItemStackTo(stack, 0, 4, false) && !moveItemStackTo(stack, 4, 31, false))
                return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, blockEntity.getBlockState().getBlock());
    }
}
