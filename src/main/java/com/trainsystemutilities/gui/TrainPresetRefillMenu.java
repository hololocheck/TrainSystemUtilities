package com.trainsystemutilities.gui;

import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.registry.ModMenuTypes;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 列車プリセットツール 粘着剤タンク補充メニュー。
 *
 * - 入力スロット 1 個 (slot 0): スライムボール / スライムブロックを受け付ける
 *   設定された瞬間に server 側でタンクへ加算 + 余りを slot に残す
 * - プレイヤーインベントリ slot 1..36: 通常通り
 */
public class TrainPresetRefillMenu extends AbstractContainerMenu {

    private final Container inputContainer;
    private final Player player;

    /** Server / Client 両方で使えるコンストラクタ (ファクトリ用) */
    public TrainPresetRefillMenu(int containerId, Inventory playerInv) {
        super(ModMenuTypes.TRAIN_PRESET_REFILL_MENU.get(), containerId);
        this.player = playerInv.player;
        this.inputContainer = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                onInputChanged();
            }
        };

        // 入力スロット (画面の中央あたり)
        addSlot(new InputSlot(inputContainer, 0, 80, 32));

        // プレイヤーインベントリ (3 行 × 9 列)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 70 + row * 18));
            }
        }
        // ホットバー
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 128));
        }
    }

    /** 入力スロットの内容変化時: server 側でタンクへ加算、消費した分は slot から減らす。 */
    private void onInputChanged() {
        if (player == null || player.level().isClientSide()) return;
        ItemStack tool = findHeldTool(player);
        if (tool.isEmpty()) return;
        ItemStack input = inputContainer.getItem(0);
        if (input.isEmpty()) return;

        int perItem = 0;
        if (input.is(Items.SLIME_BALL)) perItem = TrainPresetToolItem.GLUE_PER_SLIME_BALL;
        else if (input.is(Items.SLIME_BLOCK)) perItem = TrainPresetToolItem.GLUE_PER_SLIME_BLOCK;
        if (perItem == 0) return;

        // タンクが満タン or 1 個分の余裕もない場合はそのまま (拒否)
        int remain = TrainPresetToolItem.GLUE_TANK_MAX
                - TrainPresetToolItem.getGlueTank(tool);
        if (remain < perItem) return;

        boolean changed = false;
        while (input.getCount() > 0
                && TrainPresetToolItem.GLUE_TANK_MAX
                    - TrainPresetToolItem.getGlueTank(tool) >= perItem) {
            int actual = TrainPresetToolItem.addGlueTank(tool, perItem);
            if (actual <= 0) break;
            input.shrink(1);
            changed = true;
        }
        if (changed) {
            // ツールを持っているスロットを「変更あり」とマーク → メニュー sync で
            // クライアントに新しい DataComponent (タンク値) が届く。
            player.getInventory().setChanged();
            broadcastChanges();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
        }
    }

    /** 入力スロット専用 (スライムのみ受け付け、タンクが満タンならブロック)。 */
    private static class InputSlot extends Slot {
        InputSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }
        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(Items.SLIME_BALL) || stack.is(Items.SLIME_BLOCK);
        }
        @Override
        public int getMaxStackSize() { return 64; }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == 0) {
            // 入力スロット → プレイヤーインベントリ
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            // プレイヤーインベントリ → 入力スロット (スライム類のみ)
            if (stack.is(Items.SLIME_BALL) || stack.is(Items.SLIME_BLOCK)) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
            } else {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return !findHeldTool(player).isEmpty();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 入力スロットに残ったアイテムをプレイヤーに返す
        if (!player.level().isClientSide()) {
            ItemStack remaining = inputContainer.removeItemNoUpdate(0);
            if (!remaining.isEmpty()) {
                if (!player.getInventory().add(remaining)) {
                    player.drop(remaining, false);
                }
            }
        }
    }

    private static ItemStack findHeldTool(Player player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
