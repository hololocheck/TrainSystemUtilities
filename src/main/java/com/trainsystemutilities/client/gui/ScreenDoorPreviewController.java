package com.trainsystemutilities.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * RailwayManagementScreenV2 のホームドア popup 3D preview を所有する controller
 * (god-class 分割 増分 1)。回転 (左 drag) / pan (右 drag) / zoom (wheel) の view 状態と、
 * メモリーカード (slot → carried fallback) からのメンバー読取・描画を 1 箇所に持つ。
 *
 * <p>既存 controller 慣行どおり: 状態は全所有・screen 側は hit 判定 (overlay2 座標変換に
 * 依存するため screen 在置) と 1:1 委譲のみ。card 取得の slot→carried fallback は
 * 従来 screen 内 3 箇所に重複していたため {@link #cardFrom} に一本化。
 */
final class ScreenDoorPreviewController {

    private static final float DEFAULT_ROT_Y = 0f;
    private static final float DEFAULT_ROT_X = 25f;
    private static final float DEFAULT_ZOOM = 3.0f;

    private float rotY = DEFAULT_ROT_Y;
    private float rotX = DEFAULT_ROT_X;
    private float zoom = DEFAULT_ZOOM;
    private float panX = 0f;
    private float panY = 0f;
    private int dragButton = -1; // -1=なし, 0=回転, 1=pan
    private double lastMouseX = 0;
    private double lastMouseY = 0;

    /** popup 再 open 時などに view を初期姿勢へ戻す。 */
    void resetView() {
        rotY = DEFAULT_ROT_Y;
        rotX = DEFAULT_ROT_X;
        zoom = DEFAULT_ZOOM;
        panX = 0f;
        panY = 0f;
    }

    /** preview 上での press (= 0:左=回転, 1:右=pan)。hit 判定は screen 側で済ませて呼ぶ。 */
    void beginDrag(int button, double mouseX, double mouseY) {
        dragButton = button;
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    /** drag 中の回転/pan 更新。消費したら true。 */
    boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (dragButton != button) return false;
        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        if (button == 0) {
            rotY += (float) dx * 0.6f;
            rotX += (float) dy * 0.6f;
            rotX = Math.max(-89f, Math.min(89f, rotX));
        } else if (button == 1) {
            panX += (float) dx;
            panY += (float) dy;
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        return true;
    }

    /** drag 終了。消費したら true。 */
    boolean mouseReleased(int button) {
        if (dragButton != button) return false;
        dragButton = -1;
        return true;
    }

    /** wheel zoom (preview 上で呼ぶ)。 */
    void zoomBy(double scrollY) {
        zoom = Math.max(0.2f, Math.min(30.0f, zoom * (scrollY > 0 ? 1.2f : 0.85f)));
    }

    /** ホームドア popup 内の 3D preview。 メモリーカードのメンバー BlockPos からブロックを取得して描画。
     *  slot + carried 両方を見る (= ユーザーが card を click で持ち上げ中も継続描画)。 */
    void draw(GuiGraphics g, int x, int y, int w, int h,
              Font font, AbstractContainerMenu menu, Level level, boolean colorPickerOpen) {
        if (colorPickerOpen) return;
        ItemStack card = cardFrom(menu);
        java.util.List<com.manta.api.preview.GuiBlock3DRenderer.Block3DEntry> entries =
                new java.util.ArrayList<>();
        if (!card.isEmpty()
                && card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
            net.minecraft.nbt.CompoundTag tag =
                    card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            if (com.trainsystemutilities.item.MemoryCardItem.TYPE_SCREEN_DOOR_GROUP
                    .equals(tag.getString("Type"))) {
                long[] members = readMembers(tag);
                if (level != null) {
                    for (long packed : members) {
                        net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.of(packed);
                        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
                        if (!st.isAir()) {
                            entries.add(new com.manta.api.preview.GuiBlock3DRenderer.Block3DEntry(p, st));
                        }
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            // メモリーカード未挿入 or chunk 未ロード等の場合は説明テキストのみ
            String msg = Component.translatable(card.isEmpty()
                    ? "tsu.rm.card_not_inserted" : "tsu.rm.block_loading").getString();
            int tw = font.width(msg);
            g.drawString(font, msg, x + (w - tw) / 2, y + h / 2 - 4, 0xFF888888, false);
            return;
        }
        com.manta.api.preview.GuiBlock3DRenderer.render(
                g, x, y, w, h, entries,
                rotY, rotX, zoom, panX, panY);
    }

    /** メモリーカードを slot から、無ければマウス carried から取る (旧 3 箇所重複の一本化)。 */
    static ItemStack cardFrom(AbstractContainerMenu menu) {
        ItemStack card = menu.slots.get(
                com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD).getItem();
        if (card.isEmpty()) {
            ItemStack carried = menu.getCarried();
            if (!carried.isEmpty()
                    && carried.getItem() instanceof com.trainsystemutilities.item.MemoryCardItem) {
                card = carried;
            }
        }
        return card;
    }

    static long[] readMembers(net.minecraft.nbt.CompoundTag tag) {
        net.minecraft.nbt.Tag raw = tag.get(
                com.trainsystemutilities.item.MemoryCardItem.TAG_MEMBERS);
        if (raw instanceof net.minecraft.nbt.LongArrayTag lat) {
            return lat.getAsLongArray();
        }
        if (raw instanceof net.minecraft.nbt.ListTag lt) {
            long[] arr = new long[lt.size()];
            for (int i = 0; i < lt.size(); i++) {
                if (lt.get(i) instanceof net.minecraft.nbt.LongTag longTag) {
                    arr[i] = longTag.getAsLong();
                }
            }
            return arr;
        }
        return new long[0];
    }
}
