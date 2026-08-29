package com.trainsystemutilities.client.transit;

import belugalab.mcss3.draw.SmoothRenderer;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/** TransitTerminalScreen の描画本体 (god-class 分割)。挙動は screen 在置時代と同一 — bodies は verbatim 移設で、screen メンバーは scr. 経由で参照する。 */
final class TransitTerminalRender {

    /** W7-1: 「結果クリア」の先頭 icon 寸法。<b>描画と当たり判定で同じ値を使う</b>
     *  (片方だけ変えるとクリック位置がずれるため定数に切り出す)。 */
    static final int CLEAR_ICON_SIZE = 8;
    static final int CLEAR_ICON_GAP = 3;

    private TransitTerminalRender() {}

    static void renderTopTab(TransitTerminalScreen scr, GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = scr.px + TransitTerminalScreen.CONTENT_PAD;
        int innerW = TransitTerminalScreen.PANEL_W - TransitTerminalScreen.CONTENT_PAD * 2;
        int boxX = innerX + 18;
        int boxW = innerW - 36;
        int boxY = y + 8;
        scr.topBoxY = boxY; scr.topBoxX = boxX; scr.topBoxW = boxW;

        // 出発駅 bullet / 到着駅 bullet — W7-1: raw glyph を manta registry icon へ置換。
        belugalab.experience.render.Icons.draw(g, "manta:circle-dot", innerX + 4, boxY + 2, 8, 0xFF4FC3F7);
        belugalab.experience.render.Icons.draw(g, "manta:square", innerX + 4, boxY + 24, 8, 0xFFFF8A65);

        // Swap ボタン (↕): 右側に縦長の小ボタン、2 つの input の中間に
        int swapX = innerX + innerW - 16;
        int swapY = boxY + 7;
        int swapW = 14, swapH = 22;
        boolean swapHover = mouseX >= swapX && mouseX < swapX + swapW
                && mouseY >= swapY && mouseY < swapY + swapH;
        int swapBg = swapHover ? 0xFF1f5e7e : 0xFF1f3a50;
        SmoothRenderer.fillRoundedRect(g, swapX, swapY, swapW, swapH, 5f, 0xFF4FC3F7);
        g.fill(swapX + 1, swapY + 1, swapX + swapW - 1, swapY + swapH - 1, swapBg);
        // W7-1: swap glyph を manta:arrow-up-down icon へ。**icon はボタン枠を満たす** —
        // lucide は viewBox の内側 50% にしか描かないので、枠に合わせれば中央の適正サイズになる。
        // 縦長ボタン (14x22) なので正方形に切り出して中央へ置く (縦に潰さない)。
        int swapIco = Math.min(swapW, swapH);
        belugalab.experience.render.Icons.draw(g, "manta:arrow-up-down",
                swapX + (swapW - swapIco) / 2f, swapY + (swapH - swapIco) / 2f,
                swapIco, swapIco, 0xFFFFFFFF);

        // EditBoxes は super.render() で描画される (boxX, boxY)。

        // 検索ボタン
        int btnY = boxY + 38;
        int btnH = 16;
        boolean canSearch = TransitTerminalState.fromGroupId() != null && TransitTerminalState.toGroupId() != null;
        boolean btnHover = mouseX >= innerX && mouseX < innerX + innerW && mouseY >= btnY && mouseY < btnY + btnH;
        int btnBg = !canSearch ? 0xFF1f3030 : (btnHover ? 0xFF1f5e7e : 0xFF2da856);
        int btnBorder = !canSearch ? 0xFF445566 : 0xFF66BB6A;
        SmoothRenderer.fillRoundedRect(g, innerX, btnY, innerW, btnH, 5f, btnBorder);
        g.fill(innerX + 1, btnY + 1, innerX + innerW - 1, btnY + btnH - 1, btnBg);
        // R4.23.1: 先頭の 🔍 を manta:search icon へ (clear_results と同じ形)。
        // 幅は icon + gap + text で数えて塊ごと中央に置く — text 幅だけで中央を出すと
        // icon の分だけ左にずれる。
        String btnLabel = Component.translatable("tsu.transit_terminal.btn_search").getString();
        int bw = CLEAR_ICON_SIZE + CLEAR_ICON_GAP + scr.fontAccess().width(btnLabel);
        int btnTextColor = !canSearch ? 0xFF666666 : 0xFFFFFFFF;
        int btnX = innerX + (innerW - bw) / 2;
        belugalab.experience.render.Icons.draw(g, "manta:search", btnX, btnY + 5,
                CLEAR_ICON_SIZE, btnTextColor);
        g.drawString(scr.fontAccess(), btnLabel,
                btnX + CLEAR_ICON_SIZE + CLEAR_ICON_GAP, btnY + 4, btnTextColor, false);

        // 結果領域 (or 履歴 if no result)
        int resY = btnY + btnH + 6;
        int resH = (y + h) - resY - 4;

        // 結果クリアボタン (検索済の時のみ): 候補タブとは別行にして重なりを防ぐ
        ComposedRouteFinder.ComposedRoute r = TransitTerminalState.lastResult();
        if (r != null) {
            // W7-1: 先頭の ✕ を manta:x icon へ。幅は icon + gap + text で数える
            // (当たり判定と描画で同じ式を使う — 片方だけ直すとクリック位置がずれる)。
            String clearText = Component.translatable("tsu.transit_terminal.clear_results").getString();
            int clIco = CLEAR_ICON_SIZE;
            int clW = clIco + CLEAR_ICON_GAP + scr.fontAccess().width(clearText);
            boolean clHover = mouseX >= innerX + innerW - clW - 6
                    && mouseX < innerX + innerW
                    && mouseY >= resY && mouseY < resY + 11;
            g.fill(innerX + innerW - clW - 6, resY - 1,
                   innerX + innerW, resY + 11,
                   clHover ? 0xFFAA1F1F : 0xFF333344);
            int clX = innerX + innerW - clW - 3;
            int clC = clHover ? 0xFFFFFFFF : 0xFFAAAAAA;
            belugalab.experience.render.Icons.draw(g, "manta:x", clX, resY + 1, clIco, clC);
            g.drawString(scr.fontAccess(), clearText, clX + clIco + CLEAR_ICON_GAP, resY, clC, false);
            resY += 14;
            resH -= 14;
        }

        // ソートタブ (結果がある時のみ): 早 / 楽 / 安
        if (r != null && r.found()) {
            int tabsH = 14;
            renderSortTabs(scr, g, innerX, resY, innerW, tabsH, mouseX, mouseY);
            resY += tabsH + 4;
            resH -= tabsH + 4;
        }
        scr.renderTopResults(g, innerX, resY, innerW, resH, mouseX, mouseY);
    }

    static void renderSortTabs(TransitTerminalScreen scr, GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var routes = TransitTerminalState.lastResults();
        int n = Math.min(3, routes.size());
        if (n <= 1) {
            // 1 候補なら従来の早/楽/安タブ (UI として残す)
            int cellW = w / 3;
            String[] keys = {"tsu.transit_terminal.sort_fast", "tsu.transit_terminal.sort_easy", "tsu.transit_terminal.sort_cheap"};
            int active = TransitTerminalState.sortMode();
            for (int i = 0; i < 3; i++) {
                int cx = x + cellW * i;
                boolean hover = mouseX >= cx && mouseX < cx + cellW && mouseY >= y && mouseY < y + h;
                boolean act = i == active;
                int bg = act ? 0xFF1f5e7e : (hover ? 0xFF1f3a50 : 0xFF111928);
                int border = act ? 0xFF4FC3F7 : 0xFF2a4a60;
                g.fill(cx, y, cx + cellW, y + h, border);
                g.fill(cx + 1, y + 1, cx + cellW - 1, y + h - 1, bg);
                String label = Component.translatable(keys[i]).getString();
                int lw = scr.fontAccess().width(label);
                g.drawString(scr.fontAccess(), label, cx + (cellW - lw) / 2, y + 3, act ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            }
            return;
        }
        int cellW = w / n;
        int active = TransitTerminalState.selectedRouteIdx();
        for (int i = 0; i < n; i++) {
            int cx = x + cellW * i;
            boolean hover = mouseX >= cx && mouseX < cx + cellW && mouseY >= y && mouseY < y + h;
            boolean act = i == active;
            int bg = act ? 0xFF1f5e7e : (hover ? 0xFF1f3a50 : 0xFF111928);
            int border = act ? 0xFF4FC3F7 : 0xFF2a4a60;
            g.fill(cx, y, cx + cellW, y + h, border);
            g.fill(cx + 1, y + 1, cx + cellW - 1, y + h - 1, bg);
            // 候補番号 + 合計時間
            var route = routes.get(i);
            int legsCount = route.trainLegs().size();
            int firstDep = legsCount == 0 ? 0 : route.trainLegs().get(0).departureTicksFromNow();
            int durSec = Math.max(0, (route.totalTicks() - firstDep) / 20);
            String label = (i + 1) + "  " + (durSec / 60) + "分";
            int lw = scr.fontAccess().width(label);
            g.drawString(scr.fontAccess(), label, cx + (cellW - lw) / 2, y + 3, act ? 0xFFFFFFFF : 0xFFAAAAAA, false);
        }
    }

    static void renderHistorySection(TransitTerminalScreen scr, GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var history = TransitTerminalState.history();
        if (history.isEmpty()) {
            String hint = Component.translatable("tsu.transit_terminal.results_hint").getString();
            scr.drawWrapped(g, hint, x, y + 4, w, 0xFF80808F);
            return;
        }
        g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.history_title").getString(),
                x, y, 0xFFAAAAAA, false);
        String clearAll = Component.translatable("tsu.transit_terminal.history_clear_all").getString();
        int caW = scr.fontAccess().width(clearAll);
        boolean clearHover = mouseX >= x + w - caW - 4 && mouseX < x + w
                && mouseY >= y - 1 && mouseY < y + 9;
        g.drawString(scr.fontAccess(), clearAll, x + w - caW - 2, y, clearHover ? 0xFFFF8A65 : 0xFF80808F, false);

        int rowY = y + 12;
        int rowH = 22; // 18 → 22 で削除 × ボタンが見やすく
        int delBtnW = 20;
        int max = Math.min(history.size(), Math.max(1, (h - 16) / (rowH + 2)));
        for (int i = 0; i < max; i++) {
            var e = history.get(i);
            // 行本体 (タイル領域)
            boolean hover = mouseX >= x && mouseX < x + w - delBtnW - 2
                    && mouseY >= rowY && mouseY < rowY + rowH;
            int bg = hover ? 0xFF1f3a50 : 0xFF111928;
            SmoothRenderer.fillRoundedRect(g, x, rowY, w - delBtnW - 2, rowH, 5f, 0xFF2a4a60);
            g.fill(x + 1, rowY + 1, x + w - delBtnW - 3, rowY + rowH - 1, bg);
            // W7-1: 時刻 glyph を manta:clock icon へ。
            belugalab.experience.render.Icons.draw(g, "manta:clock", x + 4, rowY + 6, 9, 0xFF80808F);
            String txt = TransitTerminalScreen.truncate(e.fromName() + " → " + e.toName(), w - delBtnW - 22);
            g.drawString(scr.fontAccess(), txt, x + 16, rowY + 7, 0xFFE0E0E0, false);
            // 削除ボタン (× ボックス、20×rowH の独立タイル)
            int delX = x + w - delBtnW;
            boolean delHover = mouseX >= delX && mouseX < delX + delBtnW
                    && mouseY >= rowY && mouseY < rowY + rowH;
            SmoothRenderer.fillRoundedRect(g, delX, rowY, delBtnW, rowH, 5f,
                    delHover ? 0xFFFF6655 : 0xFF555566);
            int delBg = delHover ? 0xFFAA1F1F : 0xFF333344;
            g.fill(delX + 1, rowY + 1, delX + delBtnW - 1, rowY + rowH - 1, delBg);
            // W7-1: 行削除の ✕ を manta:x icon へ。icon はボタン枠を満たす (中央寄せは
            // lucide の viewBox 余白が担う)。hover 色は従来どおり追従。
            int delIco = Math.min(delBtnW, rowH);
            belugalab.experience.render.Icons.draw(g, "manta:x",
                    delX + (delBtnW - delIco) / 2f, rowY + (rowH - delIco) / 2f,
                    delIco, delIco,
                    delHover ? 0xFFFFFFFF : 0xFFAAAAAA);
            rowY += rowH + 2;
        }
    }

    static void renderResultSummary(TransitTerminalScreen scr, GuiGraphics g, ComposedRouteFinder.ComposedRoute r,
                                     int x, int y, int w, int h, int mouseX, int mouseY) {
        // ヘッダ行: 全体の発時刻 → 着時刻 + 所要時間
        List<TrainRouter.Leg> legs = r.trainLegs();
        int firstDep = legs.isEmpty() ? 0 : legs.get(0).departureTicksFromNow();
        int totalDuration = r.totalTicks(); // = 最終 leg arrival from now
        String depAbs = scr.absoluteClockOffset(firstDep);
        String arrAbs = scr.absoluteClockOffset(totalDuration);
        int totalSec = Math.max(0, (totalDuration - firstDep) / 20);
        int totalMin = totalSec / 60;

        // ヘッダ枠
        int headerH = 22;
        SmoothRenderer.fillRoundedRect(g, x, y, w, headerH, 5f, 0xFF4FC3F7);
        g.fill(x + 1, y + 1, x + w - 1, y + headerH - 1, 0xFF1a3040);
        // 大きい時刻 (発 → 着)
        g.drawString(scr.fontAccess(), depAbs, x + 4, y + 2, 0xFFFFFFFF, false);
        int dw = scr.fontAccess().width(depAbs);
        g.drawString(scr.fontAccess(), "→", x + 4 + dw + 3, y + 2, 0xFF80DEEA, false);
        g.drawString(scr.fontAccess(), arrAbs, x + 4 + dw + 12, y + 2, 0xFFFFFFFF, false);
        // 所要時間 (右上)
        String dur = Component.translatable("tsu.transit_terminal.duration_fmt",
                totalMin, totalSec % 60).getString();
        int durW = scr.fontAccess().width(dur);
        g.drawString(scr.fontAccess(), dur, x + w - durW - 4, y + 2, 0xFFFFD54F, false);
        // メタ行: 乗換 N 回 + 距離
        String meta = Component.translatable("tsu.transit_terminal.transfers_fmt", legs.size() - 1).getString();
        g.drawString(scr.fontAccess(), meta, x + 4, y + 13, 0xFFAAAAAA, false);

        int rowY = y + headerH + 4;

        // 徒歩レッグ (もしあれば短く一行で)
        if (r.walkToFrom() != null && r.walkToFrom().approxTicks() > 0) {
            int walkSec = r.walkToFrom().approxTicks() / 20;
            String walkText = Component.translatable("tsu.transit_terminal.walk_fmt",
                    r.fromGroupName(), walkSec).getString();
            // W7-1: 徒歩 glyph を manta:user icon へ。 text は icon 幅ぶん右へずらす。
            belugalab.experience.render.Icons.draw(g, "manta:user", x, rowY, 9, 0xFF80DEEA);
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(walkText, w - 14),
                    x + 11, rowY, 0xFF80DEEA, false);
            rowY += 12;
        }

        // 路線記号バー (各 leg の路線色を横並びで)
        if (!legs.isEmpty()) {
            int barH = 8;
            int barX = x;
            int totalLegTicks = 0;
            for (var leg : legs) totalLegTicks += leg.travelTicks();
            for (int i = 0; i < legs.size(); i++) {
                TrainRouter.Leg leg = legs.get(i);
                int color = TransitTerminalScreen.lineColorForLeg(leg);
                int segW = totalLegTicks <= 0 ? w / legs.size()
                        : (w * leg.travelTicks() / totalLegTicks);
                if (i == legs.size() - 1) segW = (x + w) - barX;
                g.fill(barX, rowY, barX + segW, rowY + barH, 0xFF000000);
                g.fill(barX + 1, rowY + 1, barX + segW - 1, rowY + barH - 1, color);
                barX += segW;
            }
            rowY += barH + 4;
        }

        // 列車レッグタイル群
        int rowHTile = 44;
        for (int i = 0; i < legs.size() && rowY + rowHTile < y + h; i++) {
            TrainRouter.Leg leg = legs.get(i);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + rowHTile;
            int bg = hover ? 0xFF1f3a50 : 0xFF111928;
            int border = 0xFF2a4a60;
            int lineColor = TransitTerminalScreen.lineColorForLeg(leg);
            SmoothRenderer.fillRoundedRect(g, x, rowY, w, rowHTile, 5f, border);
            g.fill(x + 1, rowY + 1, x + w - 1, rowY + rowHTile - 1, bg);
            // 左端に路線色帯 (3px)
            g.fill(x + 1, rowY + 1, x + 4, rowY + rowHTile - 1, lineColor);

            // 路線記号バッジ (タイル右、垂直中央)。サイズ 24×24
            int badgeW = 0;
            if (leg.symbolNumber() >= 0 || (leg.symbolLetters() != null && !leg.symbolLetters().isEmpty())) {
                int symBadgeY = rowY + (rowHTile - 24) / 2;
                badgeW = scr.drawSymbolBadge(g, x + w - 28, symBadgeY, leg);
            }
            int rightTextEdge = x + w - (badgeW > 0 ? badgeW + 8 : 12);

            // 1 行目: 駅名 from → to
            String fromName = TransitTerminalScreen.nameOf(leg.fromGroupId());
            String toName = TransitTerminalScreen.nameOf(leg.toGroupId());
            String l1 = TransitTerminalScreen.truncate(fromName + " → " + toName, rightTextEdge - (x + 8));
            g.drawString(scr.fontAccess(), l1, x + 8, rowY + 4, 0xFFFFFFFF, false);

            // 2 行目: 発車時刻 + あと N 分
            int delaySec = Math.max(0, leg.delayTicks() / 20);
            String absDep = scr.absoluteClockOffset(leg.departureTicksFromNow() + leg.delayTicks());
            int liveTicks = scr.liveCountdownTicks(leg.departureTicksFromNow() + leg.delayTicks());
            int depSec = liveTicks / 20;
            String relDep = Component.translatable("tsu.transit_terminal.dep_in_fmt",
                    depSec / 60, depSec % 60).getString();
            g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.abs_dep_fmt", absDep), x + 8, rowY + 15, 0xFFFFD54F, false);
            int adw = scr.fontAccess().width("発 " + absDep);
            g.drawString(scr.fontAccess(), " (" + relDep + ")", x + 8 + adw, rowY + 15, 0xFFAAAAAA, false);

            // 3 行目: 走行時間 + 番線 + 遅延バッジ
            int legSec = leg.travelTicks() / 20;
            String travel = "🕐 " + Component.translatable("tsu.transit_terminal.leg_fmt_short",
                    legSec / 60, legSec % 60).getString();
            g.drawString(scr.fontAccess(), travel, x + 8, rowY + 26, 0xFF80DEEA, false);
            int travelW = scr.fontAccess().width(travel);

            int rightBadgeX = rightTextEdge;
            // 番線バッジ (記号バッジの左に置く)
            if (leg.boardPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.boardPlatform()).getString();
                int pw = scr.fontAccess().width(plat);
                int pbX = rightBadgeX - pw - 6;
                g.fill(pbX, rowY + 25, rightBadgeX, rowY + 35, 0xFF1f4f3e);
                g.drawString(scr.fontAccess(), plat, pbX + 3, rowY + 26, 0xFF80FFAA, false);
                rightBadgeX = pbX - 4;
            }
            // 遅延バッジ
            if (delaySec > 0) {
                String dlb = Component.translatable("tsu.transit_terminal.delay_fmt",
                        delaySec / 60, delaySec % 60).getString();
                int dlw = scr.fontAccess().width(dlb);
                int dlx = rightBadgeX - dlw - 6;
                g.fill(dlx, rowY + 14, rightBadgeX, rowY + 24, 0xFFAA1F1F);
                g.drawString(scr.fontAccess(), dlb, dlx + 3, rowY + 15, 0xFFFFFFFF, false);
            }
            // 信頼度
            String confLabel;
            int confColor;
            if (leg.sampleCount() >= 5) {
                confLabel = "● " + Component.translatable("tsu.transit_terminal.conf_certain").getString();
                confColor = 0xFF66BB6A;
            } else if (leg.sampleCount() >= 1) {
                confLabel = "○ " + Component.translatable("tsu.transit_terminal.conf_estimate").getString();
                confColor = 0xFFFFD54F;
            } else {
                confLabel = "△ " + Component.translatable("tsu.transit_terminal.conf_unknown").getString();
                confColor = 0xFF80808F;
            }
            int cw = scr.fontAccess().width(confLabel);
            // 信頼度は travel の右隣 (記号バッジ前まで)
            int confX = x + 8 + travelW + 6;
            if (confX + cw < rightTextEdge) {
                g.drawString(scr.fontAccess(), confLabel, confX, rowY + 26, confColor, false);
            }

            rowY += rowHTile + 2;
        }
    }

    static void renderResultDetail(TransitTerminalScreen scr, GuiGraphics g, ComposedRouteFinder.ComposedRoute r,
                                    int x, int y, int w, int h, int mouseX, int mouseY) {
        // 戻るボタン (左上)
        boolean backHover = mouseX >= x && mouseX < x + 40 && mouseY >= y && mouseY < y + 12;
        // W7-1: 戻る glyph を manta:arrow-left icon へ (control glyph、hover 色は共通)。
        int backC = backHover ? 0xFFFFD54F : 0xFF80DEEA;
        belugalab.experience.render.Icons.draw(g, "manta:arrow-left", x, y, 9, backC);
        g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.back").getString(),
                x + 11, y, backC, false);

        // 右上に 2 ボタンを横並びで配置 (重ならないよう 8px 空ける)
        boolean hudOn = TransitTerminalState.showDetailHud();
        String hudLabel = hudOn
                ? "🪟 " + Component.translatable("tsu.transit_terminal.hud_hide").getString()
                : "🪟 " + Component.translatable("tsu.transit_terminal.hud_show").getString();
        String navLabel;
        if (TransitNavClientState.active()) {
            navLabel = "🧭 " + Component.translatable("tsu.transit_terminal.nav_cancel").getString();
        } else if (TransitNavClientState.isPending()) {
            navLabel = "🧭 " + Component.translatable("tsu.transit_terminal.nav_pending").getString();
        } else {
            navLabel = "🧭 " + Component.translatable("tsu.transit_terminal.nav_start").getString();
        }
        int hudW = scr.fontAccess().width(hudLabel);
        int navW = scr.fontAccess().width(navLabel);
        int btnGap = 8;
        // HUD button (一番右)
        int hudBoxX = x + w - hudW - 6;
        boolean hudHover = mouseX >= hudBoxX && mouseX < hudBoxX + hudW + 6
                && mouseY >= y - 1 && mouseY < y + 10;
        g.fill(hudBoxX, y - 1, hudBoxX + hudW + 6, y + 10, hudOn ? 0xFF1f5e7e : 0xFF2a4a60);
        g.drawString(scr.fontAccess(), hudLabel, hudBoxX + 3, y,
                hudHover ? 0xFFFFD54F : 0xFFFFFFFF, false);

        // Nav button (HUD button の左、btnGap 空けて)
        int navBoxX = hudBoxX - navW - 6 - btnGap;
        boolean navHover = mouseX >= navBoxX && mouseX < navBoxX + navW + 6
                && mouseY >= y - 1 && mouseY < y + 10;
        g.fill(navBoxX, y - 1, navBoxX + navW + 6, y + 10,
                TransitNavClientState.active() ? 0xFFAA3F1F : 0xFF2a4a60);
        g.drawString(scr.fontAccess(), navLabel, navBoxX + 3, y,
                navHover ? 0xFFFFD54F : 0xFFFFFFFF, false);

        if (hudOn) {
            TransitTerminalState.setHudRouteIdx(TransitTerminalState.selectedRouteIdx());
        }

        int detailTextY = y + 12;

        // ナビゲーションエラー表示 (server から失敗が返ってきたとき)
        String navErr = TransitNavClientState.lastError();
        if (!navErr.isEmpty()) {
            String errMsg = "🧭 " + Component.translatable("tsu.transit_terminal.nav_error_fmt", navErr).getString();
            int eW = scr.fontAccess().width(errMsg);
            g.fill(x, y + 11, x + Math.min(w, eW + 6), y + 21, 0xCC8B0000);
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(errMsg, w - 6), x + 3, y + 12, 0xFFFFE0E0, false);
            detailTextY += 12;
        }

        // サマリ
        List<TrainRouter.Leg> legs = r.trainLegs();
        int firstDep = legs.isEmpty() ? 0 : legs.get(0).departureTicksFromNow();
        String dr = scr.absoluteClockOffset(firstDep) + " → " + scr.absoluteClockOffset(r.totalTicks());
        int totalSec = Math.max(0, (r.totalTicks() - firstDep) / 20);
        String summary = dr + " (" + (totalSec / 60) + "m " + (totalSec % 60) + "s)";
        g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(summary, w - 4), x, detailTextY, 0xFFFFFFFF, false);
        String trans = Component.translatable("tsu.transit_terminal.transfers_fmt", legs.size() - 1).getString();
        g.drawString(scr.fontAccess(), trans, x, detailTextY + 10, 0xFFAAAAAA, false);

        // タイムライン
        int dy = detailTextY + 24;
        // 列のレイアウト:
        //   x .. x+24   : 時刻 (発・着)
        //   x+26 .. +34 : 駅マーク (●) + 縦線 (║)
        //   x+38 ..     : 駅名 / 番線 / 列車名
        int timeColW = 28;
        int barColX = x + timeColW;

        for (int i = 0; i < legs.size(); i++) {
            TrainRouter.Leg leg = legs.get(i);
            int color = TransitTerminalScreen.lineColorForLeg(leg);
            int depTicks = leg.departureTicksFromNow();
            int arrTicks = depTicks + leg.travelTicks();
            String depAbs = scr.absoluteClockOffset(depTicks);
            String arrAbs = scr.absoluteClockOffset(arrTicks);
            StationGroup fromG = TransitTerminalScreen.findGroup(leg.fromGroupId());
            StationGroup toG = TransitTerminalScreen.findGroup(leg.toGroupId());

            // === 出発駅マーク ===
            // 時刻
            g.drawString(scr.fontAccess(), depAbs, x, dy, 0xFFFFD54F, false);
            // ●
            g.fill(barColX + 2, dy + 2, barColX + 9, dy + 9, 0xFF000000);
            g.fill(barColX + 3, dy + 3, barColX + 8, dy + 8, color);
            // 駅名 (路線記号バッジ用にスペース確保)
            String fromName = fromG != null ? fromG.name() : TransitTerminalScreen.nameOf(leg.fromGroupId());
            int reserveR = 50; // 番線分
            if (leg.symbolNumber() >= 0 || (leg.symbolLetters() != null && !leg.symbolLetters().isEmpty())) reserveR += 18;
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(fromName, w - timeColW - reserveR), barColX + 14, dy + 1, 0xFFFFFFFF, false);
            // 番線 (右側に小さく)
            if (leg.boardPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.boardPlatform()).getString();
                int pw = scr.fontAccess().width(plat);
                g.fill(x + w - pw - 8, dy, x + w - 2, dy + 10, 0xFF1f4f3e);
                g.drawString(scr.fontAccess(), plat, x + w - pw - 5, dy + 1, 0xFF80FFAA, false);
            }
            dy += 12;

            // === 区間 (路線色塗り縦線) ===
            int legSec = leg.travelTicks() / 20;
            int barTopY = dy;
            int legHeight = 28; // 区間表示の高さ
            // 縦線 (路線色、太さ 5px)
            g.fill(barColX + 4, barTopY, barColX + 9, barTopY + legHeight, color);
            // 路線記号バッジ (区間の右側)
            int symBadgeW = 0;
            if (leg.symbolNumber() >= 0 || (leg.symbolLetters() != null && !leg.symbolLetters().isEmpty())) {
                symBadgeW = scr.drawSymbolBadge(g, x + w - 30, barTopY + 4, leg);
            }
            // 列車種別 / 行き先
            String trainName = "🚆 " + Component.translatable("tsu.transit_terminal.detail_train_short").getString();
            if (leg.trainId() != null) {
                var snap = TransitTerminalClientCache.allSchedules().get(leg.trainId());
                if (snap != null && snap.trainName() != null && !snap.trainName().isEmpty()) {
                    trainName = "🚆 " + snap.trainName();
                }
            }
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(trainName, w - timeColW - 16 - (symBadgeW > 0 ? symBadgeW + 4 : 0)),
                    barColX + 14, barTopY + 4, 0xFFE0E0E0, false);
            // 走行時間 + 駅数
            String legInfo = Component.translatable("tsu.transit_terminal.leg_fmt_short",
                    legSec / 60, legSec % 60).getString();
            g.drawString(scr.fontAccess(), legInfo, barColX + 14, barTopY + 14, 0xFF80DEEA, false);
            dy += legHeight;

            // === 到着駅マーク ===
            g.drawString(scr.fontAccess(), arrAbs, x, dy, 0xFFFF8A65, false);
            g.fill(barColX + 2, dy + 2, barColX + 9, dy + 9, 0xFF000000);
            g.fill(barColX + 3, dy + 3, barColX + 8, dy + 8, color);
            String toName = toG != null ? toG.name() : TransitTerminalScreen.nameOf(leg.toGroupId());
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(toName, w - timeColW - 50), barColX + 14, dy + 1, 0xFFFFFFFF, false);
            if (leg.alightPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.alightPlatform()).getString();
                int pw = scr.fontAccess().width(plat);
                g.fill(x + w - pw - 8, dy, x + w - 2, dy + 10, 0xFF4f3a1f);
                g.drawString(scr.fontAccess(), plat, x + w - pw - 5, dy + 1, 0xFFFFD54F, false);
            }
            dy += 12;

            // 次の leg がある場合は乗換ブロック
            if (i + 1 < legs.size()) {
                TrainRouter.Leg next = legs.get(i + 1);
                int wait = next.departureTicksFromNow() - arrTicks;
                if (wait > 0) {
                    int waitSec = wait / 20;
                    String waitText = Component.translatable("tsu.transit_terminal.transfer_wait_fmt",
                            waitSec / 60, waitSec % 60).getString();
                    g.fill(barColX + 5, dy, barColX + 7, dy + 14, 0xFF606080);
                    // W7-1: 待機 glyph を manta:refresh-cw icon へ。
                    belugalab.experience.render.Icons.draw(g, "manta:refresh-cw", barColX + 14, dy + 2, 8, 0xFFAAAAAA);
                    g.drawString(scr.fontAccess(), waitText, barColX + 25, dy + 2, 0xFFAAAAAA, false);
                    dy += 14;
                }
            }
            dy += 2;

            // 画面下端を超えたら停止
            if (dy > y + h - 8) break;
        }
    }

    static void renderScheduleTab(TransitTerminalScreen scr, GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = scr.px + TransitTerminalScreen.CONTENT_PAD;
        int innerW = TransitTerminalScreen.PANEL_W - TransitTerminalScreen.CONTENT_PAD * 2;
        // search box already drawn by EditBox child
        // W7-1: search glyph を manta:search icon へ (control glyph)。
        belugalab.experience.render.Icons.draw(g, "manta:search", innerX + 2, y + 11, 9, 0xFF4FC3F7);

        int listY = y + 28;
        int listH = h - 30;

        var snapshots = TransitTerminalClientCache.allSchedules();
        if (snapshots.isEmpty()) {
            g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.schedule_empty").getString(),
                    innerX, listY + 4, 0xFF80808F, false);
            g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.schedule_hint").getString(),
                    innerX, listY + 16, 0xFF606080, false);
            return;
        }
        String key = TransitTerminalState.scheduleQuery().toLowerCase(java.util.Locale.ROOT);
        int rowH = 22;
        // フィルタ済み件数 を先に数えて scrollbar 用に保持
        int filteredCount = 0;
        for (var snap : snapshots.values()) {
            if (!key.isEmpty() && !snap.trainName().toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
            filteredCount++;
        }
        // スクロールバー領域確保
        int sbW = 4;
        int listInnerW = innerW - sbW - 4;
        int rowsVisible = listH / (rowH + 2);
        int maxScroll = Math.max(0, filteredCount - rowsVisible);
        int scrollY = Math.min(TransitTerminalState.scheduleScrollY(), maxScroll);
        if (scrollY != TransitTerminalState.scheduleScrollY()) {
            TransitTerminalState.setScheduleScrollY(scrollY);
        }

        int idx = 0;
        int drawn = 0;
        for (var snap : snapshots.values()) {
            if (!key.isEmpty() && !snap.trainName().toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
            if (idx++ < scrollY) continue;
            if (drawn >= rowsVisible) break;
            int rowY = listY + drawn * (rowH + 2);
            g.fill(innerX, rowY, innerX + listInnerW, rowY + rowH, 0xFF111928);
            g.fill(innerX, rowY, innerX + listInnerW, rowY + 1, 0xFF2a4a60);
            // W7-1: 列車 glyph を manta:train-front icon へ。
            belugalab.experience.render.Icons.draw(g, "manta:train-front", innerX + 2, rowY + 2, 8, 0xFFFFFFFF);
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(snap.trainName(), listInnerW - 16),
                    innerX + 13, rowY + 2, 0xFFFFFFFF, false);
            String next = snap.nextGroupId() == null ? "—" : TransitTerminalScreen.nameOf(snap.nextGroupId());
            int eta = snap.etaTicksToNext() / 20;
            String etaText = Component.translatable("tsu.transit_terminal.eta_fmt", eta).getString();
            g.drawString(scr.fontAccess(), "→ " + TransitTerminalScreen.truncate(next, listInnerW - 60),
                    innerX + 2, rowY + 12, 0xFF80DEEA, false);
            int ew = scr.fontAccess().width(etaText);
            g.drawString(scr.fontAccess(), etaText, innerX + listInnerW - ew - 2, rowY + 12, 0xFFFFD54F, false);
            drawn++;
        }

        // スクロールバー描画 (右端)
        if (filteredCount > rowsVisible) {
            int sbX = innerX + innerW - sbW;
            int sbY = listY;
            int sbHeight = listH;
            // track
            g.fill(sbX, sbY, sbX + sbW, sbY + sbHeight, 0xFF2a2a3a);
            // thumb
            int thumbH = Math.max(12, sbHeight * rowsVisible / filteredCount);
            int thumbY = sbY + (maxScroll == 0 ? 0 : (sbHeight - thumbH) * scrollY / maxScroll);
            g.fill(sbX, thumbY, sbX + sbW, thumbY + thumbH, 0xFF4FC3F7);
        }
    }

    static void renderMapTab(TransitTerminalScreen scr, GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = scr.px + TransitTerminalScreen.CONTENT_PAD;
        int innerW = TransitTerminalScreen.PANEL_W - TransitTerminalScreen.CONTENT_PAD * 2;
        // 背景
        g.fill(innerX, y + 2, innerX + innerW, y + h - 4, 0xFF0a0a18);

        var groups = TransitTerminalClientCache.allMapGroups();
        var segments = TransitTerminalClientCache.mapSegments();

        // データ無い場合の早期 return + 初期 fit
        if (segments.isEmpty() && groups.isEmpty()) {
            String msg = Component.translatable("tsu.transit_terminal.map_empty").getString();
            int mw = scr.fontAccess().width(msg);
            g.drawString(scr.fontAccess(), msg, innerX + (innerW - mw) / 2, y + h / 2 - 4, 0xFF80808F, false);
            return;
        }

        // 初回 fit: pan = -centerPos, zoom は range に合わせて自動。
        // mapZoom == 0 を「未初期化フラグ」として使う。
        if (TransitTerminalState.mapZoomD() <= 0.0001) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var grp : groups) {
                double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
                double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                if (cz < minZ) minZ = cz; if (cz > maxZ) maxZ = cz;
            }
            for (int[] s : segments) {
                if (s[0] < minX) minX = s[0]; if (s[0] > maxX) maxX = s[0];
                if (s[2] < minX) minX = s[2]; if (s[2] > maxX) maxX = s[2];
                if (s[1] < minZ) minZ = s[1]; if (s[1] > maxZ) maxZ = s[1];
                if (s[3] < minZ) minZ = s[3]; if (s[3] > maxZ) maxZ = s[3];
            }
            if (minX != Double.MAX_VALUE) {
                double cx = (minX + maxX) / 2.0;
                double cz = (minZ + maxZ) / 2.0;
                double rangeX = Math.max(20, (maxX - minX) + 20);
                double rangeZ = Math.max(20, (maxZ - minZ) + 20);
                double zoom = Math.min((innerW - 8) / rangeX, (h - 24) / rangeZ);
                zoom = Math.max(0.05, Math.min(5.0, zoom));
                TransitTerminalState.setMapZoomD(zoom);
                TransitTerminalState.setMapPan(-cx, -cz);
            }
        }

        double mapZoom = TransitTerminalState.mapZoomD();
        double mapPanX = TransitTerminalState.mapPanXD();
        double mapPanZ = TransitTerminalState.mapPanZD();
        double centerSX = innerX + innerW / 2.0;
        double centerSY = y + h / 2.0;

        // Scissor: パネル領域内にクリップ
        var msc = g.pose().last().pose();
        float mscX = msc.m00(), mscY = msc.m11();
        int mscTx = (int) msc.m30(), mscTy = (int) msc.m31();
        g.enableScissor((int) (innerX * mscX) + mscTx, (int) ((y + 2) * mscY) + mscTy,
                        (int) ((innerX + innerW) * mscX) + mscTx, (int) ((y + h - 4) * mscY) + mscTy);

        // 線路セグメント (vector)
        var vc = belugalab.mcss3.draw.VectorRenderer.getGuiBuffer(g.bufferSource());
        var matrix = g.pose().last().pose();
        for (int[] s : segments) {
            float x1 = (float) (centerSX + (s[0] + mapPanX) * mapZoom);
            float y1 = (float) (centerSY + (s[1] + mapPanZ) * mapZoom);
            float x2 = (float) (centerSX + (s[2] + mapPanX) * mapZoom);
            float y2 = (float) (centerSY + (s[3] + mapPanZ) * mapZoom);
            belugalab.mcss3.draw.VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, 0xFF6688AA, 2.0f);
        }
        g.bufferSource().endBatch();

        // 列車のリアルタイム位置 (オレンジドット、線速度方向に短い線も)
        var trainPositions = TransitTerminalClientCache.trainPositions();
        if (!trainPositions.isEmpty()) {
            for (var pos : trainPositions.values()) {
                int tx = (int) (centerSX + (pos.x() + mapPanX) * mapZoom);
                int tz = (int) (centerSY + (pos.z() + mapPanZ) * mapZoom);
                g.fill(tx - 3, tz - 3, tx + 4, tz + 4, 0xFF000000);
                g.fill(tx - 2, tz - 2, tx + 3, tz + 3, 0xFFFF9800);
            }
        }

        // プレイヤー位置 (黄色ドット)
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            int dotX = (int) (centerSX + (mc.player.getX() + mapPanX) * mapZoom);
            int dotZ = (int) (centerSY + (mc.player.getZ() + mapPanZ) * mapZoom);
            g.fill(dotX - 3, dotZ - 3, dotX + 4, dotZ + 4, 0xFF000000);
            g.fill(dotX - 2, dotZ - 2, dotX + 3, dotZ + 3, 0xFFFFD54F);
        }

        // 駅ドット (ホバーで駅名)
        StringBuilder hoverName = null;
        int hoverX = 0, hoverY = 0;
        for (var grp : groups) {
            double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
            double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
            int dx = (int) (centerSX + (cx + mapPanX) * mapZoom);
            int dz = (int) (centerSY + (cz + mapPanZ) * mapZoom);
            g.fill(dx - 3, dz - 3, dx + 4, dz + 4, 0xFF1a1a2e);
            g.fill(dx - 2, dz - 2, dx + 3, dz + 3, 0xFF4FC3F7);
            if (mapZoom > 0.5) {
                g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(grp.name(), 60), dx + 5, dz - 4, 0xFF4fc3f7, true);
            }
            if (mouseX >= dx - 4 && mouseX <= dx + 4 && mouseY >= dz - 4 && mouseY <= dz + 4) {
                hoverName = new StringBuilder(grp.name());
                hoverX = dx; hoverY = dz;
            }
        }

        g.disableScissor();

        // Hover tooltip (scissor 外でも見えるよう scissor 終了後)
        if (hoverName != null) {
            String n = TransitTerminalScreen.truncate(hoverName.toString(), 100);
            int w = scr.fontAccess().width(n);
            g.fill(hoverX + 4, hoverY - 6, hoverX + 8 + w, hoverY + 4, 0xFF000000);
            g.drawString(scr.fontAccess(), n, hoverX + 6, hoverY - 4, 0xFFFFFFFF, false);
        }

        // ズーム表示 + ヒント
        String zoom = String.format("x%.2f", mapZoom);
        g.drawString(scr.fontAccess(), zoom, innerX + 4, y + h - 14, 0xFF80808F, false);
        if (!segments.isEmpty()) {
            String segInfo = segments.size() + " seg";
            int sw = scr.fontAccess().width(segInfo);
            g.drawString(scr.fontAccess(), segInfo, innerX + innerW - sw - 4, y + h - 14, 0xFF80808F, false);
        }
        g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.map_hint").getString(),
                innerX + 4, y + 4, 0xFF606080, false);
    }

    static void renderSettingsTab(TransitTerminalScreen scr, GuiGraphics g, int mouseX, int mouseY, int y, int h) {
        int innerX = scr.px + TransitTerminalScreen.CONTENT_PAD;
        int innerW = TransitTerminalScreen.PANEL_W - TransitTerminalScreen.CONTENT_PAD * 2;
        int rowY = y + 8;
        int rowH = 18;

        rowY = scr.renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_clock24",
                TransitTerminalState.clock24h());
        rowY += 4;
        rowY = scr.renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_walk_gate",
                TransitTerminalState.walkGateEnabled());
        rowY += 4;
        rowY = scr.renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_layout_adjust",
                TransitTerminalState.layoutAdjustMode());
        rowY += 4;
        rowY = scr.renderSettingRow(g, innerX, rowY, innerW, rowH, mouseX, mouseY,
                "tsu.transit_terminal.setting_show_hud",
                TransitTerminalState.showDetailHud());

        rowY += 8;
        // 位置リセットボタン
        boolean rstHover = mouseX >= innerX && mouseX < innerX + innerW
                && mouseY >= rowY && mouseY < rowY + 14;
        g.fill(innerX, rowY, innerX + innerW, rowY + 14, 0xFF333344);
        g.fill(innerX + 1, rowY + 1, innerX + innerW - 1, rowY + 13, rstHover ? 0xFF1f3a50 : 0xFF111928);
        String rstLabel = Component.translatable("tsu.transit_terminal.setting_reset_layout").getString();
        int rstW = scr.fontAccess().width(rstLabel);
        g.drawString(scr.fontAccess(), rstLabel, innerX + (innerW - rstW) / 2, rowY + 3, 0xFFAAAAAA, false);
        rowY += 18;

        g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.setting_about").getString(),
                innerX, rowY, 0xFF80808F, false);
    }

    static void drawMapCanvas(TransitTerminalScreen scr, GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var groups = TransitTerminalClientCache.allMapGroups();
        var segments = TransitTerminalClientCache.mapSegments();
        // データなし
        if (segments.isEmpty() && groups.isEmpty()) {
            String msg = Component.translatable("tsu.transit_terminal.map_empty").getString();
            int mw = scr.fontAccess().width(msg);
            g.drawString(scr.fontAccess(), msg, x + (w - mw) / 2, y + h / 2 - 4, 0xFF80808F, false);
            return;
        }
        // 初回 fit
        if (TransitTerminalState.mapZoomD() <= 0.0001) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var grp : groups) {
                double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
                double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
                if (cz < minZ) minZ = cz; if (cz > maxZ) maxZ = cz;
            }
            for (int[] s : segments) {
                if (s[0] < minX) minX = s[0]; if (s[0] > maxX) maxX = s[0];
                if (s[2] < minX) minX = s[2]; if (s[2] > maxX) maxX = s[2];
                if (s[1] < minZ) minZ = s[1]; if (s[1] > maxZ) maxZ = s[1];
                if (s[3] < minZ) minZ = s[3]; if (s[3] > maxZ) maxZ = s[3];
            }
            if (minX != Double.MAX_VALUE) {
                double cx = (minX + maxX) / 2.0;
                double cz = (minZ + maxZ) / 2.0;
                double rangeX = Math.max(20, (maxX - minX) + 20);
                double rangeZ = Math.max(20, (maxZ - minZ) + 20);
                double zoom = Math.min((w - 8) / rangeX, (h - 24) / rangeZ);
                zoom = Math.max(0.05, Math.min(5.0, zoom));
                TransitTerminalState.setMapZoomD(zoom);
                TransitTerminalState.setMapPan(-cx, -cz);
            }
        }
        double mapZoom = TransitTerminalState.mapZoomD();
        double mapPanX = TransitTerminalState.mapPanXD();
        double mapPanZ = TransitTerminalState.mapPanZD();
        double centerSX = x + w / 2.0;
        double centerSY = y + h / 2.0;
        // bg
        g.fill(x, y, x + w, y + h, 0xFF0a0a18);
        // scissor は screen 座標で渡す必要あり (pose translate を加算)
        var sm = g.pose().last().pose();
        float smX = sm.m00(), smY = sm.m11();
        int sx = (int) sm.m30();
        int sy = (int) sm.m31();
        g.flush();
        g.enableScissor((int) (x * smX) + sx, (int) (y * smY) + sy,
                        (int) ((x + w) * smX) + sx, (int) ((y + h) * smY) + sy);
        // 線路セグメント
        var vc = belugalab.mcss3.draw.VectorRenderer.getGuiBuffer(g.bufferSource());
        var matrix = g.pose().last().pose();
        for (int[] s : segments) {
            float x1 = (float) (centerSX + (s[0] + mapPanX) * mapZoom);
            float y1 = (float) (centerSY + (s[1] + mapPanZ) * mapZoom);
            float x2 = (float) (centerSX + (s[2] + mapPanX) * mapZoom);
            float y2 = (float) (centerSY + (s[3] + mapPanZ) * mapZoom);
            belugalab.mcss3.draw.VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, 0xFF6688AA, 2.0f);
        }
        g.bufferSource().endBatch();
        // 列車
        var trainPositions = TransitTerminalClientCache.trainPositions();
        for (var pos : trainPositions.values()) {
            int tx = (int) (centerSX + (pos.x() + mapPanX) * mapZoom);
            int tz = (int) (centerSY + (pos.z() + mapPanZ) * mapZoom);
            g.fill(tx - 3, tz - 3, tx + 4, tz + 4, 0xFF000000);
            g.fill(tx - 2, tz - 2, tx + 3, tz + 3, 0xFFFF9800);
        }
        // プレイヤー
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            int dotX = (int) (centerSX + (mc.player.getX() + mapPanX) * mapZoom);
            int dotZ = (int) (centerSY + (mc.player.getZ() + mapPanZ) * mapZoom);
            g.fill(dotX - 3, dotZ - 3, dotX + 4, dotZ + 4, 0xFF000000);
            g.fill(dotX - 2, dotZ - 2, dotX + 3, dotZ + 3, 0xFFFFD54F);
        }
        // 駅 + ホバー
        StringBuilder hoverName = null;
        int hoverX = 0, hoverY = 0;
        for (var grp : groups) {
            double cx = (grp.minPos().getX() + grp.maxPos().getX()) / 2.0;
            double cz = (grp.minPos().getZ() + grp.maxPos().getZ()) / 2.0;
            int dx = (int) (centerSX + (cx + mapPanX) * mapZoom);
            int dz = (int) (centerSY + (cz + mapPanZ) * mapZoom);
            g.fill(dx - 3, dz - 3, dx + 4, dz + 4, 0xFF1a1a2e);
            g.fill(dx - 2, dz - 2, dx + 3, dz + 3, 0xFF4FC3F7);
            if (mapZoom > 0.5) {
                g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(grp.name(), 60), dx + 5, dz - 4, 0xFF4fc3f7, true);
            }
            if (mouseX >= dx - 4 && mouseX <= dx + 4 && mouseY >= dz - 4 && mouseY <= dz + 4) {
                hoverName = new StringBuilder(grp.name());
                hoverX = dx; hoverY = dz;
            }
        }
        g.flush();
        g.disableScissor();
        // hover tooltip
        if (hoverName != null) {
            String n = TransitTerminalScreen.truncate(hoverName.toString(), 100);
            int tw = scr.fontAccess().width(n);
            g.fill(hoverX + 4, hoverY - 6, hoverX + 8 + tw, hoverY + 4, 0xFF000000);
            g.drawString(scr.fontAccess(), n, hoverX + 6, hoverY - 4, 0xFFFFFFFF, false);
        }
    }

    static void drawScheduleListCanvas(TransitTerminalScreen scr, GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        var snapshots = TransitTerminalClientCache.allSchedules();
        if (snapshots.isEmpty()) {
            g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.schedule_empty").getString(),
                    x, y + 4, 0xFF80808F, false);
            g.drawString(scr.fontAccess(), Component.translatable("tsu.transit_terminal.schedule_hint").getString(),
                    x, y + 16, 0xFF606080, false);
            return;
        }
        String key = TransitTerminalState.scheduleQuery().toLowerCase(java.util.Locale.ROOT);
        int filteredCount = scr.scheduleFilteredCount();
        int maxScroll = Math.max(0, filteredCount - TransitTerminalScreen.SCHEDULE_ROWS_VISIBLE);
        int scrollY = Math.min(TransitTerminalState.scheduleScrollY(), maxScroll);
        if (scrollY != TransitTerminalState.scheduleScrollY()) {
            TransitTerminalState.setScheduleScrollY(scrollY);
        }
        int listInnerW = w;  // canvas full width (scrollbar は隣接 canvas)
        int idx = 0, drawn = 0;
        for (var snap : snapshots.values()) {
            if (!key.isEmpty() && !snap.trainName().toLowerCase(java.util.Locale.ROOT).contains(key)) continue;
            if (idx++ < scrollY) continue;
            if (drawn >= TransitTerminalScreen.SCHEDULE_ROWS_VISIBLE) break;
            int rowY = y + drawn * TransitTerminalScreen.SCHEDULE_ROW_STRIDE;
            g.fill(x, rowY, x + listInnerW, rowY + TransitTerminalScreen.SCHEDULE_ROW_H, 0xFF111928);
            g.fill(x, rowY, x + listInnerW, rowY + 1, 0xFF2a4a60);
            // W7-1: 列車 glyph を manta:train-front icon へ。
            belugalab.experience.render.Icons.draw(g, "manta:train-front", x + 2, rowY + 2, 8, 0xFFFFFFFF);
            g.drawString(scr.fontAccess(), TransitTerminalScreen.truncate(snap.trainName(), listInnerW - 16),
                    x + 13, rowY + 2, 0xFFFFFFFF, false);
            String next = snap.nextGroupId() == null ? "—" : TransitTerminalScreen.nameOf(snap.nextGroupId());
            int eta = snap.etaTicksToNext() / 20;
            String etaText = Component.translatable("tsu.transit_terminal.eta_fmt", eta).getString();
            g.drawString(scr.fontAccess(), "→ " + TransitTerminalScreen.truncate(next, listInnerW - 60),
                    x + 2, rowY + 12, 0xFF80DEEA, false);
            int ew = scr.fontAccess().width(etaText);
            g.drawString(scr.fontAccess(), etaText, x + listInnerW - ew - 2, rowY + 12, 0xFFFFD54F, false);
            drawn++;
        }
    }

    static boolean mouseClickedTop(TransitTerminalScreen scr, double mouseX, double mouseY, int button) {
        int contentY = scr.py + TransitTerminalScreen.HEADER_H + 2;
        int innerX = scr.px + TransitTerminalScreen.CONTENT_PAD;
        int innerW = TransitTerminalScreen.PANEL_W - TransitTerminalScreen.CONTENT_PAD * 2;
        int boxY = contentY + 8;

        // Swap ボタン
        int swapX = innerX + innerW - 16;
        int swapY = boxY + 7;
        if (mouseX >= swapX && mouseX < swapX + 14 && mouseY >= swapY && mouseY < swapY + 22) {
            TransitTerminalState.swapFromTo();
            scr.rebuildEditBoxes();
            return true;
        }

        // 検索ボタン
        int btnY = boxY + 38;
        int btnH = 16;
        if (mouseX >= innerX && mouseX < innerX + innerW && mouseY >= btnY && mouseY < btnY + btnH) {
            TransitTerminalState.onSearchSubmit();
            return true;
        }

        var r = TransitTerminalState.lastResult();
        int resY = btnY + btnH + 6;
        // 結果クリアボタンのクリック判定 (結果あり時のみ)
        if (r != null) {
            // W7-1: 描画側と同じ式で幅を出す (icon + gap + text)。
            String clearText = Component.translatable("tsu.transit_terminal.clear_results").getString();
            int clW = CLEAR_ICON_SIZE + CLEAR_ICON_GAP + scr.fontAccess().width(clearText);
            if (mouseX >= innerX + innerW - clW - 6 && mouseX < innerX + innerW
                    && mouseY >= resY - 1 && mouseY < resY + 11) {
                TransitTerminalState.clearResults();
                return true;
            }
            resY += 14;
        }

        // ソート/ルートタブ (結果あり時のみ)
        if (r != null && r.found()) {
            int tabsH = 14;
            if (mouseY >= resY && mouseY < resY + tabsH) {
                var routes = TransitTerminalState.lastResults();
                int n = Math.min(3, routes.size());
                if (n >= 2) {
                    // ルート候補タブ
                    int cellW = innerW / n;
                    int relX = (int)(mouseX - innerX);
                    if (relX >= 0 && relX < cellW * n) {
                        int idx = relX / cellW;
                        TransitTerminalState.setSelectedRouteIdx(idx);
                        return true;
                    }
                } else {
                    // 早/楽/安タブ
                    int cellW = innerW / 3;
                    int relX = (int)(mouseX - innerX);
                    if (relX >= 0 && relX < cellW * 3) {
                        int idx = relX / cellW;
                        TransitTerminalState.setSortMode(idx);
                        return true;
                    }
                }
            }
            resY += tabsH + 4;
        }

        // 結果がある場合
        if (r != null && r.found()) {
            if (TransitTerminalState.expandedLegIdx() >= 0) {
                // 詳細画面: 戻るボタン
                if (mouseX >= innerX && mouseX < innerX + 40 && mouseY >= resY && mouseY < resY + 12) {
                    TransitTerminalState.setExpandedLegIdx(-1);
                    return true;
                }
                // HUD 展開ボタン / Nav ボタン (横並び、render と同じ計算)
                String hudLabel = TransitTerminalState.showDetailHud()
                        ? "🪟 " + Component.translatable("tsu.transit_terminal.hud_hide").getString()
                        : "🪟 " + Component.translatable("tsu.transit_terminal.hud_show").getString();
                String navLabel = TransitNavClientState.active()
                        ? "🧭 " + Component.translatable("tsu.transit_terminal.nav_cancel").getString()
                        : "🧭 " + Component.translatable("tsu.transit_terminal.nav_start").getString();
                int hudW = scr.fontAccess().width(hudLabel);
                int navW = scr.fontAccess().width(navLabel);
                int btnGap = 8;
                int hudBoxX = innerX + innerW - hudW - 6;
                int navBoxX = hudBoxX - navW - 6 - btnGap;
                // HUD クリック
                if (mouseX >= hudBoxX && mouseX < hudBoxX + hudW + 6
                        && mouseY >= resY - 1 && mouseY < resY + 10) {
                    TransitTerminalState.setShowDetailHud(!TransitTerminalState.showDetailHud());
                    if (TransitTerminalState.showDetailHud()) {
                        TransitTerminalState.setHudRouteIdx(TransitTerminalState.selectedRouteIdx());
                    }
                    return true;
                }
                // Nav クリック
                if (mouseX >= navBoxX && mouseX < navBoxX + navW + 6
                        && mouseY >= resY - 1 && mouseY < resY + 10) {
                    if (TransitNavClientState.active()) {
                        TransitNavClientState.clear();
                    } else if (TransitNavClientState.isPending()) {
                        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                                "[NavPath] click ignored (request still pending)");
                    } else {
                        var legs = r.trainLegs();
                        UUID target = legs.isEmpty() ? r.fromGroupId() : legs.get(0).fromGroupId();
                        int platform = legs.isEmpty() ? 0 : legs.get(0).boardPlatform();
                        // チェイン: 第 1 leg の出発駅 (= 今からナビ) 以降、各乗換駅を queue へ
                        TransitNavClientState.clearChain();
                        for (int i = 1; i < legs.size(); i++) {
                            var leg = legs.get(i);
                            TransitNavClientState.enqueueChain(
                                    new TransitNavClientState.ChainedTarget(
                                            leg.fromGroupId(), leg.boardPlatform(), ""));
                        }
                        // 最終目的地 (最後の leg の到着駅) も queue 末尾に追加
                        if (!legs.isEmpty()) {
                            var lastLeg = legs.get(legs.size() - 1);
                            TransitNavClientState.enqueueChain(
                                    new TransitNavClientState.ChainedTarget(
                                            lastLeg.toGroupId(), lastLeg.alightPlatform(), ""));
                        }
                        TransitNavClientState.markPending();
                        TransitNavClientState.setRequestedPlatform(platform);
                        com.trainsystemutilities.network.NavPathRequestPayload.send(target, platform);
                        com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                                "[NavPath] sent request, target={} platform={} chain={}",
                                target, platform, TransitNavClientState.chainSize());
                    }
                    return true;
                }
            } else {
                // タイル一覧 (新レイアウト: header 22 + 路線色帯 8 + 4 + tile 44+2)
                int rowY = resY + 22 + 4;
                if (r.walkToFrom() != null && r.walkToFrom().approxTicks() > 0) rowY += 12;
                rowY += 8 + 4; // 路線色バー
                int rowH = 44;
                List<TrainRouter.Leg> legs = r.trainLegs();
                for (int i = 0; i < legs.size(); i++) {
                    if (mouseX >= innerX && mouseX < innerX + innerW && mouseY >= rowY && mouseY < rowY + rowH) {
                        TransitTerminalState.setExpandedLegIdx(i);
                        return true;
                    }
                    rowY += rowH + 2;
                }
            }
        } else {
            // 履歴クリック (検索前)
            var history = TransitTerminalState.history();
            if (!history.isEmpty()) {
                // ヘッダ「全消去」ボタン
                String clearAll = Component.translatable("tsu.transit_terminal.history_clear_all").getString();
                int caW = scr.fontAccess().width(clearAll);
                if (mouseX >= innerX + innerW - caW - 4 && mouseX < innerX + innerW
                        && mouseY >= resY - 1 && mouseY < resY + 9) {
                    TransitTerminalState.clearHistory();
                    return true;
                }
                int rowY = resY + 12;
                int rowH = 22;
                int delBtnW = 20;
                for (int i = 0; i < history.size(); i++) {
                    // 削除 (×) ボタン
                    int delX = innerX + innerW - delBtnW;
                    if (mouseX >= delX && mouseX < delX + delBtnW
                            && mouseY >= rowY && mouseY < rowY + rowH) {
                        TransitTerminalState.removeHistoryAt(i);
                        return true;
                    }
                    // 行本体クリック
                    if (mouseX >= innerX && mouseX < innerX + innerW - delBtnW - 2
                            && mouseY >= rowY && mouseY < rowY + rowH) {
                        var e = history.get(i);
                        scr.fromCtrl.setValue(e.fromName());   // onChange が setFromQuery
                        scr.toCtrl.setValue(e.toName());       // onChange が setToQuery
                        TransitTerminalState.onSearchSubmit();
                        return true;
                    }
                    rowY += rowH + 2;
                }
            }
        }
        return false;
    }
}
