package com.trainsystemutilities.client.gui;

import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import com.trainsystemutilities.client.electrification.ClientTrainElectrificationCache;
import com.trainsystemutilities.electrification.contraption.TrainElectrificationView;
import com.trainsystemutilities.network.PantographTogglePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * 電化詳細スクリーン (Phase 24)。
 *
 * <p>1 列車に対して:
 * <ul>
 *   <li>全パンタ一括 deploy / fold ボタン</li>
 *   <li>car-list canvas: 各 car の 2D サイドビュー (車輪 / インバータハイライト / パンタ)</li>
 *   <li>パンタクリックで個別 toggle</li>
 * </ul>
 *
 * <p>{@link ClientTrainElectrificationCache} からデータを読み、操作は
 * {@link PantographTogglePayload} でサーバへ送信。サーバが状態更新後、次の周期 sync で再描画。
 */
public class ElectrificationDetailScreen extends JsonLayoutPlainScreen {

    @Override
    protected String wikiPageId() { return "electrification"; }

    private final UUID trainId;

    /** Car row 1 行の高さ (pixel)。 */
    private static final int ROW_H = 36;
    /** Car ラベル列の幅。 */
    private static final int LABEL_W = 70;
    /** パンタクリック判定の半径。 */
    private static final int PANTO_HIT_R = 8;

    /** クリック判定キャッシュ (= 直近に描画したパンタの hit box)。drawCanvas で更新、onElementClick で参照。 */
    private final java.util.List<PantoHit> pantoHits = new java.util.ArrayList<>();

    private record PantoHit(int x0, int y0, int x1, int y1, int carriageIndex, BlockPos pos) {}

    public ElectrificationDetailScreen(UUID trainId) {
        super(Component.translatable("tsu.electrification.detail_title"));
        this.trainId = trainId;
    }

    @Override
    protected String layoutJson() {
        return TsuLayouts.load("layouts/electrification-detail.json");
    }

    private TrainElectrificationView view() {
        return ClientTrainElectrificationCache.get(trainId);
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        TrainElectrificationView v = view();
        for (String c : classes) {
            if ("screen-title".equals(c)) {
                String name = v == null ? "?" : v.trainName;
                return Component.translatable("tsu.elec_detail.title_fmt", name).getString();
            }
            if ("status-summary".equals(c)) {
                if (v == null) return Component.translatable("tsu.elec_detail.not_synced").getString();
                return Component.translatable("tsu.elec_detail.status_fmt",
                        v.cars.size(), v.totalPantographs(), v.totalDeployedPantographs()).getString();
            }
        }
        return null;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        for (String c : classes) {
            if ("close-btn".equals(c) || "mc-popup-close".equals(c)) {
                onClose();
                return;
            }
            if ("deploy-all-btn".equals(c)) {
                PacketDistributor.sendToServer(new PantographTogglePayload(
                        trainId, PantographTogglePayload.ACTION_DEPLOY_ALL, 0, BlockPos.ZERO));
                return;
            }
            if ("fold-all-btn".equals(c)) {
                PacketDistributor.sendToServer(new PantographTogglePayload(
                        trainId, PantographTogglePayload.ACTION_FOLD_ALL, 0, BlockPos.ZERO));
                return;
            }
            if ("car-list-canvas".equals(c)) {
                // パンタの hit テスト
                for (PantoHit ph : pantoHits) {
                    if (mouseX >= ph.x0 && mouseX <= ph.x1
                            && mouseY >= ph.y0 && mouseY <= ph.y1) {
                        PacketDistributor.sendToServer(new PantographTogglePayload(
                                trainId, PantographTogglePayload.ACTION_TOGGLE_ONE,
                                ph.carriageIndex, ph.pos));
                        return;
                    }
                }
                return;
            }
        }
    }

    /**
     * 各 car を 1 行で描画する。1 行内に:
     *   - 左側: ラベル ("Car #N", FE 残量 bar)
     *   - 右側: 2D 車両ビュー (長方形 + 車輪 4 個 + パンタ各 1 基)
     *   - 車輪はインバータあれば塗りつぶし、なければ線のみ
     *   - パンタはアイコン化 (展開時は L 字、折畳時は __ 字)。クリックで toggle
     */
    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                            int x, int y, int w, int h, int mouseX, int mouseY) {
        if (!"car-list".equals(key)) return;
        pantoHits.clear();

        TrainElectrificationView v = view();
        if (v == null) {
            g.drawString(Minecraft.getInstance().font,
                    Component.translatable("tsu.elec_detail.canvas_not_synced"),
                    x + 10, y + 10, 0xFFAA0000, false);
            return;
        }
        if (v.cars.isEmpty()) {
            g.drawString(Minecraft.getInstance().font, Component.translatable("tsu.elec_detail.no_cars"),
                    x + 10, y + 10, 0xFFAAAAAA, false);
            return;
        }

        int rowY = y;
        for (TrainElectrificationView.Car car : v.cars) {
            drawCarRow(g, x, rowY, w, ROW_H - 2, car);
            rowY += ROW_H;
            if (rowY + ROW_H > y + h) break; // overflow
        }
    }

    private void drawCarRow(GuiGraphics g, int x, int y, int w, int h, TrainElectrificationView.Car car) {
        boolean anyInverter = car.hasInverter || car.dummyInverterCount > 0;
        // 行背景 (= 軽くハイライト)。装飾版は機能版と同じハイライト色で「インバータ車両」として見せる。
        int bg = anyInverter ? 0x402A4060 : 0x40404040;
        g.fill(x, y, x + w, y + h, bg);
        // ラベル + FE 残量を 1 行目に表示
        var font = Minecraft.getInstance().font;
        String label = "Car #" + car.carriageIndex;
        int labelColor = anyInverter ? 0xFF80DEEA : 0xFFAAAAAA;
        g.drawString(font, label, x + 4, y + 4, labelColor, false);

        // インバータ数バッジ (= ラベルの右隣)。装飾版も合算してカウント表示。
        int totalInverters = car.inverterCount + car.dummyInverterCount;
        if (totalInverters > 0) {
            String invText = "⚡×" + totalInverters;
            int invX = x + 4 + font.width(label) + 6;
            g.drawString(font, invText, invX, y + 4, 0xFFFFD54F, false);
        }

        // 2 行目: FE 残量 (機能版) / 装飾モード表示 (装飾版) / 状態表示 (それ以外)
        if (car.hasInverter) {
            String feText = String.format("%,d / %,d FE", car.storedEnergy, car.capacity);
            int feColor = car.inContact ? 0xFFA5D6A7 : 0xFFFFD54F;
            g.drawString(font, feText, x + 4, y + 16, feColor, false);
        } else if (car.dummyInverterCount > 0) {
            g.drawString(font, Component.translatable("tsu.elec_detail.mode_decorative"), x + 4, y + 16, 0xFFB39DDB, false);
        } else if (!car.pantographs.isEmpty()) {
            g.drawString(font, Component.translatable("tsu.elec_detail.mode_panto_only"), x + 4, y + 16, 0xFF80DEEA, false);
        } else {
            g.drawString(font, Component.translatable("tsu.elec_detail.mode_no_power"), x + 4, y + 16, 0xFF888888, false);
        }

        // 2D 車両ビュー: 右側の領域
        int viewX = x + LABEL_W + 4;
        int viewY = y + 4;
        int viewW = w - LABEL_W - 8;
        int viewH = h - 8;
        drawCarSideView(g, viewX, viewY, viewW, viewH, car);
    }

    /**
     * 車両のサイドビュー: 細長い長方形 + 下部に車輪 4 個 + 上部にパンタアイコン (各 panto あれば 1 個ずつ)。
     * 車輪はインバータあれば塗りつぶし、無ければ円形ライン。
     */
    private void drawCarSideView(GuiGraphics g, int x, int y, int w, int h,
                                  TrainElectrificationView.Car car) {
        boolean anyInverter = car.hasInverter || car.dummyInverterCount > 0;
        int color = anyInverter ? 0xFF80DEEA : 0xFF888888;
        // 車体外形 (長方形枠)
        int bodyTop = y + 4;
        int bodyBot = y + h - 8;
        g.fill(x, bodyTop, x + w, bodyTop + 1, color);          // top
        g.fill(x, bodyBot, x + w, bodyBot + 1, color);          // bottom
        g.fill(x, bodyTop, x + 1, bodyBot, color);              // left
        g.fill(x + w - 1, bodyTop, x + w, bodyBot, color);      // right

        // 車輪 (4 個、下部に並べる)。塗りつぶしは「インバータ系統あり」(機能 / 装飾どちらでも)。
        int wheelY = bodyBot + 2;
        int wheelR = 3;
        int[] wheelXs = {x + w / 6, x + w / 3, x + 2 * w / 3, x + 5 * w / 6};
        for (int wx : wheelXs) {
            drawWheel(g, wx, wheelY, wheelR, color, anyInverter);
        }

        // パンタ
        int pantoCount = car.pantographs.size();
        if (pantoCount > 0) {
            int stride = w / (pantoCount + 1);
            for (int i = 0; i < pantoCount; i++) {
                TrainElectrificationView.PantoEntry p = car.pantographs.get(i);
                int px = x + stride * (i + 1);
                drawPantograph(g, px, bodyTop, p.deployed, p.inContact, p.barOffsetY);
                // クリック判定登録
                pantoHits.add(new PantoHit(
                        px - PANTO_HIT_R, bodyTop - 14,
                        px + PANTO_HIT_R, bodyTop,
                        car.carriageIndex, p.pos));
            }
        }
    }

    /** 簡易の車輪描画 (= 3x3 dot)。インバータ車は塗りつぶし、無電化は線のみ。 */
    private static void drawWheel(GuiGraphics g, int cx, int cy, int r, int color, boolean filled) {
        if (filled) {
            // 塗りつぶし正方形 + 中心点強調
            g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, color);
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF000000);
        } else {
            // 枠線 (4 辺)
            g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, color);  // top
            g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, color);  // bottom
            g.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, color);  // left
            g.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, color);  // right
        }
    }

    /** パンタ簡易描画。展開時は山形、折畳時は水平線。
     *  S2: barOffsetY が負 (= 架線が低くてバーが押されている) なら集電舟の Y を下げる。 */
    private static void drawPantograph(GuiGraphics g, int cx, int topY, boolean deployed,
                                        boolean inContact, float barOffsetY) {
        int color = deployed
                ? (inContact ? 0xFFA5D6A7 : 0xFF4FC3F7)
                : 0xFF888888;
        if (deployed) {
            // S2: 接触時、バー Y を |barOffsetY| に応じてシフト (= 視覚的に "押されている" 表現)
            // 1 ブロック = 6px 程度の縮尺で表示
            int barShift = inContact ? Math.max(0, Math.min(6, Math.round(-barOffsetY * 6f))) : 0;
            int barY = topY - 12 + barShift;
            // 縦棒
            g.fill(cx - 1, topY - 12 + barShift, cx + 1, topY, color);
            // 水平バー (= 集電舟)
            g.fill(cx - 6, barY, cx + 7, barY + 1, color);
            // 斜線
            for (int i = 0; i < 4; i++) {
                int yy = topY - 8 + i + (barShift / 2);
                g.fill(cx - 4 + i, yy, cx - 3 + i, yy + 1, color);
                g.fill(cx + 3 - i, yy, cx + 4 - i, yy + 1, color);
            }
            // 接触マーカー (= 小さなスパーク)
            if (inContact) {
                int sparkColor = 0xFFFFEB3B;
                g.fill(cx - 2, barY - 2, cx + 3, barY - 1, sparkColor);
                g.fill(cx, barY - 4, cx + 1, barY - 1, sparkColor);
            }
        } else {
            // 折畳: 水平線のみ
            g.fill(cx - 4, topY - 2, cx + 5, topY - 1, color);
            g.fill(cx - 1, topY - 4, cx + 2, topY - 2, color);
        }
    }

    /** 静的: 列車 ID 指定で screen 起動。 */
    public static void open(UUID trainId) {
        Minecraft.getInstance().setScreen(new ElectrificationDetailScreen(trainId));
    }
}
