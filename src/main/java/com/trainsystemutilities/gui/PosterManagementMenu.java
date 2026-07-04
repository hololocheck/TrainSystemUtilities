package com.trainsystemutilities.gui;

import com.trainsystemutilities.blockentity.PosterManagementBlockEntity;
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

public class PosterManagementMenu extends AbstractContainerMenu {

    private final PosterManagementBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    public PosterManagementMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, data));
    }

    public PosterManagementMenu(int containerId, Inventory playerInventory, PosterManagementBlockEntity blockEntity) {
        super(ModMenuTypes.POSTER_MANAGEMENT_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        // Monitor link card slot (slot 0)
        addSlot(new Slot(blockEntity, 0, 8, 8) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem;
            }
            @Override
            public int getMaxStackSize() { return 1; }
        });

        // Player inventory (3 rows)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }
    }

    private static PosterManagementBlockEntity getBlockEntity(Inventory inv, FriendlyByteBuf data) {
        BlockPos pos = data.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof PosterManagementBlockEntity pbe) return pbe;
        throw new IllegalStateException("Block entity at " + pos + " is not PosterManagementBlockEntity");
    }

    public PosterManagementBlockEntity getBlockEntity() { return blockEntity; }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        // 0: モニターオン/オフトグル
        if (id == 0) {
            blockEntity.toggleMonitorEnabled();
            return true;
        }
        // 6: フィットモード切替
        if (id == 6) { blockEntity.toggleFitToMonitor(); return true; }
        // 7: 1枚のみでアニメーション切替
        if (id == 7) { blockEntity.toggleAnimateSingle(); return true; }
        // 8: プライベートモード切替
        if (id == 8) { blockEntity.togglePrivateMode(); return true; }
        // 500-599: 画像有効/無効切替
        if (id >= 500 && id < 600) { blockEntity.toggleImageEnabled(id - 500); return true; }
        // 10: アニメーション種類を次に変更
        if (id == 10) {
            var types = PosterManagementBlockEntity.AnimationType.values();
            int current = blockEntity.getAnimationType().ordinal();
            blockEntity.setAnimationType(types[(current + 1) % types.length]);
            return true;
        }
        // 20-30: アニメーション種類を直接指定（ordinal）
        if (id >= 20 && id < 20 + PosterManagementBlockEntity.AnimationType.values().length) {
            blockEntity.setAnimationType(PosterManagementBlockEntity.AnimationType.values()[id - 20]);
            return true;
        }
        // 2: スライド間隔+1秒
        if (id == 2) {
            blockEntity.setSlideInterval(Math.min(60, blockEntity.getSlideInterval() + 1));
            return true;
        }
        // 3: スライド間隔-1秒
        if (id == 3) {
            blockEntity.setSlideInterval(Math.max(1, blockEntity.getSlideInterval() - 1));
            return true;
        }
        // 4: アニメーション速度+0.1秒
        if (id == 4) {
            blockEntity.setAnimationDuration(Math.min(15, blockEntity.getAnimationDuration() + 0.1f));
            return true;
        }
        // 5: アニメーション速度-0.1秒
        if (id == 5) {
            blockEntity.setAnimationDuration(Math.max(0.1f, blockEntity.getAnimationDuration() - 0.1f));
            return true;
        }
        // 1: toggle batch apply
        if (id == 1) { blockEntity.toggleBatchApply(); return true; }
        // 100+: 画像削除
        if (id >= 100 && id < 200) { int idx = id - 100; if (idx < blockEntity.getImageIds().size()) blockEntity.removeImage(idx); return true; }
        // 300+: 画像を上に移動
        if (id >= 300 && id < 400) { int idx = id - 300; if (idx > 0 && idx < blockEntity.getImageIds().size()) blockEntity.moveImageUp(idx); return true; }
        // 400+: 画像を下に移動
        if (id >= 400 && id < 500) { int idx = id - 400; if (idx < blockEntity.getImageIds().size() - 1) blockEntity.moveImageDown(idx); return true; }

        // === モニター設定（鉄道管理ブロックと同じID体系）===
        // 1002-1101: global track number
        if (id >= 1002 && id <= 1101) { blockEntity.setGlobalTrackNumber(id - 1002); return true; }
        // 1102-1199: global track font size
        if (id >= 1102 && id <= 1199) { blockEntity.setGlobalTrackFontSize(id - 1102); return true; }
        // 1200-1499: per-group track number
        if (id >= 1200 && id < 1500) { int e = id - 1200; blockEntity.setTrackNumberForGroup(e / 100, e % 100); return true; }
        // 1500-1999: per-group track font size
        if (id >= 1500 && id < 2000) { int e = id - 1500; blockEntity.setTrackFontSizeForGroup(e / 100, e % 100); return true; }
        // 2000: toggle global track position
        if (id == 2000) { blockEntity.setGlobalTrackPosition(blockEntity.getGlobalTrackPosition() == 0 ? 1 : 0); return true; }
        // 2001-2099: per-group track position
        if (id >= 2001 && id < 2100) { int gi = id - 2001; var g = blockEntity.getMonitorGroups(); if (gi < g.size()) blockEntity.setTrackPositionForGroup(gi, g.get(gi).trackPosition() == 0 ? 1 : 0); return true; }
        // 3002-3101: global back track number
        if (id >= 3002 && id <= 3101) { blockEntity.setGlobalBackTrackNumber(id - 3002); return true; }
        // 3102-3199: global back font size
        if (id >= 3102 && id <= 3199) { blockEntity.setGlobalBackTrackFontSize(id - 3102); return true; }
        // 3200-3499: per-group back track number
        if (id >= 3200 && id < 3500) { int e = id - 3200; blockEntity.setBackTrackNumberForGroup(e / 100, e % 100); return true; }
        // 3500-3999: per-group back font size
        if (id >= 3500 && id < 4000) { int e = id - 3500; blockEntity.setBackTrackFontSizeForGroup(e / 100, e % 100); return true; }
        // 4000: toggle global back position
        if (id == 4000) { blockEntity.setGlobalBackTrackPosition(blockEntity.getGlobalBackTrackPosition() == 0 ? 1 : 0); return true; }
        // 4001-4099: per-group back position
        if (id >= 4001 && id < 4100) { int gi = id - 4001; var g = blockEntity.getMonitorGroups(); if (gi < g.size()) blockEntity.setBackTrackPositionForGroup(gi, g.get(gi).backTrackPosition() == 0 ? 1 : 0); return true; }
        // 5000: toggle global clock visible
        if (id == 5000) { blockEntity.setGlobalClockVisible(blockEntity.getGlobalClockVisible() == 1 ? 0 : 1); return true; }
        // 5001-5099: per-group clock visible
        if (id >= 5001 && id < 5100) { int gi = id - 5001; var g = blockEntity.getMonitorGroups(); if (gi < g.size()) blockEntity.setClockVisibleForGroup(gi, g.get(gi).clockVisible() == 1 ? 0 : 1); return true; }
        // 5100-5199: global clock font size
        if (id >= 5100 && id < 5200) { blockEntity.setGlobalClockFontSize(id - 5100); return true; }
        // 5200-5499: per-group clock font size
        if (id >= 5200 && id < 5500) { int e = id - 5200; blockEntity.setClockFontSizeForGroup(e / 100, e % 100); return true; }
        // 6000: toggle global back clock visible
        if (id == 6000) { blockEntity.setGlobalBackClockVisible(blockEntity.getGlobalBackClockVisible() == 1 ? 0 : 1); return true; }
        // 6001-6099: per-group back clock visible
        if (id >= 6001 && id < 6100) { int gi = id - 6001; var g = blockEntity.getMonitorGroups(); if (gi < g.size()) blockEntity.setBackClockVisibleForGroup(gi, g.get(gi).backClockVisible() == 1 ? 0 : 1); return true; }
        // 6100-6199: global back clock font size
        if (id >= 6100 && id < 6200) { blockEntity.setGlobalBackClockFontSize(id - 6100); return true; }
        // 6200-6499: per-group back clock font size
        if (id >= 6200 && id < 6500) { int e = id - 6200; blockEntity.setBackClockFontSizeForGroup(e / 100, e % 100); return true; }

        return false;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == 0) {
            if (!moveItemStackTo(stack, 1, 37, true)) return ItemStack.EMPTY;
        } else if (stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else if (index < 28) {
            if (!moveItemStackTo(stack, 28, 37, false)) return ItemStack.EMPTY;
        } else {
            if (!moveItemStackTo(stack, 1, 28, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, blockEntity.getBlockState().getBlock());
    }
}
