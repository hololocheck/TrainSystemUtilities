package com.trainsystemutilities.client.gui;

import com.manta.api.controller.ColorPickerController;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.schedule.TrainTypes;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/** ManagementComputerScreenV2 の click / wheel / drag dispatcher 本体 (god-class 分割)。
 *  挙動は screen 在置時代と同一 — bodies は verbatim 移設で、screen メンバーは scr. 経由で参照する。
 *  値 (旧 getDynamic*) は Manta 7 Phase 4 から ManagementComputerPush が page へ書く。 */
final class ManagementComputerDispatch {

    private ManagementComputerDispatch() {}

    static boolean onElementDrag(ManagementComputerScreenV2 scr, String[] classes, String key, int mouseX, int mouseY,
                                 int elX, int elY, int elW, int elH, boolean pressed) {
        // The lists' thumbs, grabbed and dragged (Manta 2.87.0's dragThumb, R4.19.5).
        switch (key) {
            case "trains-thumb-drag": return scr.trainScroll.dragThumb(mouseY, pressed);
            case "sched-entries-thumb-drag": return scr.schedEntryScroll.dragThumb(mouseY, pressed);
            case "stations-thumb-drag": return scr.stationScroll.dragThumb(mouseY, pressed);
            case "tickets-thumb-drag": return scr.ticketScroll.dragThumb(mouseY, pressed);
            case "sched-share-thumb-drag": return scr.schedShareScroll.dragThumb(mouseY, pressed);
            case "sched-station-thumb-drag": return scr.schedEditor.dragStationThumb(mouseY, pressed);
            case "sched-edit-body-drag": return scr.schedEditor.dragEditThumb(mouseX, mouseY, pressed);
            default: break;
        }
        if ("cp-hue-bar".equals(key)) {
            scr.picker.setHueFromX(mouseX - elX, elW);
            return true;
        }
        if ("cp-sv-panel".equals(key)) {
            scr.picker.setSvFromXY(mouseX - elX, mouseY - elY, elW, elH);
            return true;
        }
        if ("map-pan".equals(key)) {
            if (pressed) {
                scr.mapRenderer.onPanDrag(mouseX, mouseY, true);
            } else if (scr.mapRenderer.wasClick(mouseX, mouseY)) {
                // #15: タップ (ドラッグでない) = 駅/列車アイコンのクリック
                var hit = scr.mapRenderer.hitTest(mouseX, mouseY);
                if (hit != null) scr.handleMapHit(hit);
            } else {
                scr.mapRenderer.onPanDrag(mouseX, mouseY, false);
            }
            return true;
        }
        // Layout editor: パレットタイルからの drag-and-drop で追加 (DragDropPalette に委譲)
        if ("layout-tile-drag".equals(key)) {
            if (pressed) {
                String type = null;
                for (String c : classes) {
                    if (c.startsWith("layout-tile-") && !"layout-tile-item".equals(c)) {
                        type = c.substring("layout-tile-".length());
                        break;
                    }
                }
                scr.palette.onPress(type);
            }
            scr.palette.update(mouseX, mouseY);
            return true;
        }
        // Layout editor: preview canvas のドラッグでパネル選択 + 移動。
        if ("layout-preview-drag".equals(key)) {
            if (scr.layoutPrevW <= 0 || scr.layoutPrevH <= 0) return true;
            // mouseX/Y は popup-local 座標。preview canvas の絶対座標を引いてキャンバス内座標に。
            int cx = mouseX - scr.layoutPrevX;
            int cy = mouseY - scr.layoutPrevY;
            if (pressed) {
                // クリック位置のパネルを選択 (一番上=後追加分から検査)
                scr.layoutEditor.clearSelection();
                for (int i = scr.layoutEditor.getLayout().size() - 1; i >= 0; i--) {
                    var p = scr.layoutEditor.getLayout().get(i);
                    int px = (int)(p.getX() * scr.layoutPrevW);
                    int py = (int)(p.getY() * scr.layoutPrevH);
                    int pw = Math.max(8, (int)(p.getWidth() * scr.layoutPrevW));
                    int ph = Math.max(8, (int)(p.getHeight() * scr.layoutPrevH));
                    if (com.manta.api.hud.Rects.contains(cx, cy, px, py, pw, ph)) {
                        scr.layoutEditor.select(i);
                        scr.layoutDragStartPanelX = p.getX();
                        scr.layoutDragStartPanelY = p.getY();
                        scr.layoutDragStartMouseX = mouseX;
                        scr.layoutDragStartMouseY = mouseY;
                        break;
                    }
                }
            } else if (scr.layoutEditor.selectedIndex() >= 0 && scr.layoutEditor.selectedIndex() < scr.layoutEditor.getLayout().size()) {
                var p = scr.layoutEditor.getLayout().get(scr.layoutEditor.selectedIndex());
                float dx = (float)((mouseX - scr.layoutDragStartMouseX) / (double) scr.layoutPrevW);
                float dy = (float)((mouseY - scr.layoutDragStartMouseY) / (double) scr.layoutPrevH);
                p.setX(scr.layoutDragStartPanelX + dx);
                p.setY(scr.layoutDragStartPanelY + dy);
            }
            return true;
        }
        // 列車詳細 popup の 3D モデル: 左ドラッグで回転 (shift で pan) — TrainModelRenderer へ委譲。
        if ("train-rotate".equals(key)) {
            scr.trainModel.onRotateDrag(mouseX, mouseY, pressed,
                    com.manta.api.hud.ModifierKeys.shift(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow()));
            return true;
        }
        return false;
    }

    static boolean onElementWheel(ManagementComputerScreenV2 scr, String[] classes, String key,
                                  int mouseX, int mouseY, double scrollY) {
        if ("map-zoom".equals(key)) {
            scr.mapRenderer.onZoomWheel(scrollY);
            return true;
        }
        if ("train-zoom".equals(key)) {
            scr.trainModel.onZoomWheel(scrollY);
            return true;
        }
        // Layout editor: preview 上のタイルを hover + wheel で拡大縮小 (中心アンカー)
        if ("layout-preview-wheel".equals(key)) {
            if (scr.layoutEditor.isOpen()) {
                int idx = scr.layoutPanelAt(mouseX, mouseY);
                if (idx >= 0) {
                    var p = scr.layoutEditor.getLayout().get(idx);
                    float f = scrollY > 0 ? 1.08f : 1f / 1.08f;
                    float cxN = p.getX() + p.getWidth() / 2f;
                    float cyN = p.getY() + p.getHeight() / 2f;
                    p.setWidth(p.getWidth() * f);
                    p.setHeight(p.getHeight() * f);
                    p.setX(cxN - p.getWidth() / 2f);   // setX/setY が bezel 内へ clamp
                    p.setY(cyN - p.getHeight() / 2f);
                    scr.layoutEditor.select(idx);
                }
            }
            return true;
        }
        // 列車種別: 値 hover + wheel で循環 (R4.13.0 / R4.13.0.8)。 値ピッカーなので非反転。
        if ("sched-type-val".equals(key)) {
            scr.cycleSelectedTrainType(scrollY > 0 ? 1 : -1);
            return true;
        }
        // Panel settings popup: 値 hover + wheel で増減 (R4.13.0)。 0 = 自動 (推奨)。
        if (key != null && key.startsWith("pset-")) {
            var pp = scr.psetPanel();
            if (pp != null) {
                int d = scrollY > 0 ? 1 : -1;
                switch (key) {
                    case "pset-font-val":        pp.setFontSize(ManagementComputerScreenV2.adjustPsetValue(pp.getFontSize(), d)); return true;
                    case "pset-maptext-val":     pp.setMapTextSize(ManagementComputerScreenV2.adjustPsetValue(pp.getMapTextSize(), d)); return true;
                    case "pset-trainicon-val":   pp.setTrainIconSize(ManagementComputerScreenV2.adjustPsetValue(pp.getTrainIconSize(), d)); return true;
                    case "pset-stationicon-val": pp.setStationIconSize(ManagementComputerScreenV2.adjustPsetValue(pp.getStationIconSize(), d)); return true;
                    case "pset-signalicon-val":  pp.setSignalIconSize(ManagementComputerScreenV2.adjustPsetValue(pp.getSignalIconSize(), d)); return true;
                }
            }
            return true;
        }
        int delta = scrollY > 0 ? -1 : 1;
        if ("trains-scroll".equals(key)) {
            scr.trainScroll.scroll(delta);
            return true;
        }
        if ("sched-list-scroll".equals(key)) {
            scr.schedListScroll.scroll(delta);
            return true;
        }
        if ("sched-entries-scroll".equals(key)) {
            scr.schedEntryScroll.scroll(delta);
            return true;
        }
        if ("sched-share-scroll".equals(key)) {
            scr.schedShareScroll.scroll(delta);
            return true;
        }
        if ("stations-list-scroll".equals(key)) {
            scr.stationScroll.scroll(delta);
            return true;
        }
        if ("tickets-list-scroll".equals(key)) {
            scr.ticketScroll.scroll(delta);  // ScrollViewport が clamp 内包 (§4.19)
            return true;
        }
        // Symbol editor field wheel-edit (wheel-up = increase) — controller 委譲
        if (scr.symEditor.handleWheel(key, scrollY)) return true;
        if ("sched-station-pick-scroll".equals(key)) {
            return scr.schedEditor.handleStationWheel(delta);
        }
        if ("sched-edit-body-scroll".equals(key)) {
            if (scr.schedEditor.handleWheel(mouseX, mouseY, delta)) return true;
        }
        return false;
    }

    static void onElementClick(ManagementComputerScreenV2 scr, String[] classes, int mouseX, int mouseY, int button) {
        // Phase 9: 4-arg を完全 override しているため base の hint/wiki 処理を明示呼び出し
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        // Monitor toggle (= track/knob + alias 旧名)
        if (scr.monitorToggle.handleClick(classes)) return;
        if (scr.exportAllToggle.handleClick(classes)) return;
        if (scr.showScheduleShare && scr.schedShareToggle.handleClick(classes, scr.schedShareRealIdx())) return;
        // 券売機タブ: 販売可トグル (repeat 行ごと)
        if (scr.ticketToggle.handleClick(classes, com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex())) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = scr.wikiPageId();
                if (pid != null && !pid.isEmpty()) {
                    com.manta.api.wiki.Wiki.open(pid);
                }
                return;
            }
        }
        // === Schedule editor (sub-dropdown chain + frame、controller 委譲) ===
        if (scr.schedEditor.handleClick(classes, mouseX, mouseY, scr.overlayX(), scr.overlayY(),
                scr::schedStationNames, scr::applyScheduleEdit, scr::clearOverlayAnimByClass)) return;
        // HSV picker (highest popup priority)
        if (scr.showColorPicker) {
            int ri = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("cp-close".equals(c) || "cp-close-btn".equals(c) || "mc-popup-close".equals(c)) {
                    scr.showColorPicker = false; return;
                }
                if ("cp-info-hex".equals(c)) { scr.focusField("hex"); return; }
                if ("cp-info-rgb".equals(c)) { scr.focusField("rgb"); return; }
                if ("cp-info-hsl".equals(c)) { scr.focusField("hsl"); return; }
                if ("cp-add".equals(c)) {
                    String hex = scr.currentPickerHex();
                    if (!scr.customColors.contains(hex)) scr.customColors.add(hex);
                    scr.symEditor.setColorCustom(hex);
                    return;
                }
                if ("cp-chip".equals(c) && ri >= 0 && ri < scr.customColors.size()) {
                    if (button == 1) {
                        scr.customColors.remove(ri);
                    } else {
                        String picked = scr.customColors.get(ri);
                        scr.symEditor.setColorCustom(picked);
                        scr.setPickerFromColor(picked);
                    }
                    return;
                }
            }
            // fallthrough → main GUI
        }
        // Symbol delete confirm popup (共通ダイアログ)
        if (scr.symbolDelete.handleClick(classes, idx -> {
            if (idx != null) scr.confirmDeleteSymbol(idx);
        })) return;
        // Symbol editor popup (save/delete/cp-btn は screen helper 結合ゆえ screen、他は controller 委譲)
        if (scr.symEditor.isOpen()) {
            for (String c : classes) {
                if ("sym-edit-save".equals(c)) {
                    scr.saveEditedSymbol();
                    scr.symEditor.close();
                    return;
                }
                if ("sym-edit-delete".equals(c) && scr.symEditor.getIndex() >= 0) {
                    scr.symbolDelete.ask(scr.symEditor.getIndex());
                    scr.symEditor.close();
                    return;
                }
                if ("sym-edit-cp-btn".equals(c)) {
                    scr.showColorPicker = true;
                    scr.setPickerFromColor(scr.symEditor.getColor());
                    return;
                }
            }
            if (scr.symEditor.handleClick(classes, () -> scr.customColors)) return;
            // fallthrough → main GUI
        }
        // Station assign dropdown (controller 委譲)
        if (scr.stationAssign.isOpen()) {
            int ri = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
            if (scr.stationAssign.handleClick(classes, ri, () -> scr.be().getLineSymbols(),
                    (n, p, sym) -> scr.assignSymbolOnServer(n, p, sym == null ? null : sym.getId()))) return;
            // fallthrough → main GUI
        }
        // Monitor color settings popup — ColorTargetController に dispatch
        if (scr.showMonitorColorSettings) {
            int dIdx = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
            // popup-close は controller では false を返す (popup 自体を閉じる責務は screen)
            for (String c : classes) {
                if ("mcol-popup-close".equals(c) || "mc-popup-close".equals(c)) {
                    scr.showMonitorColorSettings = false;
                    scr.monitorColorPopup.resetTransientState();
                    return;
                }
            }
            // The target list unfolds / folds by the controller's DropdownMotion (every press is played).
            if (scr.monitorColorPopup.handleClick(classes, dIdx)) {
                return;
            }
            // 一致なし → main GUI ハンドラに落とす
        }
        // Layout editor popup clicks
        if (scr.layoutEditor.isOpen()) {
            // タイル上で中ボタン押し込み → パネル機能別設定 popup (overlay2)
            if (button == 2) {
                for (String c : classes) {
                    if ("layout-preview".equals(c)) {
                        int idx = scr.layoutPanelAt(mouseX, mouseY);
                        if (idx >= 0) { scr.layoutEditor.select(idx); scr.layoutSettingsIdx = idx; }
                        return;
                    }
                }
            }
            for (String c : classes) {
                if ("pset-close-btn".equals(c)) { scr.layoutSettingsIdx = -1; return; }
                if ("pset-auto-btn".equals(c)) {
                    var pp = scr.psetPanel();
                    if (pp != null) {
                        // おすすめ = 全て 0 (自動)。 renderer がモニター/パネルの px サイズから最適値を算出
                        pp.setFontSize(0); pp.setMapTextSize(0);
                        pp.setTrainIconSize(0); pp.setStationIconSize(0); pp.setSignalIconSize(0);
                    }
                    return;
                }
                if ("layout-edit-close".equals(c) || "mc-popup-close".equals(c)) {
                    scr.layoutSettingsIdx = -1;
                    scr.layoutEditor.close();
                    return;
                }
                if ("layout-clear-btn".equals(c)) {
                    scr.layoutEditor.getLayout().clear();
                    scr.layoutEditor.clearSelection();
                    scr.layoutSettingsIdx = -1;
                    return;
                }
                if ("layout-recommend-btn".equals(c)) {
                    scr.applyRecommendedLayout();
                    scr.layoutSettingsIdx = -1;
                    return;
                }
                if ("layout-save-btn".equals(c)) {
                    scr.saveLayoutToServer();
                    scr.layoutSettingsIdx = -1;
                    scr.layoutEditor.close();
                    return;
                }
                if (c.startsWith("layout-tile-") && !"layout-tile-item".equals(c)) {
                    String typeName = c.substring("layout-tile-".length());
                    scr.addLayoutPanel(typeName);
                    return;
                }
            }
            // 一致なし → main GUI ハンドラに落とす (popup 中も main の他ボタン操作可)
        }
        // Phase 24: 電化詳細 popup clicks (列車詳細 popup と並存しているため、
        // mc-popup-close は ed-close-btn 経由の場合のみ受ける = ユニーククラス判定)
        if (scr.edDetail.isOpen() && scr.selectedTrainId != null && !scr.tabDropdown.isOpen()) {
            if (scr.wikiMode && scr.wikiPantographButtons(classes)) return;
            if (scr.edDetail.handleClick(classes, scr.selectedTrainId)) return;
            // パンタ個別 toggle は edPantoHits (drawCanvas が populate する hit-box) の
            // hit-test ゆえ controller でなく screen 側で処理する。
            for (String c : classes) {
                if ("ed-car-list-canvas".equals(c)) {
                    // canvas 内のパンタ hit-box を順にチェック
                    for (ElectrificationCarListRenderer.EdPantoHit ph : scr.edCarList.pantoHits()) {
                        if (mouseX >= ph.x0() && mouseX <= ph.x1()
                                && mouseY >= ph.y0() && mouseY <= ph.y1()) {
                            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                                    "[PantoToggle-DEBUG] CLIENT click toggle-one train={} car={} pos={}",
                                    scr.selectedTrainId, ph.carriageIndex(), ph.pos());
                            com.trainsystemutilities.client.TrainClient.pantograph(
                                    scr.selectedTrainId,
                                    com.trainsystemutilities.network.PantographAction.ACTION_TOGGLE_ONE,
                                    ph.carriageIndex(), ph.pos());
                            return;
                        }
                    }
                    com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                            "[PantoToggle-DEBUG] CLIENT canvas-click missed hits ({} pantos cached)",
                            scr.edCarList.pantoHits().size());
                    return;
                }
            }
            // 電化詳細のクラスにマッチしなかった click は train-detail / main GUI へ落とす
            // (= 列車詳細 popup と並存しているため両方クリック可能)
        }
        // Train detail popup clicks
        if (scr.selectedTrainId != null && !scr.tabDropdown.isOpen()) {
            for (String c : classes) {
                if ("mc-popup-close".equals(c) || "train-detail-close".equals(c)) {
                    scr.selectedTrainId = null;
                    scr.edDetail.close();
                    return;
                }
                if ("train-detail-electrification-btn".equals(c)) {
                    // 列車詳細 popup を閉じて電化詳細 popup を表示
                    scr.edDetail.open();
                    return;
                }
            }
            // fallthrough → main GUI
        }

        if (scr.tabDropdown.isOpen()) {
            for (String c : classes) {
                if ("tab-dropdown".equals(c)) {
                    // the trigger again folds the menu (it used to close and open it again in one press)
                    scr.tabDropdown.close();
                    return;
                }
                if (c.startsWith("tab-item-")) {
                    String key = c.substring("tab-item-".length());
                    for (String[] t : ManagementComputerScreenV2.TABS) {
                        if (t[0].equals(key)) {
                            scr.tabs.switchTo(key);
                            scr.tabDropdown.close();
                            return;
                        }
                    }
                }
            }
            scr.tabDropdown.close();
            // fallthrough → main GUI
        }

        for (String c : classes) {
            switch (c) {
                case "tab-dropdown":
                    scr.tabDropdown.setOpen(true);
                    return;
                case "mc-popup-close":
                case "mc-header-close":
                    scr.onClose();
                    return;
                // hint-toggle-track/knob は base class HintToggleHelper が自動処理
                // monitor toggle (new + alias) は下の controller dispatch で処理
                case "layout-edit-btn":
                    scr.openLayoutEditor();
                    return;
                case "monitor-color-btn":
                    scr.showMonitorColorSettings = !scr.showMonitorColorSettings;
                    if (!scr.showMonitorColorSettings) scr.monitorColorPopup.resetTransientState();
                    return;
                case "symbol-edit-btn":
                    scr.tabs.switchTo("symbol");
                    scr.tabDropdown.close();
                    return;
                case "color-edit-btn":
                    scr.showColorPicker = !scr.showColorPicker;
                    return;
                case "owner-face-box":
                case "owner-face":
                case "owner-face-canvas": // canvas の class は "owner-face-canvas" (canvasKey "owner-face" ではない)。innermost auto-clickable で実クリックはこちらに来る
                    if (scr.minecraftAccess() != null && scr.minecraftAccess().gameMode != null) {
                        scr.minecraftAccess().gameMode.handleInventoryButtonClick(scr.getMenu().containerId, 9000);
                    }
                    return;
                case "sym-create-btn":
                    scr.openSymbolEditorNew();
                    return;
                case "sym-tile":
                case "sym-tile-badge":   // canvas は auto-clickable のため innermost-rule でこちらに来る
                case "sym-tile-name": {
                    int ri = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (ri >= 0 && ri < scr.be().getLineSymbols().size()) {
                        if (button == 1) {
                            scr.symbolDelete.ask(ri);
                        } else {
                            scr.openSymbolEditorExisting(ri);
                        }
                    }
                    return;
                }
                case "train-row": {
                    int idx = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var live = scr.trainsForList();
                        int realIdx = idx + scr.trainScroll.offset();
                        if (realIdx < live.size()) scr.selectedTrainId = live.get(realIdx).id();
                    }
                    return;
                }
                case "sched-row": {
                    int idx = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var live = scr.trainsForList();
                        int realIdx = idx + scr.schedListScroll.offset();
                        if (realIdx < live.size()) {
                            scr.scheduleSelectedTrainId = live.get(realIdx).id();
                            scr.scheduleSelectNano = System.nanoTime();
                            scr.schedEntryScroll.setOffset(0);
                        }
                    }
                    return;
                }
                case "sched-stop-all-btn":
                    scr.startAllStop();
                    return;
                case "sched-resume-all-btn":
                    scr.resumeAllStop();
                    return;
                case "sched-back":
                    scr.scheduleSelectedTrainId = null;
                    scr.showScheduleShare = false;
                    return;
                case "sched-pause":
                    scr.togglePauseSelected();
                    return;
                case "sched-edit":
                    scr.openScheduleEditor();
                    return;
                case "sched-share-btn":
                    scr.showScheduleShare = true;
                    scr.schedShareScroll.clamp();
                    return;
                case "sched-share-close":
                    scr.showScheduleShare = false;
                    return;
                case "station-row": {
                    int idx = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var stations = scr.stationsForList();
                        int realIdx = idx + scr.stationScroll.offset();
                        if (realIdx < stations.size()) {
                            var st = stations.get(realIdx);
                            scr.selectedStationKey = ManagementComputerBlockEntity.stationKey(st.name(), st.position());
                        }
                    }
                    return;
                }
                case "station-row-assign": {
                    int idx = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) {
                        var stations = scr.stationsForList();
                        int realIdx = idx + scr.stationScroll.offset();
                        if (realIdx < stations.size()) {
                            var st = stations.get(realIdx);
                            scr.stationAssign.open(st.name(), st.position());
                            // + ボタンの screen 座標を記録 → overlayDefaultPosition で参照 (anchorStationAssign)。
                            if (!scr.anchorStationAssign(idx)) {
                                scr.assignBtnScreenX = scr.dialogLocalToScreenXAccess((int) mouseX);
                                scr.assignBtnScreenY = scr.dialogLocalToScreenYAccess((int) mouseY + 8);
                            }
                        }
                    }
                    return;
                }
                case "station-back":
                    scr.selectedStationKey = "";
                    return;
                case "door-btn": {
                    int idx = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0 && idx < ManagementComputerScreenV2.DOOR_OPTS.length) {
                        var s = scr.selectedStation();
                        if (s != null) {
                            var side = com.trainsystemutilities.blockentity
                                    .RailwayManagementBlockEntity.DoorSide.valueOf(ManagementComputerScreenV2.DOOR_OPTS[idx][0]);
                            scr.setDoorSideOnServer(s.name(), s.position(), side);
                        }
                    }
                    return;
                }
            }
        }
    }
}
