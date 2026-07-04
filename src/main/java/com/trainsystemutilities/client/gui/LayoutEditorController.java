package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.blockentity.MonitorLayoutPanel;

import java.util.ArrayList;
import java.util.List;

/**
 * モニターレイアウト編集 popup (Phase 9-G) の state を保持する controller。
 * {@code ManagementComputerScreenV2} から切り出し (god-class deep #2)。
 *
 * <p>編集中パネル list と選択 index・表示 flag を所有する。drag-and-drop / preview 描画 /
 * recommend builder / save は preview geometry・palette・networking と結合した描画責務ゆえ
 * screen 側に残し、{@link #getLayout()}(可変 list)と選択 accessor を参照する。
 */
public final class LayoutEditorController {

    private boolean open = false;
    private final List<MonitorLayoutPanel> layout = new ArrayList<>();
    private int selectedIndex = -1;

    public boolean isOpen() { return open; }
    public void open() { open = true; }
    public void close() { open = false; }

    /** 編集中パネル list (可変。drag/drop/render/save/recommend が直接操作する)。 */
    public List<MonitorLayoutPanel> getLayout() { return layout; }

    public int selectedIndex() { return selectedIndex; }
    public void select(int i) { selectedIndex = i; }
    public void clearSelection() { selectedIndex = -1; }

    /** 選択中パネルがあれば削除し選択解除。削除したら true (= DEL キー処理)。 */
    public boolean deleteSelected() {
        if (selectedIndex >= 0 && selectedIndex < layout.size()) {
            layout.remove(selectedIndex);
            selectedIndex = -1;
            return true;
        }
        return false;
    }
}
