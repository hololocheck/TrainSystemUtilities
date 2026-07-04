package com.trainsystemutilities.gui;

import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.electrification.item.WireSpoolItem;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.registry.ModMenuTypes;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 架線接続ツール 架線スプール装填メニュー (= 架線設定画面のインラインロードスロット)。
 *
 * <p>列車プリセットの粘着剤タンク補充 ({@link TrainPresetRefillMenu}) と同パターン:
 * <ul>
 *   <li>入力スロット (slot 0): 架線スプールを受け付け、設定された瞬間にツール内タンクへ充填。</li>
 *   <li>プレイヤーインベントリ slot 1..36 は通常通り。</li>
 * </ul>
 * スロットの実座標は JSON layout (= wire-connector.json) の {@code isSlot} 要素が
 * 文書順で上書きするため、ここでの addSlot 座標は placeholder。
 */
public class WireConnectorMenu extends AbstractContainerMenu {

    private final Container inputContainer;
    private final Player player;

    public WireConnectorMenu(int containerId, Inventory playerInv) {
        super(ModMenuTypes.WIRE_CONNECTOR_MENU.get(), containerId);
        this.player = playerInv.player;
        this.inputContainer = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                onInputChanged();
            }
        };

        // 入力スロット (slot 0)
        addSlot(new InputSlot(inputContainer, 0, 8, 8));
        // プレイヤーインベントリ (3 行 × 9 列)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 30 + row * 18));
            }
        }
        // ホットバー
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 90));
        }
    }

    /** 入力スロットに架線スプールが入ったら、ツール内タンクへ充填 (server-side のみ)。 */
    private void onInputChanged() {
        if (player == null || player.level().isClientSide()) return;
        ItemStack tool = findHeldTool(player);
        if (tool.isEmpty()) return;
        ItemStack input = inputContainer.getItem(0);
        if (input.isEmpty() || !input.is(ModItems.WIRE_SPOOL.get())) return;

        boolean changed = false;
        while (input.getCount() > 0
                && WireConnectorItem.readWireTank(tool) < WireConnectorItem.WIRE_TANK_MAX) {
            int actual = WireConnectorItem.addWireTank(tool, WireSpoolItem.METERS_PER_SPOOL);
            if (actual <= 0) break;
            input.shrink(1);
            changed = true;
        }
        if (changed) {
            player.getInventory().setChanged();
            broadcastChanges();
            if (player.containerMenu != null) player.containerMenu.broadcastChanges();
        }
    }

    /** 入力スロット専用 (架線スプールのみ受け付け)。 */
    private static class InputSlot extends Slot {
        InputSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }
        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.is(ModItems.WIRE_SPOOL.get());
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
            // プレイヤーインベントリ → 入力スロット (架線スプールのみ)
            if (stack.is(ModItems.WIRE_SPOOL.get())) {
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
        return belugalab.tsu.api.HeldTools.find(player, ModItems.WIRE_CONNECTOR.get());
    }
}
