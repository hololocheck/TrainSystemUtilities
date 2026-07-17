package com.trainsystemutilities.gui;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
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

public class RailwayManagementMenu extends AbstractContainerMenu {

    private final RailwayManagementBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    public RailwayManagementMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, data));
    }

    public RailwayManagementMenu(int containerId, Inventory playerInventory, RailwayManagementBlockEntity blockEntity) {
        super(ModMenuTypes.RAILWAY_MANAGEMENT_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        // Slot order MUST match the order of isSlot:true elements in
        // assets/trainsystemutilities/layouts/railway-management.json (JsonLayoutScreen
        // walks the JSON tree and writes positions into slots in that order).
        // → monitor card (1) + player inventory (36) → 37 isSlot elements.
        // The 18 announcement slots come AFTER the JSON-synced ones; their positions
        // are managed manually by the screen when the announcement popup opens/closes.

        // Monitor link card slot (BE slot 0) - fixed pos in main dialog
        addSlot(new Slot(blockEntity, 0, 8, 8) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem;
            }
            @Override public int getMaxStackSize() { return 1; }
        });

        // Player inventory (3 rows + hotbar)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 142));
        }

        // Phase 18: アナウンス設定 popup 内のスロット (BE slot 1..18)。Menu index 37..54。
        // 初期位置は off-screen。Screen 側で popup 開閉に応じて x/y を上書きする。
        addSlot(new AnnouncementSlot(blockEntity, 1, com.trainsystemutilities.item.TrainDetectionCardItem.class, true, false, false));
        addSlot(new AnnouncementSlot(blockEntity, 2, null, false, true, false));
        for (int i = 0; i < com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES; i++) {
            addSlot(new AnnouncementSlot(blockEntity, 3 + i, null, false, false, true));
        }
        // Phase 21: ホームドア用 メモリーカードスロット (BE slot 19)、 Menu index 55。
        addSlot(new ScreenDoorCardSlot(blockEntity,
                3 + com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES));
    }

    /** Menu slot index for the first announcement slot (after monitor + 36 player inv). */
    public static final int ANNOUNCEMENT_SLOT_BASE = 1 + 36;
    public static final int SLOT_DETECTION_CARD = ANNOUNCEMENT_SLOT_BASE;       // 37
    public static final int SLOT_RANGE_BOARD = ANNOUNCEMENT_SLOT_BASE + 1;      // 38
    public static final int SLOT_MEDIA_BASE = ANNOUNCEMENT_SLOT_BASE + 2;       // 39
    public static final int SLOT_SCREEN_DOOR_CARD = SLOT_MEDIA_BASE
            + com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES;   // 55

    /**
     * アナウンス設定 popup 内のスロット。popup が閉じている間は off-screen に
     * 移動された状態で {@link #isActive()} が false を返し、player の操作を受け付けない。
     */
    private static class AnnouncementSlot extends Slot {
        private final Class<?> requiredItemClass;
        private final boolean rangeBoard;
        private final boolean recordingMedium;

        AnnouncementSlot(RailwayManagementBlockEntity be, int beSlot,
                         Class<?> requiredItemClass, boolean detectionCard,
                         boolean rangeBoard, boolean recordingMedium) {
            super(be, beSlot, -1000, -1000); // 初期: off-screen
            this.requiredItemClass = detectionCard ? com.trainsystemutilities.item.TrainDetectionCardItem.class : requiredItemClass;
            this.rangeBoard = rangeBoard;
            this.recordingMedium = recordingMedium;
        }

        @Override public boolean mayPlace(ItemStack stack) {
            if (requiredItemClass != null) return requiredItemClass.isInstance(stack.getItem());
            if (rangeBoard) return com.trainsystemutilities.compat.sas.SasIntegration.isRangeBoard(stack);
            if (recordingMedium) return com.trainsystemutilities.compat.sas.SasIntegration.isRecordingMedium(stack);
            return false;
        }
        @Override public int getMaxStackSize() { return 1; }

        /** Slot は popup が開いている (= x >= 0) ときだけ有効。 */
        @Override public boolean isActive() { return this.x >= 0; }
    }

    /** ホームドア用 メモリーカードスロット (= screen_door_group type のみ受付)。 */
    private static class ScreenDoorCardSlot extends Slot {
        ScreenDoorCardSlot(RailwayManagementBlockEntity be, int beSlot) {
            super(be, beSlot, -1000, -1000); // 初期: off-screen
        }
        @Override public boolean mayPlace(ItemStack stack) {
            return isScreenDoorGroupMemoryCard(stack);
        }
        @Override public int getMaxStackSize() { return 1; }
        @Override public boolean isActive() { return this.x >= 0; }
    }

    private static boolean isScreenDoorGroupMemoryCard(ItemStack stack) {
        if (!(stack.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem)) return false;
        if (!stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) return false;
        var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        return com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                .equals(tag.getString("Type"));
    }

    private static RailwayManagementBlockEntity getBlockEntity(Inventory inv, FriendlyByteBuf data) {
        BlockPos pos = data.readBlockPos();
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        if (be instanceof RailwayManagementBlockEntity rmbe) return rmbe;
        throw new IllegalStateException("Block entity at " + pos + " is not RailwayManagementBlockEntity");
    }

    public RailwayManagementBlockEntity getBlockEntity() { return blockEntity; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Slot layout:
        //   0       = monitor card
        //   1..36   = player inventory (3 rows + hotbar)
        //   37      = detection card
        //   38      = range board
        //   39..54  = announcement media (16)
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index == 0 || index >= ANNOUNCEMENT_SLOT_BASE) {
            // BE slot → player inventory
            if (!moveItemStackTo(stack, 1, 37, true)) return ItemStack.EMPTY;
        } else {
            // Player → BE: 適合スロットを探して入れる
            if (stack.getItem() instanceof com.trainsystemutilities.item.MonitorLinkCardItem) {
                if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
            } else if (stack.getItem() instanceof com.trainsystemutilities.item.TrainDetectionCardItem) {
                if (!moveItemStackTo(stack, SLOT_DETECTION_CARD, SLOT_DETECTION_CARD + 1, false)) return ItemStack.EMPTY;
            } else if (com.trainsystemutilities.compat.sas.SasIntegration.isRangeBoard(stack)) {
                if (!moveItemStackTo(stack, SLOT_RANGE_BOARD, SLOT_RANGE_BOARD + 1, false)) return ItemStack.EMPTY;
            } else if (isScreenDoorGroupMemoryCard(stack)) {
                if (!moveItemStackTo(stack, SLOT_SCREEN_DOOR_CARD, SLOT_SCREEN_DOOR_CARD + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (com.trainsystemutilities.compat.sas.SasIntegration.isRecordingMedium(stack)) {
                if (!moveItemStackTo(stack, SLOT_MEDIA_BASE,
                        SLOT_MEDIA_BASE + com.trainsystemutilities.announce.AnnouncementConfig.MAX_ENTRIES,
                        false)) return ItemStack.EMPTY;
            } else {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == 0) {
            blockEntity.toggleMonitorEnabled();
            return true;
        }
        if (id == 1) {
            blockEntity.toggleBatchApply();
            return true;
        }
        // 2-101: set global track number (id - 2)
        if (id >= 2 && id <= 101) {
            blockEntity.setGlobalTrackNumber(id - 2);
            return true;
        }
        // 102-199: set global track font size (id - 102)
        if (id >= 102 && id <= 199) {
            blockEntity.setGlobalTrackFontSize(id - 102);
            return true;
        }
        // 200-499: per-group track number (groupIndex * 100 + trackNumber)
        if (id >= 200 && id < 500) {
            int encoded = id - 200;
            int groupIndex = encoded / 100;
            int trackNum = encoded % 100;
            blockEntity.setTrackNumberForGroup(groupIndex, trackNum);
            return true;
        }
        // 500+: per-group track font size (groupIndex * 100 + fontSize)
        if (id >= 500 && id < 1000) {
            int encoded = id - 500;
            int groupIndex = encoded / 100;
            int fontSize = encoded % 100;
            blockEntity.setTrackFontSizeForGroup(groupIndex, fontSize);
            return true;
        }
        // 1000: toggle global track position
        if (id == 1000) {
            blockEntity.setGlobalTrackPosition(blockEntity.getGlobalTrackPosition() == 0 ? 1 : 0);
            return true;
        }
        // 1001-1099: toggle per-group track position
        if (id >= 1001 && id < 1100) {
            int groupIndex = id - 1001;
            var groups = blockEntity.getMonitorGroups();
            if (groupIndex >= 0 && groupIndex < groups.size()) {
                int current = groups.get(groupIndex).trackPosition();
                blockEntity.setTrackPositionForGroup(groupIndex, current == 0 ? 1 : 0);
            }
            return true;
        }
        // 2000-2099: global back track number (id - 2000)
        if (id >= 2000 && id <= 2099) {
            blockEntity.setGlobalBackTrackNumber(id - 2000);
            return true;
        }
        // 2100-2199: global back font size (id - 2100)
        if (id >= 2100 && id <= 2199) {
            blockEntity.setGlobalBackTrackFontSize(id - 2100);
            return true;
        }
        // 2200-2499: per-group back track number (groupIndex * 100 + trackNum)
        if (id >= 2200 && id < 2500) {
            int encoded = id - 2200;
            int groupIndex = encoded / 100;
            int trackNum = encoded % 100;
            blockEntity.setBackTrackNumberForGroup(groupIndex, trackNum);
            return true;
        }
        // 2500-2999: per-group back font size (groupIndex * 100 + fontSize)
        if (id >= 2500 && id < 3000) {
            int encoded = id - 2500;
            int groupIndex = encoded / 100;
            int fontSize = encoded % 100;
            blockEntity.setBackTrackFontSizeForGroup(groupIndex, fontSize);
            return true;
        }
        // 3000: toggle global back position
        if (id == 3000) {
            blockEntity.setGlobalBackTrackPosition(blockEntity.getGlobalBackTrackPosition() == 0 ? 1 : 0);
            return true;
        }
        // 3001-3099: toggle per-group back position
        if (id >= 3001 && id < 3100) {
            int groupIndex = id - 3001;
            var groups = blockEntity.getMonitorGroups();
            if (groupIndex >= 0 && groupIndex < groups.size()) {
                int current = groups.get(groupIndex).backTrackPosition();
                blockEntity.setBackTrackPositionForGroup(groupIndex, current == 0 ? 1 : 0);
            }
            return true;
        }
        // 4000: toggle global clock visible
        if (id == 4000) {
            blockEntity.setGlobalClockVisible(blockEntity.getGlobalClockVisible() == 1 ? 0 : 1);
            return true;
        }
        // 4001-4099: per-group clock visible toggle
        if (id >= 4001 && id < 4100) {
            int groupIndex = id - 4001;
            var groups = blockEntity.getMonitorGroups();
            if (groupIndex >= 0 && groupIndex < groups.size()) {
                int current = groups.get(groupIndex).clockVisible();
                blockEntity.setClockVisibleForGroup(groupIndex, current == 1 ? 0 : 1);
            }
            return true;
        }
        // 4100-4199: global clock font size (id - 4100)
        if (id >= 4100 && id < 4200) {
            blockEntity.setGlobalClockFontSize(id - 4100);
            return true;
        }
        // 4200-4499: per-group clock font size (groupIndex*100 + fontSize)
        if (id >= 4200 && id < 4500) {
            int encoded = id - 4200;
            int groupIndex = encoded / 100;
            int fontSize = encoded % 100;
            blockEntity.setClockFontSizeForGroup(groupIndex, fontSize);
            return true;
        }
        // 5000: toggle global back clock visible
        if (id == 5000) {
            blockEntity.setGlobalBackClockVisible(blockEntity.getGlobalBackClockVisible() == 1 ? 0 : 1);
            return true;
        }
        // 5001-5099: per-group back clock visible toggle
        if (id >= 5001 && id < 5100) {
            int groupIndex = id - 5001;
            var groups = blockEntity.getMonitorGroups();
            if (groupIndex >= 0 && groupIndex < groups.size()) {
                int current = groups.get(groupIndex).backClockVisible();
                blockEntity.setBackClockVisibleForGroup(groupIndex, current == 1 ? 0 : 1);
            }
            return true;
        }
        // 5100-5199: global back clock font size (id - 5100)
        if (id >= 5100 && id < 5200) {
            blockEntity.setGlobalBackClockFontSize(id - 5100);
            return true;
        }
        // 5200-5499: per-group back clock font size (groupIndex*100 + fontSize)
        if (id >= 5200 && id < 5500) {
            int encoded = id - 5200;
            int groupIndex = encoded / 100;
            int fontSize = encoded % 100;
            blockEntity.setBackClockFontSizeForGroup(groupIndex, fontSize);
            return true;
        }
        // 9000: プライベートモード切替
        if (id == 9000) { blockEntity.togglePrivateMode(); return true; }
        // 10000-10999: 表面色設定, 20000-20999: 裏面色設定
        // (colorIndex * 100 + presetIndex), presetIndex 99 = reset
        String[] colorKeys = {"arrTime", "depTime", "stopInfo", "routeType", "stopSec", "trainName", "nextName", "sectionTitle", "countdown", "trackNumber"};
        String[] presets = {"#4fc3f7", "#80deea", "#ff8a65", "#ffc107", "#66bb6a",
                "#ef5350", "#ab47bc", "#ffffff", "#888888", "#555555", "#444444", "#333333"};
        if ((id >= 10000 && id < 11000) || (id >= 20000 && id < 21000)) {
            boolean isBack = id >= 20000;
            int encoded = id - (isBack ? 20000 : 10000);
            int colorIdx = encoded / 100;
            int presetIdx = encoded % 100;
            if (colorIdx >= 0 && colorIdx < colorKeys.length) {
                String key = (isBack ? "back." : "") + colorKeys[colorIdx];
                if (presetIdx == 99) {
                    blockEntity.setColor(key, "");
                } else if (presetIdx >= 0 && presetIdx < presets.length) {
                    blockEntity.setColor(key, presets[presetIdx]);
                }
            }
            return true;
        }
        // 11000: 表面全リセット, 21000: 裏面全リセット
        if (id == 11000 || id == 21000) {
            boolean isBack = id == 21000;
            for (String key : colorKeys) {
                blockEntity.setColor((isBack ? "back." : "") + key, "");
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        // SECURITY (TSU-AUTH-001): open 中に owner が private 化した場合、非 owner の
        // 既存 menu を再認可で閉じる (open 時のみの canAccess では TOCTOU が残る)。
        return stillValid(access, player, blockEntity.getBlockState().getBlock())
                && blockEntity.canAccess(player);
    }
}
