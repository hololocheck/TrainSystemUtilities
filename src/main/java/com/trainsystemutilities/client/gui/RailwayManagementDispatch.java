package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutEngine;

/** RailwayManagementScreenV2 の onElementClick dispatcher + popup slot chrome (god-class 分割 増分 2〜4)。
 * 値 (旧 getDynamic*) は Manta 7 Phase 4 から RailwayManagementPush が page へ書く。
 * 挙動は screen 在置時代と同一 — bodies は verbatim 移設で、screen メンバーは scr. 経由で参照する。 */
final class RailwayManagementDispatch {
    private RailwayManagementDispatch() {}

    static void click(RailwayManagementScreenV2 scr, String[] classes, int mouseX, int mouseY) {
        // batch / monitor toggle は popup 中も操作可
        if (scr.batchToggle.handleClick(classes)) return;
        if (scr.monitorToggle.handleClick(classes)) return;
        // popup が開いていても、popup 内クラスでなければ main ハンドラに落とす。
        // (V1 と同じく popup 開中もメイン GUI 操作可。Settings + Color 同時表示にも必要。)
        if (scr.showColorSettings) {
            // 色対象ドロップダウン項目クリック
            // Color popup click → ColorTargetController に委譲
            int dIdx = JsonLayoutEngine.currentRepeatIndex();
            for (String c : classes) {
                if ("color-popup-close".equals(c)) {
                    scr.showColorSettings = false;
                    scr.colorPopup.resetTransientState();
                    return;
                }
            }
            // The target list unfolds / folds by the controller's DropdownMotion (every press is played).
            if (scr.colorPopup.handleClick(classes, dIdx)) {
                return;
            }
            // 一致なし → fall through to main (色 popup 中も main の他ボタンが押せる)
        }
        if (scr.showSettings) {
            // Group items inside repeat
            int idx = JsonLayoutEngine.currentRepeatIndex();
            if (idx >= 0) {
                for (String c : classes) {
                    if ("group-item".equals(c)) {
                        var groups = scr.be().getMonitorGroups();
                        if (idx < groups.size()) {
                            scr.selectedGroupIndex = idx;
                            scr.resetLocalOverrides();
                        }
                        return;
                    }
                }
            }
            for (String c : classes) {
                switch (c) {
                    case "settings-popup-close":
                        // mc-popup-close は使わない (両 popup 共通クラスのため)
                        scr.showSettings = false;
                        scr.resetLocalOverrides(); return;
                    case "face-flip-btn":
                        scr.showBackFace = !scr.showBackFace;
                        scr.resetLocalOverrides();
                        return;
                    // batch-toggle-track/knob は batchToggle controller が下で処理
                    case "track-display-pos": {
                        // クリックで左/右切替
                        int cur = scr.currentTrackPosition();
                        scr.localTrackPosition = cur == 0 ? 1 : 0;
                        int gi = scr.currentGroupIndex();
                        if (scr.showBackFace) {
                            scr.clickButton(scr.batchApply() ? 3000 : 3001 + gi);
                        } else {
                            scr.clickButton(scr.batchApply() ? 1000 : 1001 + gi);
                        }
                        return;
                    }
                    case "track-display-clock": {
                        int cur = scr.currentClockVisible();
                        scr.localClockVisible = cur == 1 ? 0 : 1;
                        int gi = scr.currentGroupIndex();
                        if (scr.showBackFace) {
                            scr.clickButton(scr.batchApply() ? 5000 : 5001 + gi);
                        } else {
                            scr.clickButton(scr.batchApply() ? 4000 : 4001 + gi);
                        }
                        return;
                    }
                }
            }
            // 一致なし → main ハンドラに落とす (popup 中も main 操作可)
        }
        for (String c : classes) {
            switch (c) {
                // hint-toggle-track/knob は base class HintToggleHelper が自動処理
                // monitor-toggle-track/knob は下の controller dispatch で処理
                case "settings-btn":
                    // 同じボタンで開閉トグル
                    scr.setFunctionDropdownOpen(false);
                    scr.showSettings = !scr.showSettings;
                    if (!scr.showSettings) scr.resetLocalOverrides();
                    return;
                case "color-btn":
                    scr.setFunctionDropdownOpen(false);
                    // 排他: アナウンス / ホームドア popup を閉じてから Color popup を開く
                    if (scr.showAnnouncement) {
                        scr.showAnnouncement = false; scr.resetAnnouncementTransientState();
                        scr.hideAnnouncementSlots();
                    }
                    if (scr.showScreenDoor) { scr.showScreenDoor = false; scr.hideScreenDoorSlot(); }
                    scr.showColorSettings = !scr.showColorSettings;
                    return;
                case "announcement-btn": {
                    scr.setFunctionDropdownOpen(false);
                    if (!com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()) return;
                    if (scr.showColorSettings) { scr.showColorSettings = false; scr.colorPopup.resetTransientState(); }
                    if (scr.showScreenDoor) { scr.showScreenDoor = false; scr.hideScreenDoorSlot(); }
                    scr.showAnnouncement = !scr.showAnnouncement;
                    if (scr.showAnnouncement) {
                        // 開いた時刻は render() 内で popup 初出現と同フレームで set される。
                        scr.resetAnnouncementTransientState();
                        scr.positionAnnouncementSlots();
                    } else {
                        scr.resetAnnouncementTransientState();
                        scr.hideAnnouncementSlots();
                    }
                    return;
                }
                case "announcement-popup-close":
                    scr.showAnnouncement = false;
                    scr.resetAnnouncementTransientState();
                    scr.hideAnnouncementSlots();
                    return;
                case "function-dd-trigger":
                    scr.toggleFunctionDropdown();
                    return;
                case "screen-door-btn": {
                    // ドロップダウン項目: ホームドア popup を開く
                    scr.setFunctionDropdownOpen(false);
                    // 排他: アナウンス / Color popup を閉じてから開く
                    if (scr.showColorSettings) { scr.showColorSettings = false; scr.colorPopup.resetTransientState(); }
                    if (scr.showAnnouncement) {
                        scr.showAnnouncement = false; scr.resetAnnouncementTransientState();
                        scr.hideAnnouncementSlots();
                    }
                    scr.showScreenDoor = !scr.showScreenDoor;
                    if (scr.showScreenDoor) {
                        scr.resetScreenDoorPreviewView();
                        scr.positionScreenDoorSlot();
                    } else {
                        scr.hideScreenDoorSlot();
                    }
                    return;
                }
                case "screen-door-popup-close":
                    scr.showScreenDoor = false;
                    scr.showScreenDoorColorPicker = false;
                    scr.hideScreenDoorSlot();
                    return;
                case "sd-color-swatch":
                    // popup 内 picker をトグル開閉
                    if (scr.showScreenDoor) scr.showScreenDoorColorPicker = !scr.showScreenDoorColorPicker;
                    return;
                case "sd-color-picker-close":
                case "sd-color-picker-modal":
                    scr.showScreenDoorColorPicker = false;
                    return;
                case "sd-test-open-btn":
                    scr.sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_TEST,
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_OPEN);
                    return;
                case "sd-test-close-btn":
                    scr.sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_TEST,
                                    com.trainsystemutilities.screendoor.ScreenDoorCondition.ACTION_CLOSE);
                    return;
                case "sd-cond-add-btn":
                    scr.sendScreenDoorCondAdd();
                    return;
                case "sd-cond-event": {
                    int idx = scr.sdCondRealIdx();
                    var conds = scr.be().getScreenDoorConditions();
                    if (idx < 0 || idx >= conds.size()) return;
                    var cur = conds.get(idx);
                    int next = (cur.eventType()
                            == com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP)
                            ? com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_DEPART
                            : com.trainsystemutilities.screendoor.ScreenDoorCondition.EVENT_STOP;
                    scr.sendScreenDoorCondUpdate(idx, cur.withEvent(next));
                    return;
                }
                case "sd-cond-action": {
                    int idx = scr.sdCondRealIdx();
                    var conds = scr.be().getScreenDoorConditions();
                    if (idx < 0 || idx >= conds.size()) return;
                    var cur = conds.get(idx);
                    int next = (cur.actionType() + 1) % 3;
                    scr.sendScreenDoorCondUpdate(idx, cur.withAction(next));
                    return;
                }
                case "sd-cond-del-btn": {
                    int idx = scr.sdCondRealIdx();
                    if (idx < 0) return;
                    scr.sendScreenDoorCondRemove(idx);
                    return;
                }
                default:
                    // sd-preset-N (= N: 0..11) → 帯色を BE に反映 + picker 閉じる
                    if (c.startsWith("sd-preset-")) {
                        try {
                            int idx = Integer.parseInt(c.substring("sd-preset-".length()));
                            if (idx >= 0 && idx < RailwayManagementScreenV2.SCREEN_DOOR_BAND_PRESETS.length) {
                                int argb = RailwayManagementScreenV2.SCREEN_DOOR_BAND_PRESETS[idx];
                                // server 同期
                                scr.sendData(com.trainsystemutilities.network.RailwayManagementData.DOOR_BAND_COLOR, argb);
                                // client 即時反映 (= 次フレームの push が新色を書く)
                                scr.be().setScreenDoorBandColorARGB(argb);
                                scr.showScreenDoorColorPicker = false;
                            }
                        } catch (NumberFormatException ignored) {}
                        return;
                    }
                    break;
                // ann-master / ann-rangeframe / ann-attenuation toggle は下の controller dispatch で処理
                case "ann-add-entry-btn":
                    scr.sendAnnouncementCmd(com.trainsystemutilities.network.AnnouncementCommandAction.OP_ADD_ENTRY, 0, 0, 0);
                    return;
                case "ann-test-play-btn": {
                    var cfg = scr.announcementConfig();
                    if (cfg != null && cfg.size() > 0) {
                        scr.sendAnnouncementCmd(com.trainsystemutilities.network.AnnouncementCommandAction.OP_TEST_PLAY, 0, 0, 0);
                    }
                    return;
                }
                case "ann-cond-display": {
                    int idx = scr.annEntryRealIdx();
                    if (idx < 0) return;
                    // unfolds / folds by the controller's DropdownMotion (every press is played)
                    scr.conditionDropdown.toggleFor(idx);
                    return;
                }
                case "ann-cond-dd-item-0":
                case "ann-cond-dd-item-1":
                case "ann-cond-dd-item-2": {
                    int typeOrd = c.charAt(c.length() - 1) - '0';
                    if (scr.conditionDropdown.isOpen()) {
                        int eIdx = scr.conditionDropdown.openIdx();
                        scr.sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandAction.OP_SET_ENTRY_CONDITION,
                                eIdx, typeOrd, 0);
                        scr.applyConditionTypeLocally(eIdx, typeOrd);
                    }
                    scr.conditionDropdown.close();
                    return;
                }
                case "ann-entry-pause-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx >= 0) scr.sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandAction.OP_STOP_PLAYBACK,
                            idx, 0, 0);
                    return;
                }
                case "ann-entry-up-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx > 0) {
                        scr.triggerAnnouncementSwap(idx, idx - 1);
                        scr.sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandAction.OP_REORDER_ENTRY,
                                idx, idx - 1, 0);
                    }
                    return;
                }
                case "ann-entry-down-btn": {
                    int idx = scr.annEntryRealIdx();
                    var cfg = scr.announcementConfig();
                    if (idx >= 0 && cfg != null && idx + 1 < cfg.size()) {
                        scr.triggerAnnouncementSwap(idx, idx + 1);
                        scr.sendAnnouncementCmd(
                                com.trainsystemutilities.network.AnnouncementCommandAction.OP_REORDER_ENTRY,
                                idx, idx + 1, 0);
                    }
                    return;
                }
                case "ann-entry-test-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx >= 0) scr.sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandAction.OP_TEST_PLAY, idx, 0, 0);
                    return;
                }
                case "ann-entry-del-btn": {
                    int idx = scr.annEntryRealIdx();
                    if (idx >= 0) scr.sendAnnouncementCmd(
                            com.trainsystemutilities.network.AnnouncementCommandAction.OP_REMOVE_ENTRY, idx, 0, 0);
                    return;
                }
                case "ann-share-btn": {
                    if (!scr.showAnnouncement) return;
                    scr.showAnnouncementShareList = !scr.showAnnouncementShareList;
                    if (scr.showAnnouncementShareList) {
                        // dropdown が開いていたら閉じる
                        scr.conditionDropdown.close();
                        scr.annShareScroll.clamp();
                    }
                    return;
                }
                case "ann-share-close-btn":
                    scr.showAnnouncementShareList = false;
                    return;
                // ann-share-det / ann-share-rng toggle は下の controller dispatch で処理
                case "owner-face-box":
                case "owner-face-canvas": // 中央の顔 canvas が innermost auto-clickable で実クリックはこちらに来る
                    // サーバ側で button id 9000 経由で togglePrivateMode 呼び出し。
                    // client は getUpdateTag 同期で反映 (client 直 mutate を廃止)。
                    scr.clickButton(com.manta.api.hud.OwnerAccess.TOGGLE_BUTTON);
                    return;
            }
        }
        // Announcement-related toggles (= controller dispatch、popup 中のみ意味あり)
        if (scr.showAnnouncement) {
            if (scr.annMasterToggle.handleClick(classes)) return;
            if (scr.annRangeFrameToggle.handleClick(classes)) return;
            if (scr.annAttenuationToggle.handleClick(classes)) return;
        }
        if (scr.showScreenDoor) {
            if (scr.sdHighlightToggle.handleClick(classes)) return;
        }
        if (scr.showAnnouncement && scr.showAnnouncementShareList) {
            int idx = scr.annShareRealIdx();
            if (scr.annShareDetToggle.handleClick(classes, idx)) return;
            if (scr.annShareRngToggle.handleClick(classes, idx)) return;
        }
    }

    // ===== popup slot chrome (render 側) =====

    /**
     * popup (overlay2) 上の slot の当たり判定。
     *
     * <p>{@link #renderPopupOverlayItems} は 16px の中身を popup の scale で拡大して描くため、
     * 見えている slot は {@code 16 * scale} px。vanilla の {@code isHovering} は 16px 固定なので、
     * dialog が拡大されているとき外周がハイライトされず・クリックも効かない。判定も同じ縮尺で行う。
     * scale は manta の overlay 変換 ({@code overlay2Scale()}) を単一の情報源にする。
     */
    static boolean isOverPopupSlot(RailwayManagementScreenV2 scr, net.minecraft.world.inventory.Slot slot,
                                    double mouseX, double mouseY) {
        return com.manta.api.hud.Rects.overSlot(mouseX, mouseY,
                scr.leftPosAccess() + slot.x, scr.topPosAccess() + slot.y, scr.overlay2Scale());
    }

    /** popup の上 (z=700) に slot icon + hover highlight + 手持ちアイテムを再描画。
     *  popup 開放アニメと同期して scale-in (popIn と同じ 0.7→1.0 + EASE_OUT_BACK) を適用し、
     *  popup と「同時に」アイテムが拡大されて見えるようにする (他 GUI と同等の挙動)。 */
    static void renderPopupOverlayItems(RailwayManagementScreenV2 scr, net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY) {
        // popup 開放アニメ進捗 (0..1)。完了後は scale=1.0 で固定。
        long elapsedNs = scr.announcementOpenedAtNanos > 0
                ? System.nanoTime() - scr.announcementOpenedAtNanos : RailwayManagementScreenV2.ANNOUNCEMENT_OPEN_ANIM_NS;
        float t = elapsedNs >= RailwayManagementScreenV2.ANNOUNCEMENT_OPEN_ANIM_NS
                ? 1f
                : Math.max(0f, Math.min(1f, elapsedNs / (float) RailwayManagementScreenV2.ANNOUNCEMENT_OPEN_ANIM_NS));
        float ease = com.manta.api.anim.Easing.EASE_OUT_BACK.apply(t);
        float scale = 0.7f + (1.0f - 0.7f) * ease;

        // popup の中心 (アニメの anchor) — overlay2X/Y + popup root size。
        // overlay2W/H は論理サイズなので、screen 中心は manta の変換を通す
        // (overlay2X() + overlay2W()/2 は screen 原点 + 論理長の混在で scale != 1 のときずれる)。
        int popupW = scr.overlay2W() > 0 ? scr.overlay2W() : 240;
        int popupH = scr.overlay2H() > 0 ? scr.overlay2H() : 340;
        int popupCx = Math.round(scr.overlay2LocalToScreenX(popupW / 2f));
        int popupCy = Math.round(scr.overlay2LocalToScreenY(popupH / 2f));

        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        // 開放アニメ中は overshoot (>1.0) も含めて popup と同じ pose に乗せる。
        if (Math.abs(scale - 1f) > 0.001f) {
            g.pose().translate(popupCx, popupCy, 0);
            g.pose().scale(scale, scale, 1f);
            g.pose().translate(-popupCx, -popupCy, 0);
        }
        // slot に格納されたアイテム + hover highlight + lock 表示 (× 線)
        boolean lockDet = scr.be().isSharedDetectionTarget();
        boolean lockRng = scr.be().isSharedRangeTarget();
        for (int i = com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE;
             i < scr.getMenu().slots.size(); i++) {
            var slot = scr.getMenu().slots.get(i);
            if (!slot.isActive()) continue;
            // off-screen (-1000) の slot はスキップ
            if (slot.x < -500) continue;
            float entryOffsetY = 0f;
            if (scr.showAnnouncement
                    && i >= com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE
                    && i < com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD) {
                entryOffsetY = scr.announcementEntryShuffleOffset(
                        i - com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_MEDIA_BASE);
            }
            int sx = scr.leftPosAccess() + slot.x;
            int sy = Math.round(scr.topPosAccess() + slot.y + entryOffsetY);
            // popup の scale に合わせて 16px の中身も縮尺する (位置は slot 座標で確定済み)。
            // hover 判定も同じ縮尺の矩形で行い、見た目とハイライトを一致させる。
            float slotScale = scr.overlay2Scale() > 0f ? scr.overlay2Scale() : 1f;
            boolean hovered = isOverPopupSlot(scr, slot, mouseX, mouseY);
            g.pose().pushPose();
            g.pose().translate(sx, sy, 0);
            g.pose().scale(slotScale, slotScale, 1f);
            g.pose().translate(-sx, -sy, 0);
            try {
            boolean screenDoorCardSlot = slot.index
                    == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_SCREEN_DOOR_CARD;
            if (screenDoorCardSlot) {
                drawScreenDoorCardSlotFrame(g, sx, sy, hovered);
            }
            if (hovered && !screenDoorCardSlot) {
                g.fillGradient(sx, sy, sx + 16, sy + 16, 0x80FFFFFF, 0x80FFFFFF);
            }
            var stack = slot.getItem();
            if (!stack.isEmpty()) {
                g.renderItem(stack, sx, sy);
                com.manta.api.render.ItemDraw.decorations(g, scr.fontOrNull(), stack, sx, sy);
            }
            // 共有先になっているスロットには × 線を描画 (アイテム配置不可だと視覚的に示す)。
            boolean drawLock = false;
            if (slot.index == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_DETECTION_CARD
                    && lockDet) drawLock = true;
            else if (slot.index == com.trainsystemutilities.gui.RailwayManagementMenu.SLOT_RANGE_BOARD
                    && lockRng) drawLock = true;
            if (drawLock) drawSlotLockedHatch(g, sx, sy);
            } finally {
                g.pose().popPose();
            }
        }
        g.pose().popPose();

        // 手持ち (drag 中) アイテムは scale 影響を受けない z=700 layer に描画。
        // ツールチップは render() の最後に z=900 で再描画する (popup の上に表示)。
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        var carried = scr.getMenu().getCarried();
        if (!carried.isEmpty()) {
            int cx = mouseX - 8;
            int cy = mouseY - 8;
            g.renderItem(carried, cx, cy);
            com.manta.api.render.ItemDraw.decorations(g, scr.fontOrNull(), carried, cx, cy);
        }
        g.pose().popPose();
    }

    static void drawScreenDoorCardSlotFrame(net.minecraft.client.gui.GuiGraphics g,
                                             int slotX, int slotY, boolean hovered) {
        int left = slotX - 1;
        int top = slotY - 1;
        int right = left + 18;
        int bottom = top + 18;
        int border = hovered ? 0xFF4FC3F7 : 0xFF2A2A3A;
        com.manta.api.draw.SmoothRenderer.fillRoundedRect(g, left, top, 18, 18, 5f, 0x8C000000);
        if (hovered) {
            com.manta.api.draw.SmoothRenderer.fillRoundedRect(g, left + 1, top + 1, 16, 16, 4f, 0x264FC3F7);
        }
        com.manta.api.draw.SmoothRenderer.strokeRoundedRect(g, left, top, 18, 18, 5f, 1f, border);
    }

    /** 共有先になっているスロット (16x16) に × 状の斜線を描画する。
     *  ユーザにアイテムが配置できないことを視覚的に伝える。 */
    static void drawSlotLockedHatch(net.minecraft.client.gui.GuiGraphics g, int x, int y) {
        // 半透明の暗いオーバーレイで「使用不可」の雰囲気を出す
        g.fill(x, y, x + 16, y + 16, 0x80101020);
        // 赤い斜線 (\\ と /) を 16 ドット分プロット。各ドットは 1x1 px の塗り。
        int line = 0xFFff5555;
        for (int i = 0; i < 16; i++) {
            // \ : (i, i)
            g.fill(x + i, y + i, x + i + 1, y + i + 1, line);
            // / : (15-i, i)
            g.fill(x + 15 - i, y + i, x + 16 - i, y + i + 1, line);
        }
    }

    /** マウス位置からアクティブな slot を探す (popup slot 含む)。tooltip 再描画 / hit 判定で利用。
     *  popup の外の slot は vanilla が選んだ hover そのもの (論理座標で選ばれ、popup の下に隠れたものは manta の基底が
     *  外す)。2026-10-02 まで生のマウスで引き直していたので、popup の上で隠れた slot のツールチップが popup の上に出て、
     *  UI スケールつまみが 1.0 でないときは別の slot を拾っていた。 */
    static net.minecraft.world.inventory.Slot findHoveredSlot(RailwayManagementScreenV2 scr, int mouseX, int mouseY) {
        for (int i = 0; i < scr.getMenu().slots.size(); i++) {
            var slot = scr.getMenu().slots.get(i);
            if (!slot.isActive()) continue;
            if (slot.x < -500) continue;
            boolean hit = i >= com.trainsystemutilities.gui.RailwayManagementMenu.ANNOUNCEMENT_SLOT_BASE
                    ? RailwayManagementDispatch.isOverPopupSlot(scr, slot, mouseX, mouseY)
                    : slot == scr.hoveredSlotAccess();
            if (hit) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Menu slot の x/y を書き換える。実体は {@link com.manta.api.screen.SlotMover}。
     *
     * <p>旧実装は<b>呼ばれるたびに {@code getDeclaredField} を 2 回</b>していた
     * （popup の slot 数 × フレーム）。部品側は 1 度だけ解決する。
     */
    static void setMenuSlotPos(net.minecraft.world.inventory.AbstractContainerMenu menu, int slotIndex, int x, int y) {
        com.manta.api.screen.SlotMover.move(menu, slotIndex, x, y);
    }
}
