package com.trainsystemutilities.client.gui;

import com.manta.api.controller.ColorPickerController;
import com.manta.api.render.HoverTilePreview;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.schedule.CreateScheduleIds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** ManagementComputerScreenV2 の描画/補助本体 (god-class 分割 第 2 波)。
 *  挙動は screen 在置時代と同一 — bodies は verbatim 移設で、screen メンバーは scr. 経由で参照する。 */
final class ManagementComputerRender {

    private ManagementComputerRender() {}

    static int[] overlayDefaultPosition(ManagementComputerScreenV2 scr, int overlayW, int overlayH) {
        // Phase 5d FIX: dialog scale != 1.0 でも overlay が dialog 内の正しい相対位置に出るよう、
        // dialog 内座標は dialogLocalToScreenX/Y、長さ計算は dialogScaleAmount を使う。
        int dispW = scr.dialogScaleAmountAccess(overlayW);  // overlay 表示幅 (= overlayW * dialogScale)
        int dispH = scr.dialogScaleAmountAccess(overlayH);
        // タブ dropdown はサイドバーの dropdown trigger 直下に配置。
        if (scr.tabDropdown.isOpen()) {
            return new int[]{scr.dialogLocalToScreenXAccess(14 + 2), scr.dialogLocalToScreenYAccess(35 + 18 + 18)};
        }
        // 列車詳細 popup はメイン GUI の右側 (V1 と同じ)。
        // 画面右端を超えるなら左側へフォールバック。
        if (scr.selectedTrainId != null) {
            int x = scr.dialogLocalToScreenXAccess(scr.imageWidthAccess() + 6);
            int y = scr.dialogLocalToScreenYAccess(10);
            if (x + dispW + 4 > scr.width) x = scr.dialogLocalToScreenXAccess(-overlayW - 6);
            int maxY = scr.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // スケジュール編集 popup は時刻表本体の表示を遮らないようメイン GUI 左側へ。
        if (scr.schedEditor.isOpen()) {
            int x = scr.dialogLocalToScreenXAccess(-overlayW - 6);
            int y = scr.dialogLocalToScreenYAccess(10);
            if (x < 4) x = scr.dialogLocalToScreenXAccess(scr.imageWidthAccess() + 6);
            int maxY = scr.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // 路線記号エディタもメイン GUI 左側に展開 (V1 と同じ)。
        if (scr.symEditor.isOpen()) {
            int x = scr.dialogLocalToScreenXAccess(-overlayW - 6);
            int y = scr.dialogLocalToScreenYAccess(10);
            if (x < 4) x = scr.dialogLocalToScreenXAccess(scr.imageWidthAccess() + 6);
            int maxY = scr.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // モニター色設定 popup はメイン GUI 右側に展開 (RM 色 popup と同じレイアウト)。
        // 中央に重ねるとメイン GUI のボタンがクリックできなくなるため。
        if (scr.showMonitorColorSettings) {
            int x = scr.dialogLocalToScreenXAccess(scr.imageWidthAccess() + 8);
            int y = scr.dialogLocalToScreenYAccess(10);
            // 画面右に入らなければ左へフォールバック
            if (x + dispW + 4 > scr.width) x = scr.dialogLocalToScreenXAccess(-overlayW - 8);
            if (x < 4) x = 4;
            int maxY = scr.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // 駅タブの路線記号 assign dropdown は + ボタン直下に配置。
        if (scr.stationAssign.isOpen()) {
            // ボタン右端を popup 右端に揃える (button x は + ボタンの左端)
            int x = scr.assignBtnScreenX + scr.dialogScaleAmountAccess(14) - dispW;
            int y = scr.assignBtnScreenY + 2;
            if (x < 4) x = 4;
            if (x + dispW + 4 > scr.width) x = scr.width - dispW - 4;
            if (y + dispH + 4 > scr.height) y = scr.assignBtnScreenY - scr.dialogScaleAmountAccess(14) - dispH - 4;
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        // 他 popup は親クラス default (画面中央) で表示。
        return null;
    }

    static int[] overlayDefaultPosition2(ManagementComputerScreenV2 scr, int overlayW, int overlayH) {
        // Phase 5d FIX: overlay2 もスケール対応 (= overlayW/H は論理サイズ、表示は overlay2Scale=dialogScale 倍)
        int dispW = scr.dialogScaleAmountAccess(overlayW);
        int dispH = scr.dialogScaleAmountAccess(overlayH);
        if (scr.symEditor.isOpen() && scr.showColorPicker) {
            // editor (overlay1) の右隣に color picker (overlay2) を配置
            int x = scr.overlayX() + scr.dialogScaleAmountAccess(scr.overlayW() + 6);
            int y = scr.overlayY();
            if (x + dispW + 4 > scr.width) x = scr.overlayX() - dispW - 6;
            int maxY = scr.height - dispH - 4;
            if (y > maxY) y = maxY;
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        if (scr.schedEditor.isOpen() && scr.schedEditor.addCondForEntry() >= 0) {
            int x = scr.schedEditor.addCondBtnScreenX();
            int y = scr.schedEditor.addCondBtnScreenY() + 2;  // ボタン下端の少し下
            // 画面端クリップ
            if (x + dispW + 4 > scr.width) x = scr.width - dispW - 4;
            if (x < 4) x = 4;
            if (y + dispH + 4 > scr.height) y = scr.schedEditor.addCondBtnScreenY() - dispH - 4;  // 下に入らなければ上に
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        if (scr.schedEditor.isOpen() && scr.schedEditor.isAddEntryOpen()) {
            // 「動作を追加」ボタン (popup-local 8,252,h14) の下に overlay2 を出す。
            int btnX = scr.overlayX() + scr.dialogScaleAmountAccess(8);
            int btnY = scr.overlayY() + scr.dialogScaleAmountAccess(252 + 14);
            int x0 = btnX;
            int y0 = btnY + 2;
            if (x0 + dispW + 4 > scr.width) x0 = scr.width - dispW - 4;
            if (x0 < 4) x0 = 4;
            int maxY0 = scr.height - dispH - 4;
            if (y0 > maxY0) y0 = maxY0;
            return new int[]{x0, y0};
        }
        if (scr.schedEditor.isOpen() && scr.schedEditor.isStationDropdownOpen()) {
            // 「動作を追加」→「駅へ移動」等も同じボタンの下に出す (時刻表編集パネル内、枠外に飛ばさない)。
            int btnX = scr.overlayX() + scr.dialogScaleAmountAccess(8);
            int btnY = scr.overlayY() + scr.dialogScaleAmountAccess(252 + 14);
            int x = btnX;
            int y = btnY + 2;
            if (x + dispW + 4 > scr.width) x = scr.width - dispW - 4;
            if (x < 4) x = 4;
            int maxY = scr.height - dispH - 4;
            if (y > maxY) y = maxY;
            return new int[]{x, y};
        }
        // Phase 24: 電化詳細 popup は画面中央に配置 (列車詳細 popup の上に重ねる)
        if (scr.edDetail.isOpen() && scr.selectedTrainId != null) {
            int x = (scr.width - dispW) / 2;
            int y = (scr.height - dispH) / 2;
            if (x < 4) x = 4;
            if (y < 4) y = 4;
            return new int[]{x, y};
        }
        return null;
    }

    static void drawCanvas(ManagementComputerScreenV2 scr, GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        switch (key) {
            case "sym-edit-preview" -> {
                scr.drawSymbolBadge(g, x, y, Math.min(w, h), scr.symEditor.buildSymbol());
            }
            case "sym-tile-badge" -> {
                int ri = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                var syms = scr.serverBE().getLineSymbols();
                if (ri >= 0 && ri < syms.size()) {
                    scr.drawSymbolBadge(g, x, y, Math.min(w, h), syms.get(ri));
                }
            }
            case "map" -> scr.mapRenderer.draw(g, x, y, w, h, scr.fontOrNull(), scr.leftPosAccess(), scr.topPosAccess(), scr.mapNodes, scr.mapEdges, scr.mapStations, scr.mapSignals, scr.mapTrains);
            case "owner-face" -> com.manta.api.hud.OwnerFacePainter.draw(
                    g, x, y, w, h, scr.be().getOwnerUUID());
            case "train-model" -> scr.trainModel.draw(g, x, y, w, h, scr.minecraftAccess(), scr.fontOrNull(), scr.selectedTrainId, scr.overlayX(), scr.overlayY());
            case "ed-car-list" -> scr.edCarList.draw(g, x, y, w, h, scr.fontOrNull(), scr.selectedTrainId);
            case "station-row-badge" -> {
                int rii = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                if (rii >= 0) {
                    var stations = scr.stationsForList();
                    int realIdx = rii + scr.stationScroll.offset();
                    if (realIdx < stations.size()) {
                        var st = stations.get(realIdx);
                        var sym = scr.be().getSymbolForStation(st.name(), st.position());
                        if (sym != null) scr.drawSymbolBadge(g, x, y, Math.min(w, h), sym);
                    }
                }
            }
            case "station-detail-badge" -> {
                var s = scr.selectedStation();
                if (s != null) {
                    var sym = scr.be().getSymbolForStation(s.name(), s.position());
                    if (sym != null) scr.drawSymbolBadge(g, x, y, Math.min(w, h), sym);
                }
            }
            case "assign-item-badge" -> {
                int ri = com.manta.api.screen.JsonLayoutEngine.currentRepeatIndex();
                var syms = scr.serverBE().getLineSymbols();
                if (ri >= 0 && ri < syms.size()) {
                    scr.drawSymbolBadge(g, x, y, Math.min(w, h), syms.get(ri));
                }
            }
            case "layout-preview" -> scr.drawLayoutPreview(g, x, y, w, h, mouseX, mouseY);
            case "sched-edit-body" -> scr.schedEditor.drawBody(g, x, y, w, h, mouseX, mouseY, scr.overlayX(), scr.overlayY(), scr.fontOrNull());
            case "cp-hue-bar" -> {
                for (int i = 0; i < w; i++) {
                    int col = ColorPickerController.hsvToRgb(i / (float) w, 1f, 1f) | 0xFF000000;
                    g.fill(x + i, y, x + i + 1, y + h, col);
                }
                int hueCx = x + (int) (scr.picker.hue() * w);
                g.fill(hueCx - 1, y - 1, hueCx + 2, y + h + 1, 0xFFFFFFFF);
                g.fill(hueCx, y, hueCx + 1, y + h, 0xFF000000);
            }
            case "cp-sv-panel" -> {
                int step = 2;
                for (int sy = 0; sy < h; sy += step) {
                    float v = 1f - sy / (float) h;
                    for (int sx = 0; sx < w; sx += step) {
                        float s = sx / (float) w;
                        int col = ColorPickerController.hsvToRgb(scr.picker.hue(), s, v) | 0xFF000000;
                        g.fill(x + sx, y + sy, x + sx + step, y + sy + step, col);
                    }
                }
                int svCx = x + (int) (scr.picker.saturation() * w);
                int svCy = y + (int) ((1f - scr.picker.value()) * h);
                g.fill(svCx - 4, svCy, svCx + 5, svCy + 1, 0xFFFFFFFF);
                g.fill(svCx, svCy - 4, svCx + 1, svCy + 5, 0xFFFFFFFF);
            }
            case "export-arrow-bar" -> {
                // 時刻表書き出しの進捗を「矢印型」で描く。 軸 + 矢じりを1ループの列描画で継ぎ目なく一体に。
                float p = Math.max(0f, Math.min(1f,
                        scr.be().getExportProgress() / (float) ManagementComputerBlockEntity.EXPORT_TICKS));
                int cy = y + h / 2;
                int tip = x + w;
                int headW = 8;
                int bodyEnd = tip - headW;
                int shaftHalf = 2;  // 軸 4px
                int headHalf = 4;   // 矢じり 8px (基部)
                int progressX = x + Math.round(w * p);
                int track = 0xFF555566, fill = 0xFF66cc66;
                for (int px = x; px < tip; px++) {
                    int half = (px < bodyEnd) ? shaftHalf
                            : Math.round(headHalf * (1f - (float) (px - bodyEnd) / headW));
                    g.fill(px, cy - half, px + 1, cy + half, (px < progressX) ? fill : track);
                }
            }
        }
    }

    static void applyRecommendedLayout(ManagementComputerScreenV2 scr) {
        scr.layoutEditor.getLayout().clear();
        int mw = scr.be().getMonitorW();
        int mh = scr.be().getMonitorH();
        int area = Math.max(1, mw * mh);
        // 寸法に応じてレイアウトを切り替え。 area = 横ブロック数 × 縦ブロック数。
        if (area <= 2) {
            // 1x1 / 1x2 / 2x1: clock + train list の最小構成
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.04f, 0.04f, 0.92f, 0.22f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.04f, 0.30f, 0.92f, 0.66f));
        } else if (area <= 4) {
            // 2x2 / 1x4 / 4x1: route map + clock + train list + stat 1 個
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP, 0.04f, 0.04f, 0.55f, 0.92f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.62f, 0.04f, 0.34f, 0.18f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.STATION_COUNT, 0.62f, 0.25f, 0.16f, 0.22f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_COUNT, 0.80f, 0.25f, 0.16f, 0.22f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.62f, 0.50f, 0.34f, 0.46f));
        } else if (area <= 8) {
            // 3x2 / 2x3 / 4x2 / 2x4: route map + clock + stat 3 個 + train list + schedule
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP, 0.02f, 0.02f, 0.55f, 0.96f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.60f, 0.02f, 0.38f, 0.14f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.STATION_COUNT, 0.60f, 0.18f, 0.12f, 0.18f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_COUNT, 0.73f, 0.18f, 0.12f, 0.18f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SIGNAL_COUNT, 0.86f, 0.18f, 0.12f, 0.18f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.60f, 0.38f, 0.38f, 0.30f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SCHEDULE, 0.60f, 0.70f, 0.38f, 0.28f));
        } else {
            // 大型 (3x3 以上): 全 panel type を配置
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.ROUTE_MAP, 0.02f, 0.02f, 0.55f, 0.96f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.CLOCK, 0.60f, 0.02f, 0.38f, 0.12f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.STATION_COUNT, 0.60f, 0.16f, 0.12f, 0.14f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_COUNT, 0.73f, 0.16f, 0.12f, 0.14f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SIGNAL_COUNT, 0.86f, 0.16f, 0.12f, 0.14f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.TRAIN_LIST, 0.60f, 0.32f, 0.38f, 0.32f));
            scr.layoutEditor.getLayout().add(new com.trainsystemutilities.blockentity.MonitorLayoutPanel(
                    com.trainsystemutilities.blockentity.MonitorLayoutPanel.PanelType.SCHEDULE, 0.60f, 0.66f, 0.38f, 0.32f));
        }
        scr.layoutEditor.clearSelection();
    }

    static List<ScheduleEditorController.EditEntryData> buildEditEntries(
            ManagementComputerScreenV2 scr, com.simibubi.create.content.trains.schedule.Schedule sched) {
        List<ScheduleEditorController.EditEntryData> entries = new ArrayList<>();
        try {
            for (var e : sched.entries) {
                String type = "destination"; String text = ""; int value = 0;
                try {
                    if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.DestinationInstruction d) {
                        type = "destination"; text = d.getFilter();
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.DeliverPackagesInstruction) {
                        type = "deliver"; text = e.instruction.getData().getString("Text");
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.FetchPackagesInstruction) {
                        type = "fetch"; text = e.instruction.getData().getString("Text");
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction) {
                        type = "rename"; text = e.instruction.getData().getString("Text");
                    } else if (e.instruction instanceof com.simibubi.create.content.trains.schedule.destination.ChangeThrottleInstruction) {
                        type = "throttle"; value = e.instruction.getData().getInt("Value");
                    } else {
                        var s2 = e.instruction.getSummary();
                        text = s2 != null ? s2.getSecond().getString() : "?";
                    }
                } catch (Exception ex) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] schedule entry read failed", ex); }
                List<ScheduleEditorController.EditCondData> conds = new ArrayList<>();
                for (var cg : e.conditions) {
                    for (var c : cg) {
                        try {
                            var cd = c.getData();
                            String cType = "delay"; int cVal = 5; int cUnit = 1;
                            if (c instanceof com.simibubi.create.content.trains.schedule.condition.ScheduledDelay) {
                                cType = "delay"; cVal = cd.getInt("Value"); cUnit = cd.getInt("TimeUnit");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.TimeOfDayCondition) {
                                cType = "time_of_day"; cVal = cd.getInt("Hour");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.ItemThresholdCondition) {
                                cType = "item_threshold"; cVal = cd.getInt("Threshold");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.FluidThresholdCondition) {
                                cType = "fluid_threshold"; cVal = cd.getInt("Threshold");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.RedstoneLinkCondition) {
                                cType = "redstone_link"; cVal = cd.getInt("Inverted");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.PlayerPassengerCondition) {
                                cType = "passenger"; cVal = cd.getInt("Count");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.IdleCargoCondition) {
                                cType = "idle"; cVal = cd.getInt("Value"); cUnit = cd.getInt("TimeUnit");
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.StationPoweredCondition) {
                                cType = "powered";
                            } else if (c instanceof com.simibubi.create.content.trains.schedule.condition.StationUnloadedCondition) {
                                cType = "unloaded";
                            }
                            conds.add(new ScheduleEditorController.EditCondData(cType, cVal, cUnit));
                        } catch (Exception ex) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] schedule condition read failed", ex); }
                    }
                }
                entries.add(new ScheduleEditorController.EditEntryData(type, text, value, conds));
            }
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "schedule editor: failed to parse existing schedule into edit form", e);
        }
        return entries;
    }

    static void applyScheduleEdit(ManagementComputerScreenV2 scr) {
        if (scr.scheduleSelectedTrainId == null) { scr.schedEditor.close(); return; }
        try {
            var nbt = new net.minecraft.nbt.CompoundTag();
            var entriesList = new net.minecraft.nbt.ListTag();
            for (var entry : scr.schedEditor.getEntries()) {
                var entryNbt = new net.minecraft.nbt.CompoundTag();
                var instrNbt = new net.minecraft.nbt.CompoundTag();
                switch (entry.type) {
                    case "destination" -> { instrNbt.putString("Id", CreateScheduleIds.DESTINATION); instrNbt.putString("Text", entry.text); }
                    case "deliver" -> { instrNbt.putString("Id", CreateScheduleIds.PACKAGE_DELIVERY); instrNbt.putString("Text", entry.text); }
                    case "fetch" -> { instrNbt.putString("Id", CreateScheduleIds.PACKAGE_RETRIEVAL); instrNbt.putString("Text", entry.text); }
                    case "rename" -> { instrNbt.putString("Id", CreateScheduleIds.RENAME); instrNbt.putString("Text", entry.text); }
                    case "throttle" -> { instrNbt.putString("Id", CreateScheduleIds.THROTTLE); instrNbt.putInt("Value", entry.value); }
                }
                entryNbt.put("Instruction", instrNbt);
                var condListNbt = new net.minecraft.nbt.ListTag();
                if (!entry.conditions.isEmpty()) {
                    var condGroupNbt = new net.minecraft.nbt.ListTag();
                    for (var cond : entry.conditions) {
                        var condNbt = new net.minecraft.nbt.CompoundTag();
                        switch (cond.type) {
                            case "delay" -> { condNbt.putString("Id", CreateScheduleIds.DELAY); condNbt.putInt("Value", cond.value); condNbt.putInt("TimeUnit", cond.timeUnit); }
                            case "time_of_day" -> { condNbt.putString("Id", CreateScheduleIds.TIME_OF_DAY); condNbt.putInt("Hour", cond.value); condNbt.putInt("Minute", 0); condNbt.putInt("Rotation", 0); }
                            case "item_threshold" -> { condNbt.putString("Id", CreateScheduleIds.ITEM_THRESHOLD); condNbt.putInt("Threshold", cond.value); condNbt.putInt("Operator", 0); condNbt.putInt("Measure", 0); }
                            case "fluid_threshold" -> { condNbt.putString("Id", CreateScheduleIds.FLUID_THRESHOLD); condNbt.putInt("Threshold", cond.value); condNbt.putInt("Operator", 0); }
                            case "redstone_link" -> { condNbt.putString("Id", CreateScheduleIds.REDSTONE_LINK); condNbt.putInt("Inverted", cond.value); }
                            case "passenger" -> { condNbt.putString("Id", CreateScheduleIds.PLAYER_COUNT); condNbt.putInt("Count", cond.value); condNbt.putInt("Exact", 0); }
                            case "idle" -> { condNbt.putString("Id", CreateScheduleIds.IDLE); condNbt.putInt("Value", cond.value); condNbt.putInt("TimeUnit", cond.timeUnit); }
                            case "powered" -> condNbt.putString("Id", CreateScheduleIds.POWERED);
                            case "unloaded" -> condNbt.putString("Id", CreateScheduleIds.UNLOADED);
                            case "coupling" -> { condNbt.putString("Id", "trainsystemutilities:coupling"); condNbt.putInt("Mode", 0); condNbt.putInt("WaitTime", 5); }
                            case "decoupling" -> { condNbt.putString("Id", "trainsystemutilities:coupling"); condNbt.putInt("Mode", 1); condNbt.putInt("WaitTime", 5); }
                        }
                        condGroupNbt.add(condNbt);
                    }
                    condListNbt.add(condGroupNbt);
                }
                entryNbt.put("Conditions", condListNbt);
                entriesList.add(entryNbt);
            }
            nbt.put("Entries", entriesList);
            nbt.putBoolean("Cyclic", scr.schedEditor.isCyclic());

            // MP 対応: server 権威の payload で適用 (ゲートは ApplyScheduleEditPayload.handle で検証)
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.trainsystemutilities.network.ApplyScheduleEditPayload(
                            scr.be().getBlockPos(), scr.scheduleSelectedTrainId, nbt));
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug(
                    "applyScheduleEdit failed for train {}", scr.scheduleSelectedTrainId, e);
        }
        scr.schedEditor.close();
    }

    static void afterDialogRender(ManagementComputerScreenV2 scr, GuiGraphics g, int mouseX, int mouseY, float partial) {
        // 時刻表書き出しスロットの可視/有効化 + 入力充填で自動書き出し (server 権威)
        boolean schedDetail = scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null;
        com.trainsystemutilities.gui.ManagementComputerMenu.exportSlotsVisible = schedDetail;
        if (schedDetail && scr.getMenu().slots.size() > 3) {
            boolean inFilled = !scr.getMenu().slots.get(2).getItem().isEmpty();
            if (inFilled && scr.getMenu().slots.get(3).getItem().isEmpty()
                    && scr.be().getExportProgress() == 0 && !scr.exportRequestSent) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.trainsystemutilities.network.ExportTimetablePayload(
                                scr.be().getBlockPos(), scr.scheduleSelectedTrainId));
                scr.exportRequestSent = true;
            }
            if (!inFilled) scr.exportRequestSent = false;
        } else {
            scr.exportRequestSent = false;
        }
        if (scr.tabDropdown.isOpen()) return;
        scr.refreshNetworkData();
        if (scr.selectedTrainId != null) {
            scr.refreshSelectedTrainSnapshot(scr.selectedTrainId);
        } else if (scr.tabs.is("schedule") && scr.scheduleSelectedTrainId != null) {
            // 時刻表タブで列車タイルを選択したときも明細 snapshot を更新する
            // (旧実装は selectedTrainId のみ対象で scheduleSelectedTrainId が未反映 →「スケジュールなし」固定だった)
            if (!scr.wikiMode) scr.refreshSelectedTrainSnapshot(scr.scheduleSelectedTrainId); // wiki: 事前設定 snapshot を保持
        }
        // 路線記号タブで hover 中のタイル detail preview を描画 (V1 renderSymbolTilePreview 同等)。
        if (scr.tabs.is("symbol") && !scr.symEditor.isOpen() && !scr.showColorPicker
                && !scr.symbolDelete.isOpen()) {
            scr.renderSymbolTileHoverPreview(g, mouseX, mouseY);
        } else {
            scr.symbolHover.reset();
        }
        // Layout editor: drag 中のパレットタイルを DragDropPalette ghost で描画。
        // Phase 5d FIX: palette.update に渡される mouseX/Y は overlay popup-local 座標。
        // afterDialogRender は screen-pose で呼ばれるので、overlay の translate+scale
        // を再適用してから描画する (これがないと cursor からずれる)。
        if (scr.layoutEditor.isOpen() && scr.palette.isDragging()) {
            g.pose().pushPose();
            g.pose().translate(scr.overlayX(), scr.overlayY(), 700);
            float s = scr.overlayScale();
            if (s != 1.0f) g.pose().scale(s, s, 1f);
            scr.palette.drawGhost(g, scr.fontOrNull(), scr.paletteLabelFor(scr.palette.payload()));
            g.pose().popPose();
        }
    }

    static void renderSymbolTileHoverPreview(ManagementComputerScreenV2 scr, GuiGraphics g, int mouseX, int mouseY) {
        var symbols = scr.serverBE().getLineSymbols();
        // タイル位置: (CONTENT_X + 6, CONTENT_Y + 22) から 7 cols × stride (36+4)。
        // gen 側の SYM_TAB_PAD=6, SYM_TILE=36, SYM_GAP=4, SYM_COLS=7。
        final int SYM_TILE = 36, SYM_GAP = 4, SYM_COLS = 7;
        final int CONTENT_X_LOCAL = 148, CONTENT_Y_LOCAL = 35;  // gen 側と整合
        int gridX0 = scr.leftPosAccess() + CONTENT_X_LOCAL + 6;
        int gridY0 = scr.topPosAccess()  + CONTENT_Y_LOCAL + 22;
        int idx = scr.symbolHover.update(mouseX, mouseY,
                gridX0, gridY0, SYM_TILE, SYM_GAP, SYM_COLS, symbols.size());
        if (idx < 0) return;
        var sym = symbols.get(idx);

        // scale-in アニメ進捗 (HoverTilePreview に委譲)
        float eased = scr.symbolHover.animProgress();
        eased = 1f - (1f - eased) * (1f - eased);  // ease-out quad
        int previewW = 100, previewH = 70;
        int drawW = (int) (previewW * eased);
        int drawH = (int) (previewH * eased);
        if (drawW <= 0 || drawH <= 0) return;
        int padding = 6;
        int panelW = drawW + padding * 2;
        int panelH = drawH + padding * 2;

        // メイン GUI 右側にフロート表示 (画面端は左へフォールバック)
        int[] pos = HoverTilePreview.sideSnapPosition(
                scr.leftPosAccess(), scr.topPosAccess(), scr.imageWidthAccess(), scr.imageHeightAccess(),
                panelW, panelH, scr.width, scr.height);
        int px = pos[0];
        int py = Math.max(4, mouseY - panelH / 2);
        if (py + panelH + 4 > scr.height) py = scr.height - panelH - 4;

        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(px, py, px + panelW, py + panelH, 0xDD1a1a2e);
        g.fill(px, py, px + panelW, py + 1, 0xFF4fc3f7);
        g.fill(px, py + panelH - 1, px + panelW, py + panelH, 0xFF4fc3f7);
        g.fill(px, py, px + 1, py + panelH, 0xFF4fc3f7);
        g.fill(px + panelW - 1, py, px + panelW, py + panelH, 0xFF4fc3f7);

        int iconSize = Math.min(drawW - 4, 30);
        int iconX = px + padding + 2;
        int iconY = py + padding + 2;
        // LineSymbolPainter 経由でアイコン描画
        LineSymbolPainter.draw(g, iconX, iconY, iconSize, sym, scr.fontOrNull());

        int textX = iconX + iconSize + 6;
        int textY = py + padding + 2;
        if (!sym.getName().isEmpty()) {
            g.drawString(scr.fontOrNull(), sym.getName(), textX, textY, 0xFF4fc3f7, false);
            textY += 11;
        }
        g.drawString(scr.fontOrNull(), sym.getLetters() + " " + sym.getNumberStr(), textX, textY, 0xFFFFFFFF, false);
        textY += 11;
        g.drawString(scr.fontOrNull(),
                net.minecraft.network.chat.Component.translatable("tsu.mc.sym_color_label_fmt", sym.getBorderColor()).getString(),
                textX, textY, 0xFF888888, false);
        textY += 11;
        g.drawString(scr.fontOrNull(), "R: " + sym.getBorderRadius() + "px", textX, textY, 0xFF888888, false);
        g.pose().popPose();
    }

    static void refreshNetworkData(ManagementComputerScreenV2 scr) {
        var computer = scr.be();
        if (computer == null) return;

        BlockPos scanPos = null;
        var cardSlot = scr.getMenu().getSlot(0);
        if (cardSlot != null && cardSlot.hasItem()) {
            var card = cardSlot.getItem();
            if (card.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                var tag = card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                String type = tag.getString("Type");
                if ("track_network".equals(type) || "railway_manager".equals(type)) {
                    scanPos = BlockPos.of(tag.getLong("Pos"));
                }
            }
        }

        if (scanPos == null) {
            if (!scr.mapNodes.isEmpty() || !scr.mapStations.isEmpty() || !scr.mapTrains.isEmpty()) {
                scr.mapNodes = new ArrayList<>();
                scr.mapEdges = new ArrayList<>();
                scr.mapStations = new ArrayList<>();
                scr.mapSignals = new ArrayList<>();
                scr.mapTrains = new ArrayList<>();
                scr.mapRenderer.resetInit();
            }
            scr.lastNetworkScanPos = null;
            scr.lastNetworkRefreshNano = 0L;
            return;
        }

        long now = System.nanoTime();
        boolean sourceChanged = scr.lastNetworkScanPos == null || !scr.lastNetworkScanPos.equals(scanPos);
        if (!sourceChanged && !scr.mapNodes.isEmpty()
                && now - scr.lastNetworkRefreshNano < ManagementComputerScreenV2.NETWORK_REFRESH_INTERVAL_NS) {
            return;
        }

        try {
            // サーバー権威化 (MP desync 修正): client 側で TrackNetworkScanner を回さず、
            // server が updateNetworkCache() で毎秒 scan → sendBlockUpdated 同期した BE cache を読む。
            // dedicated server では client の Create RAILWAYS/TrackGraph が非同期・不完全で、
            // 旧 client scan + setClientSideCache が駅/列車を 0↔正常 にフリッカーさせていた。
            var be = scr.be();
            scr.mapNodes = be.getCachedNodes();
            scr.mapEdges = be.getCachedEdges();
            scr.mapStations = be.getCachedStations();
            scr.mapSignals = be.getCachedSignals();
            scr.mapTrains = be.getCachedTrains();
            scr.clampTrainListScrolls();
            if (sourceChanged) scr.mapRenderer.resetInit();
            scr.lastNetworkScanPos = scanPos.immutable();
            scr.lastNetworkRefreshNano = now;
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
    }

    static void drawLayoutPreview(ManagementComputerScreenV2 scr, GuiGraphics g, int cx, int cy, int cw, int ch,
                                    int mouseX, int mouseY) {
        int monW = scr.serverBE().getMonitorW();
        int monH = scr.serverBE().getMonitorH();
        if (monW <= 0 || monH <= 0) {
            String msg = ManagementComputerScreenV2.tr("tsu.mc.monitor_unlinked");
            int tw = scr.fontOrNull().width(msg);
            g.drawString(scr.fontOrNull(), msg, cx + (cw - tw) / 2, cy + ch / 2 - 4, 0xFFff8888, false);
            scr.layoutPrevW = scr.layoutPrevH = 0;
            return;
        }
        // アスペクト比保持
        float aspect = (float) monW / monH;
        int prevW, prevH;
        int pad = 6;
        int availW = cw - pad * 2, availH = ch - pad * 2;
        if (availW / aspect <= availH) { prevW = availW; prevH = (int)(availW / aspect); }
        else { prevH = availH; prevW = (int)(availH * aspect); }
        if (prevW <= 0 || prevH <= 0) return;
        int prevX = cx + (cw - prevW) / 2;
        int prevY = cy + (ch - prevH) / 2;
        // drag handler 用 (canvas-local; pose は popup 原点に既に変換済みなので追加変換不要)
        scr.layoutPrevX = prevX;
        scr.layoutPrevY = prevY;
        scr.layoutPrevW = prevW;
        scr.layoutPrevH = prevH;

        // モニター枠 + 背景
        g.fill(prevX - 1, prevY - 1, prevX + prevW + 1, prevY + prevH + 1, 0xFF4fc3f7);
        g.fill(prevX, prevY, prevX + prevW, prevY + prevH, 0xFF0a0a14);

        // 各パネル
        for (int i = 0; i < scr.layoutEditor.getLayout().size(); i++) {
            var p = scr.layoutEditor.getLayout().get(i);
            int px = prevX + (int)(p.getX() * prevW);
            int py = prevY + (int)(p.getY() * prevH);
            int pw = Math.max(8, (int)(p.getWidth() * prevW));
            int ph = Math.max(8, (int)(p.getHeight() * prevH));
            boolean selected = (i == scr.layoutEditor.selectedIndex());
            int bgCol = selected ? 0x60ffc107 : 0x304fc3f7;
            int bcCol = selected ? 0xFFffc107 : 0xFF4fc3f7;
            g.fill(px, py, px + pw, py + ph, bgCol);
            // 枠
            g.fill(px, py, px + pw, py + 1, bcCol);
            g.fill(px, py + ph - 1, px + pw, py + ph, bcCol);
            g.fill(px, py + 1, px + 1, py + ph - 1, bcCol);
            g.fill(px + pw - 1, py + 1, px + pw, py + ph - 1, bcCol);
            // ラベル
            String label = p.getType().getDisplayName();
            int tw = scr.fontOrNull().width(label);
            if (tw < pw - 4 && scr.fontOrNull().lineHeight < ph - 2) {
                g.drawString(scr.fontOrNull(), label, px + 3, py + 3,
                        selected ? 0xFFffc107 : 0xFFFFFFFF, false);
            }
        }
        // ヘルプ
        if (scr.layoutEditor.getLayout().isEmpty()) {
            String hint = ManagementComputerScreenV2.tr("tsu.mc.layout_click_hint");
            int tw = scr.fontOrNull().width(hint);
            g.drawString(scr.fontOrNull(), hint, prevX + (prevW - tw) / 2, prevY + prevH / 2 - 4,
                    0xFF666688, false);
        } else if (scr.layoutEditor.selectedIndex() >= 0) {
            String help = net.minecraft.network.chat.Component.translatable("tsu.mc.layout_help").getString();
            g.drawString(scr.fontOrNull(), help, cx + 4, cy + ch - 11, 0xFF888888, false);
        }
    }
}
