package com.trainsystemutilities.client.transit;

import belugalab.mcss3.draw.SmoothRenderer;
import belugalab.tsu.api.HudAnimState;
import belugalab.tsu.api.HudChrome;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.UUID;

/**
 * 検索結果の詳細タイムラインを画面左下にスタンドアロン HUD として表示する。
 *
 * <p>{@link TransitTerminalState#showDetailHud()} が true のとき、Screen 表示の
 * 有無に関わらず常時描画される。HUD では選択中のルート ({@code hudRouteIdx}) の
 * 縦タイムラインを Yahoo!乗換案内風に出す。
 *
 * <p>位置調整モード ({@code layoutAdjustMode}) ON のときは、ドラッグで位置を
 * 移動できる (mouseHandler 経由でない単純実装: ScreenEvent 系で実装すると重いので、
 * 端末 Screen 側から HUD オフセットを直接更新するのに任せる。HUD 自体はドラッグ
 * 検出を持たない。代わりに右上に "リセット" ヒントを出す)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class TransitDetailHudRenderer {

    private static final int HUD_W = 200;
    private static final int HUD_H = 220;
    private static final int LEFT_MARGIN = 8;
    private static final int BOTTOM_MARGIN = 8;
    private static final long ENTRY_ANIM_NANOS = 300_000_000L; // 0.30s
    private static final long EXIT_ANIM_NANOS  = 250_000_000L; // 0.25s

    private static final HudAnimState anim = new HudAnimState(ENTRY_ANIM_NANOS, EXIT_ANIM_NANOS);
    /** 退場アニメ中も描画できるよう、 最後に表示した route を保持する。 */
    private static ComposedRouteFinder.ComposedRoute lastRoute = null;

    private TransitDetailHudRenderer() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        // detail HUD は端末 Screen の上に重ねるのが設計意図 (Javadoc 参照)。 ただし
        // inventory / pause 等 無関係な screen の上には出さない (= R2.3.2 の主旨)。
        if (mc.screen != null && !(mc.screen instanceof TransitTerminalScreen)) return;

        var routes = TransitTerminalState.lastResults();
        int idx = routes.isEmpty() ? -1
                : Math.max(0, Math.min(routes.size() - 1, TransitTerminalState.hudRouteIdx()));
        ComposedRouteFinder.ComposedRoute route = idx >= 0 ? routes.get(idx) : null;
        boolean show = TransitTerminalState.showDetailHud() && route != null && route.found();
        if (show) lastRoute = route;

        anim.update(show);                          // R2.3.3: 毎フレーム呼ぶ
        if (!anim.shouldRender()) return;           // R2.3.4

        ComposedRouteFinder.ComposedRoute draw = show ? route : lastRoute;
        if (draw == null) return;

        float fade = anim.fade();
        int slide = (int) ((show ? (1f - fade) : anim.exitEased()) * 20f); // R2.6: 下から/下へ 20px

        GuiGraphics g = event.getGuiGraphics();
        int sh = g.guiHeight();
        int x = LEFT_MARGIN + TransitTerminalState.hudOffsetX();
        int y = sh - HUD_H - BOTTOM_MARGIN + TransitTerminalState.hudOffsetY() + slide;

        // 「常にサイズ2相当」: 左下アンカーを pivot に counter-scale (G=2 で無変更)。
        HudChrome.pushUiScale(g, (float) x, (float) (y + HUD_H));
        renderPanel(g, mc, draw, x, y, fade);

        // レイアウト調整モード時は枠を強調 + ヘルプ表示
        if (show && TransitTerminalState.layoutAdjustMode()) {
            SmoothRenderer.strokeRoundedRect(g, x - 2, y - 2, HUD_W + 4, HUD_H + 4, 8f, 2f, fa(0xFFFFD54F, fade));
            String hint = Component.translatable("tsu.transit_terminal.hud_drag_hint").getString();
            g.drawString(mc.font, hint, x, y - 12, fa(0xFFFFD54F, fade), true);
        }
        HudChrome.popUiScale(g);
    }

    /** alpha チャンネルに fade を掛ける (= R2.5.2: alpha は fade 連動。 実装は HudChrome に集約)。 */
    private static int fa(int argb, float fade) {
        return belugalab.tsu.api.HudChrome.fadeAlpha(argb, fade);
    }

    private static void renderPanel(GuiGraphics g, Minecraft mc,
                                    ComposedRouteFinder.ComposedRoute route, int x, int y, float fade) {
        // 半透明パネル
        HudChrome.drawRoundedRect(g, x, y, HUD_W, HUD_H, fa(0xE612122A, fade), fa(0xFF4FC3F7, fade));

        int innerX = x + 8;
        int innerW = HUD_W - 16;
        int dy = y + 6;

        // ヘッダ: 戻る ボタンっぽいダミー (実際の interaction は Screen 側で)
        g.drawString(mc.font, "← " + Component.translatable("tsu.transit_terminal.hud_title").getString(),
                innerX, dy, fa(0xFF80DEEA, fade), false);
        dy += 12;

        // サマリ
        var legs = route.trainLegs();
        int firstDep = legs.isEmpty() ? 0 : legs.get(0).departureTicksFromNow();
        long base = TransitTerminalState.lastResultBaseDayTime();
        String depAbs = clockOf(base + firstDep);
        String arrAbs = clockOf(base + route.totalTicks());
        int totalSec = Math.max(0, (route.totalTicks() - firstDep) / 20);
        String summary = depAbs + " → " + arrAbs + " (" + (totalSec / 60) + "m " + (totalSec % 60) + "s)";
        g.drawString(mc.font, summary, innerX, dy, fa(0xFFFFFFFF, fade), false);
        dy += 11;
        String trans = Component.translatable("tsu.transit_terminal.transfers_fmt", legs.size() - 1).getString();
        g.drawString(mc.font, trans, innerX, dy, fa(0xFFAAAAAA, fade), false);
        dy += 16;

        // タイムライン
        int timeColW = 28;
        int barColX = innerX + timeColW;
        for (int i = 0; i < legs.size() && dy + 12 < y + HUD_H; i++) {
            TrainRouter.Leg leg = legs.get(i);
            int color = lineColor(leg);
            int depTicks = leg.departureTicksFromNow();
            int arrTicks = depTicks + leg.travelTicks();

            // 出発駅
            g.drawString(mc.font, clockOf(base + depTicks), innerX, dy, fa(0xFFFFD54F, fade), false);
            SmoothRenderer.fillRoundedRect(g, barColX + 2, dy + 2, 7, 7, 5f, fa(0xFF000000, fade));
            SmoothRenderer.fillRoundedRect(g, barColX + 3, dy + 3, 5, 5, 4f, fa(color, fade));
            String fromName = nameOf(leg.fromGroupId());
            g.drawString(mc.font, truncate(mc, fromName, HUD_W - timeColW - 50),
                    barColX + 14, dy + 1, fa(0xFFFFFFFF, fade), false);
            if (leg.boardPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.boardPlatform()).getString();
                int pw = mc.font.width(plat);
                SmoothRenderer.fillRoundedRect(g, x + HUD_W - pw - 14, dy, pw + 8, 10, 5f, fa(0xFF1f4f3e, fade));
                g.drawString(mc.font, plat, x + HUD_W - pw - 11, dy + 1, fa(0xFF80FFAA, fade), false);
            }
            dy += 12;

            // 区間
            int legSec = leg.travelTicks() / 20;
            int barTopY = dy;
            int legHeight = 24;
            SmoothRenderer.fillRect(g, barColX + 4, barTopY, 5, legHeight, fa(color, fade));
            String trainName = "🚆 ";
            if (leg.trainId() != null) {
                var snap = TransitTerminalClientCache.allSchedules().get(leg.trainId());
                if (snap != null && snap.trainName() != null && !snap.trainName().isEmpty()) {
                    trainName += snap.trainName();
                } else {
                    trainName += Component.translatable("tsu.transit_terminal.detail_train_short").getString();
                }
            }
            g.drawString(mc.font, truncate(mc, trainName, HUD_W - timeColW - 16),
                    barColX + 14, barTopY + 4, fa(0xFFE0E0E0, fade), false);
            String legInfo = (legSec / 60) + "m " + (legSec % 60) + "s";
            g.drawString(mc.font, legInfo, barColX + 14, barTopY + 14, fa(0xFF80DEEA, fade), false);
            dy += legHeight;

            // 到着駅
            g.drawString(mc.font, clockOf(base + arrTicks), innerX, dy, fa(0xFFFF8A65, fade), false);
            SmoothRenderer.fillRoundedRect(g, barColX + 2, dy + 2, 7, 7, 5f, fa(0xFF000000, fade));
            SmoothRenderer.fillRoundedRect(g, barColX + 3, dy + 3, 5, 5, 4f, fa(color, fade));
            String toName = nameOf(leg.toGroupId());
            g.drawString(mc.font, truncate(mc, toName, HUD_W - timeColW - 50),
                    barColX + 14, dy + 1, fa(0xFFFFFFFF, fade), false);
            if (leg.alightPlatform() > 0) {
                String plat = Component.translatable("tsu.transit_terminal.platform_fmt",
                        leg.alightPlatform()).getString();
                int pw = mc.font.width(plat);
                SmoothRenderer.fillRoundedRect(g, x + HUD_W - pw - 14, dy, pw + 8, 10, 5f, fa(0xFF4f3a1f, fade));
                g.drawString(mc.font, plat, x + HUD_W - pw - 11, dy + 1, fa(0xFFFFD54F, fade), false);
            }
            dy += 12;

            // 乗換ブロック
            if (i + 1 < legs.size()) {
                TrainRouter.Leg next = legs.get(i + 1);
                int wait = next.departureTicksFromNow() - arrTicks;
                if (wait > 0) {
                    int waitSec = wait / 20;
                    String waitText = Component.translatable("tsu.transit_terminal.transfer_wait_fmt",
                            waitSec / 60, waitSec % 60).getString();
                    SmoothRenderer.fillRect(g, barColX + 5, dy, 2, 10, fa(0xFF606080, fade));
                    g.drawString(mc.font, "🔄 " + waitText, barColX + 14, dy + 2, fa(0xFFAAAAAA, fade), false);
                    dy += 12;
                }
            }
            dy += 2;
        }
    }

    private static String clockOf(long dayTime) {
        long t = ((dayTime % 24000L) + 24000L) % 24000L;
        long minutesInDay = (long) ((t / 24000.0) * 24 * 60);
        long mcMinutes = (minutesInDay + 6 * 60) % (24 * 60);
        long hours = mcMinutes / 60;
        long mins = mcMinutes % 60;
        return String.format("%02d:%02d", hours, mins);
    }

    private static int lineColor(TrainRouter.Leg leg) {
        if (leg == null) return 0xFF4FC3F7;
        String c = leg.symbolColor();
        if (c != null && c.startsWith("#") && c.length() == 7) {
            try { return 0xFF000000 | Integer.parseInt(c.substring(1), 16); }
            catch (NumberFormatException ignored) {}
        }
        if (leg.trainId() == null) return 0xFF4FC3F7;
        long h = leg.trainId().getMostSignificantBits() ^ leg.trainId().getLeastSignificantBits();
        return hsvToColor((Math.abs(h) % 360) / 360f);
    }

    private static int hsvToColor(float h) {
        float s = 0.65f, v = 0.85f;
        float r = 0, g = 0, b = 0;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            case 5 -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(b * 255);
    }

    private static String nameOf(UUID id) {
        if (id == null) return "?";
        for (var grp : com.trainsystemutilities.station.StationGroupClientCache.all()) {
            if (grp.id().equals(id)) return grp.name();
        }
        return id.toString().substring(0, 6);
    }

    private static String truncate(Minecraft mc, String s, int w) {
        return belugalab.tsu.api.HudText.clip(mc.font, s, w);
    }
}
