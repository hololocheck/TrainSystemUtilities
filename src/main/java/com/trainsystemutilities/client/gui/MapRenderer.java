package com.trainsystemutilities.client.gui;

import belugalab.mcss3.draw.VectorRenderer;
import com.trainsystemutilities.network.TrackNetworkScanner;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

/**
 * 路線マップタブの pan/zoom 付きネットワーク描画 renderer。
 * god-class 分割 (v2) で {@link ManagementComputerScreenV2} から切り出した。
 * pan/zoom/auto-fit の view state を自前で保持する。 network data (nodes/edges/stations/
 * signals/trains) は複数タブで共有されるため screen が保持し、 描画時に引数で受け取る。
 * screen は drawCanvas("map") で {@link #draw} を、 map-pan ドラッグ / map-zoom ホイールから
 * {@link #onPanDrag} / {@link #onZoomWheel} を、 タブ切替や network 再取得時に
 * {@link #resetInit} を呼ぶ。
 */
public final class MapRenderer {

    private double mapPanX = 0, mapPanZ = 0;
    private double mapZoom = 1.0;
    private boolean mapInitialized = false;
    private double dragStartMouseX, dragStartMouseY;
    private double dragStartPanX, dragStartPanZ;
    /** draw() 冒頭で set、 body の this.font が参照。 */
    private Font font;
    /** P2.1: fallback 列車描画の lerp 平滑化 state (trainId → world x,z)。
     *  TrainPositionPayload (server broadcast の実 bogey 位置) を補間し、 dedicated server で
     *  live train が取れない離れた列車も滑らかに動かす (= 1秒同期の worldX/Z 直描きの jump 解消)。 */
    private final java.util.Map<java.util.UUID, double[]> smoothTrainPos = new java.util.HashMap<>();

    /** #15: draw() で描いた駅/列車アイコンの screen 位置 (mouseX/Y と同じ dialog-local 空間)。
     *  毎フレーム作り直し、 screen の map クリックから {@link #hitTest} で引く。 */
    private final java.util.List<Target> hitTargets = new java.util.ArrayList<>();
    private record Target(float sx, float sy, java.util.UUID trainId, String stationName,
                          net.minecraft.core.BlockPos stationPos) {}
    /** 路線マップのクリック結果 (列車 or 駅、 どちらか一方が非 null)。 */
    public record MapHit(java.util.UUID trainId, String stationName, net.minecraft.core.BlockPos stationPos) {}

    /** auto-fit を再実行させる (タブ切替 / network source 変更時に screen から呼ぶ)。 */
    public void resetInit() { mapInitialized = false; }

    /** map-pan ドラッグ。 screen の onElementDrag("map-pan") から委譲。 */
    public void onPanDrag(int mouseX, int mouseY, boolean pressed) {
        if (pressed) {
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            dragStartPanX = mapPanX;
            dragStartPanZ = mapPanZ;
        } else {
            mapPanX = dragStartPanX + (mouseX - dragStartMouseX) / mapZoom;
            mapPanZ = dragStartPanZ + (mouseY - dragStartMouseY) / mapZoom;
        }
    }

    /** map-zoom ホイール。 screen の onElementWheel("map-zoom") から委譲。 */
    public void onZoomWheel(double scrollY) {
        double factor = scrollY > 0 ? 1.2 : 1.0 / 1.2;
        mapZoom = Math.max(0.1, Math.min(10.0, mapZoom * factor));
    }

    /** #15: press から release までの移動が小さければクリック (tap) とみなす (= pan でない)。 */
    public boolean wasClick(int releaseX, int releaseY) {
        double dx = releaseX - dragStartMouseX, dy = releaseY - dragStartMouseY;
        return dx * dx + dy * dy <= 25.0;   // 5px 以内
    }

    /** #15: (clickX,clickY) に最も近い列車/駅アイコンを返す (dialog-local)。 列車を優先。 無ければ null。 */
    public MapHit hitTest(int clickX, int clickY) {
        Target best = null; double bestD2 = Double.MAX_VALUE; boolean bestTrain = false;
        for (Target t : hitTargets) {
            double dx = clickX - t.sx(), dy = clickY - t.sy();
            double d2 = dx * dx + dy * dy;
            boolean isTrain = t.trainId() != null;
            double radius2 = isTrain ? 100.0 : 49.0;   // 列車 10px / 駅 7px
            if (d2 > radius2) continue;
            if (best == null || (isTrain && !bestTrain) || (isTrain == bestTrain && d2 < bestD2)) {
                best = t; bestD2 = d2; bestTrain = isTrain;
            }
        }
        return best == null ? null : new MapHit(best.trainId(), best.stationName(), best.stationPos());
    }

    /**
     * 路線マップの描画。 network data は screen が保持するため引数で受け取る (param 名は
     * 抽出前の screen フィールド名と一致させ body を verbatim 化)。
     */
    public void draw(GuiGraphics g, int mapX, int mapY, int mapW, int mapH, Font font, int leftPos, int topPos,
                     java.util.List<TrackNetworkScanner.NodeInfo> mapNodes,
                     java.util.List<TrackNetworkScanner.EdgeInfo> mapEdges,
                     java.util.List<TrackNetworkScanner.StationInfo> mapStations,
                     java.util.List<TrackNetworkScanner.SignalInfo> mapSignals,
                     java.util.List<TrackNetworkScanner.TrainInfo> mapTrains) {
        this.font = font;
        hitTargets.clear();   // #15: このフレームの駅/列車当たり判定を作り直す
        // Background
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF0d0d1a);

        if (mapNodes.isEmpty()) {
            String msg = net.minecraft.network.chat.Component.translatable("tsu.mc.no_memory_card").getString();
            int tw = this.font.width(msg);
            g.drawString(this.font, msg,
                    mapX + (mapW - tw) / 2, mapY + mapH / 2 - 4,
                    0xFF666688, false);
            return;
        }

        if (!mapInitialized) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (var n : mapNodes) {
                minX = Math.min(minX, n.x()); maxX = Math.max(maxX, n.x());
                minZ = Math.min(minZ, n.z()); maxZ = Math.max(maxZ, n.z());
            }
            double cx = (minX + maxX) / 2.0;
            double cz = (minZ + maxZ) / 2.0;
            double rangeX = maxX - minX + 20;
            double rangeZ = maxZ - minZ + 20;
            mapZoom = Math.min(mapW / rangeX, mapH / rangeZ);
            mapZoom = Math.max(0.1, Math.min(5.0, mapZoom));
            mapPanX = -cx;
            mapPanZ = -cz;
            mapInitialized = true;
        }

        // enableScissor は SCREEN 座標を要求し pose の scale/translate を考慮しない。
        // canvas pose は dialogScale で scale 済なので pose 行列から実 screen rect を算出
        // (leftPos+mapX の手動加算は dialogScale 非乗算で高 GUI スケール時に切れる)。
        var pm = g.pose().last().pose();
        int sx0 = Math.round(mapX * pm.m00() + pm.m30());
        int sy0 = Math.round(mapY * pm.m11() + pm.m31());
        g.enableScissor(sx0, sy0,
                Math.round((mapX + mapW) * pm.m00() + pm.m30()),
                Math.round((mapY + mapH) * pm.m11() + pm.m31()));
        double centerSX = mapX + mapW / 2.0;
        double centerSY = mapY + mapH / 2.0;

        var vc = VectorRenderer.getGuiBuffer(g.bufferSource());
        var matrix = g.pose().last().pose();
        for (var edge : mapEdges) {
            var pts = edge.points();
            if (pts != null && pts.size() >= 2) {
                for (int i = 0; i < pts.size() - 1; i++) {
                    float x1 = (float)(centerSX + (pts.get(i)[0] + mapPanX) * mapZoom);
                    float y1 = (float)(centerSY + (pts.get(i)[1] + mapPanZ) * mapZoom);
                    float x2 = (float)(centerSX + (pts.get(i + 1)[0] + mapPanX) * mapZoom);
                    float y2 = (float)(centerSY + (pts.get(i + 1)[1] + mapPanZ) * mapZoom);
                    VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, 0xFF6688AA, 2.0f);
                }
            } else if (edge.fromId() < mapNodes.size() && edge.toId() < mapNodes.size()) {
                var from = mapNodes.get(edge.fromId());
                var to = mapNodes.get(edge.toId());
                float x1 = (float)(centerSX + (from.x() + mapPanX) * mapZoom);
                float y1 = (float)(centerSY + (from.z() + mapPanZ) * mapZoom);
                float x2 = (float)(centerSX + (to.x() + mapPanX) * mapZoom);
                float y2 = (float)(centerSY + (to.z() + mapPanZ) * mapZoom);
                VectorRenderer.drawLine(vc, matrix, x1, y1, x2, y2, 0xFF556688, 2.0f);
            }
        }
        g.bufferSource().endBatch();

        for (var station : mapStations) {
            int sx = (int)(centerSX + (station.position().getX() + mapPanX) * mapZoom);
            int sy = (int)(centerSY + (station.position().getZ() + mapPanZ) * mapZoom);
            hitTargets.add(new Target(sx, sy, null, station.name(), station.position()));   // #15: 駅の当たり判定
            g.fill(sx - 3, sy - 3, sx + 3, sy + 3, 0xFF4fc3f7);
            g.fill(sx - 2, sy - 2, sx + 2, sy + 2, 0xFF1a1a2e);
            if (mapZoom > 0.5) {
                g.drawString(this.font, station.name(), sx + 5, sy - 4, 0xFF4fc3f7, true);
            }
        }
        for (var signal : mapSignals) {
            if (signal.position().equals(BlockPos.ZERO)) continue;
            int sx = (int)(centerSX + (signal.position().getX() + mapPanX) * mapZoom);
            int sy = (int)(centerSY + (signal.position().getZ() + mapPanZ) * mapZoom);
            int color = switch (signal.state()) {
                case GREEN -> 0xFF4caf50;
                case RED -> 0xFFf44336;
                case YELLOW -> 0xFFffc107;
                case ORANGE -> 0xFFff9800;
            };
            g.fill(sx - 2, sy - 2, sx + 2, sy + 2, color);
        }
        for (var train : mapTrains) {
            // 各車両のボギー位置を取得して描画 (curve 追従 + 可変長対応)。
            // live train が取得できない場合のみ、旧 fallback (worldX/Z + 固定長) を使う。
            boolean rendered = false;
            float headSx = 0, headSy = 0;

            if (train.id() != null) {
                try {
                    var optTrain = TrackNetworkScanner.getTrainById(train.id());
                    if (optTrain.isPresent() && !optTrain.get().carriages.isEmpty()) {
                        var live = optTrain.get();
                        for (var c : live.carriages) {
                            var lead = c.leadingBogey();
                            if (lead == null || lead.getAnchorPosition() == null) continue;
                            var leadPos = lead.getAnchorPosition();
                            var trail = c.trailingBogey();
                            var trailPos = (trail != null && trail != lead && trail.getAnchorPosition() != null)
                                    ? trail.getAnchorPosition() : leadPos;

                            float lsx = (float)(centerSX + (leadPos.x + mapPanX) * mapZoom);
                            float lsy = (float)(centerSY + (leadPos.z + mapPanZ) * mapZoom);
                            float tsx = (float)(centerSX + (trailPos.x + mapPanX) * mapZoom);
                            float tsy = (float)(centerSY + (trailPos.z + mapPanZ) * mapZoom);

                            drawCarriageIcon(g, lsx, lsy, tsx, tsy);

                            // 先頭車両の中心を train name 表示位置として記録
                            if (!rendered) {
                                headSx = (lsx + tsx) * 0.5f;
                                headSy = (lsy + tsy) * 0.5f;
                                rendered = true;
                            }
                        }
                    }
                } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
            }

            if (!rendered) {
                // Fallback (live train が取れない = dedicated server で離れた列車等):
                // server broadcast の TrainPositionPayload (= 実 leading bogey 位置) を lerp 平滑化して
                // 滑らかに動かす。 無ければ 1秒同期の worldX/Z を使う。
                double targetX = train.worldX(), targetZ = train.worldZ();
                if (train.id() != null) {
                    var live = com.trainsystemutilities.client.transit.TransitTerminalClientCache
                            .trainPositions().get(train.id());
                    if (live != null) { targetX = live.x(); targetZ = live.z(); }
                }
                if (targetX == 0 && targetZ == 0) continue;
                double headX = targetX, headZ = targetZ;
                // 進行方向 (world): broadcast payload に角度が無いため、平滑化した移動ベクトルから
                // heading を導出して保持する (= curve でアイコンが縦向き固定のままになる不具合の解消)。
                // 停止中は動かないので最後に検出した向きを維持。 sm = {x, z, dirX, dirZ}。
                double dirX = 0, dirZ = 1;   // default: 縦 (初回 / 未移動時)
                if (train.id() != null) {
                    double[] sm = smoothTrainPos.get(train.id());
                    if (sm == null) sm = new double[]{targetX, targetZ, 0, 1};
                    double nx = sm[0] + (targetX - sm[0]) * 0.2;   // 毎フレーム 0.2 ずつ目標へ glide
                    double nz = sm[1] + (targetZ - sm[1]) * 0.2;
                    double mvx = nx - sm[0], mvz = nz - sm[1];
                    if (mvx * mvx + mvz * mvz > 1e-5) { sm[2] = mvx; sm[3] = mvz; } // 動いた時だけ heading 更新
                    sm[0] = nx; sm[1] = nz;
                    smoothTrainPos.put(train.id(), sm);
                    headX = nx; headZ = nz;
                    dirX = sm[2]; dirZ = sm[3];
                }
                float sx = (float)(centerSX + (headX + mapPanX) * mapZoom);
                float sy = (float)(centerSY + (headZ + mapPanZ) * mapZoom);
                // world 進行方向 (単位)。 各車両を head から後方 (heading の逆) へ world 間隔でずらし、
                // それぞれ最寄り線路セグメント上へ snap して局所 tangent で向ける。 これで curve でも
                // アイコンが線路パスからはみ出さず、両数に関わらずパスに沿う。
                double wlen = Math.sqrt(dirX * dirX + dirZ * dirZ);
                double uwx = wlen > 1e-6 ? dirX / wlen : 0.0;
                double uwz = wlen > 1e-6 ? dirZ / wlen : 1.0;
                double worldStep = 5.0 / Math.max(0.05, mapZoom); // 画面上 ~5px 相当の world 間隔
                float carHalf = 2.5f;
                for (int ci = 0; ci < train.carriageCount(); ci++) {
                    double wx = headX - uwx * worldStep * ci;
                    double wz = headZ - uwz * worldStep * ci;
                    double cxw, czw, tgx, tgz;
                    double[] snap = nearestTrackPoint(wx, wz, mapEdges, mapNodes);
                    if (snap != null) { cxw = snap[0]; czw = snap[1]; tgx = snap[2]; tgz = snap[3]; }
                    else { cxw = wx; czw = wz; tgx = dirX; tgz = dirZ; } // 線路情報が無ければ heading
                    float cx = (float)(centerSX + (cxw + mapPanX) * mapZoom);
                    float cy = (float)(centerSY + (czw + mapPanZ) * mapZoom);
                    double tl = Math.sqrt(tgx * tgx + tgz * tgz);
                    float tux = tl > 1e-6 ? (float)(tgx / tl) : 0f;
                    float tuz = tl > 1e-6 ? (float)(tgz / tl) : 1f;
                    drawCarriageIcon(g, cx + tux * carHalf, cy + tuz * carHalf,
                                        cx - tux * carHalf, cy - tuz * carHalf);
                }
                headSx = sx; headSy = sy;
            }

            if (train.id() != null) {   // #15: 列車の当たり判定 (先頭位置)
                hitTargets.add(new Target(headSx, headSy, train.id(), null, null));
            }
            if (mapZoom > 0.3) {
                g.drawString(this.font, train.name(), (int) headSx + 5, (int) headSy - 4, 0xFFffcc02, true);
            }
        }
        g.disableScissor();
    }

    /**
     * 車両 1 両を leadingBogey → trailingBogey の screen 線分として描画。
     * pose stack を回転させて軸合わせ rect を傾ければ、curve 上の車両を正しい
     * 向き・長さで表現できる (車両ボディは 2 ボギー間で直線なので、隣接車両との
     * 連結部に curve が現れる)。
     *
     * <p>1 ボギー車両 (leadingBogey == trailingBogey) は短い square dot として描画。
     */
    private static void drawCarriageIcon(GuiGraphics g, float lsx, float lsy, float tsx, float tsy) {
        float dx = tsx - lsx, dy = tsy - lsy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) {
            // 1-bogey 車両、または overlapping bogeys: 小さな dot
            int isx = Math.round(lsx), isy = Math.round(lsy);
            g.fill(isx - 3, isy - 2, isx + 3, isy + 2, 0xFFff9800);
            g.fill(isx - 2, isy - 1, isx + 2, isy + 1, 0xFFffcc02);
            return;
        }
        // pose stack 回転で傾いた長方形を描画
        float cx = (lsx + tsx) * 0.5f, cy = (lsy + tsy) * 0.5f;
        float angleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
        int halfLen = Math.max(1, Math.round(len * 0.5f));
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(angleDeg));
        // 外枠 (orange)
        g.fill(-halfLen, -2, halfLen, 2, 0xFFff9800);
        // 内側 (yellow)
        g.fill(-halfLen + 1, -1, halfLen - 1, 1, 0xFFffcc02);
        g.pose().popPose();
    }

    /** (wx,wz) に最も近い線路セグメント上の点と、そのセグメント方向を返す。
     *  戻り値 double[]{px, pz, dirX, dirZ}。 線路が無ければ null。 fallback の各車両を
     *  線路パスへ載せる (= curve でアイコンがパスからはみ出すのを解消) のに使う。
     *  MonitorWorldRenderer の路線マップ fallback 描画からも共用。 */
    public static double[] nearestTrackPoint(double wx, double wz,
            java.util.List<TrackNetworkScanner.EdgeInfo> edges,
            java.util.List<TrackNetworkScanner.NodeInfo> nodes) {
        double bestD2 = Double.MAX_VALUE;
        double bpx = 0, bpz = 0, bdx = 0, bdz = 1;
        for (var edge : edges) {
            java.util.List<double[]> pts = edge.points();
            if (pts != null && pts.size() >= 2) {
                for (int i = 0; i < pts.size() - 1; i++) {
                    double[] r = projectToSeg(wx, wz, pts.get(i)[0], pts.get(i)[1],
                            pts.get(i + 1)[0], pts.get(i + 1)[1]);
                    if (r[2] < bestD2) {
                        bestD2 = r[2]; bpx = r[0]; bpz = r[1];
                        bdx = pts.get(i + 1)[0] - pts.get(i)[0];
                        bdz = pts.get(i + 1)[1] - pts.get(i)[1];
                    }
                }
            } else if (edge.fromId() >= 0 && edge.toId() >= 0
                    && edge.fromId() < nodes.size() && edge.toId() < nodes.size()) {
                var from = nodes.get(edge.fromId());
                var to = nodes.get(edge.toId());
                double[] r = projectToSeg(wx, wz, from.x(), from.z(), to.x(), to.z());
                if (r[2] < bestD2) {
                    bestD2 = r[2]; bpx = r[0]; bpz = r[1];
                    bdx = to.x() - from.x(); bdz = to.z() - from.z();
                }
            }
        }
        if (bestD2 == Double.MAX_VALUE) return null;
        return new double[]{bpx, bpz, bdx, bdz};
    }

    /** 点 (wx,wz) を線分 (ax,az)-(bx,bz) へ射影。 戻り値 {px, pz, dist2}。 */
    private static double[] projectToSeg(double wx, double wz,
            double ax, double az, double bx, double bz) {
        double abx = bx - ax, abz = bz - az;
        double len2 = abx * abx + abz * abz;
        double t = len2 > 1e-9 ? ((wx - ax) * abx + (wz - az) * abz) / len2 : 0;
        t = Math.max(0, Math.min(1, t));
        double px = ax + abx * t, pz = az + abz * t;
        double dx = wx - px, dz = wz - pz;
        return new double[]{px, pz, dx * dx + dz * dz};
    }
}
