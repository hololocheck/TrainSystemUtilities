package com.trainsystemutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import com.trainsystemutilities.network.TrackNetworkScanner;

import java.util.UUID;

/**
 * 列車詳細 popup の "車両構成" canvas に列車の 3D モデルを描画する renderer。
 * god-class 分割 (v2) で {@link ManagementComputerScreenV2} から切り出した。
 * 3D モデルの interactive 回転 / ズーム / パン状態を自前で保持し、 screen は
 * drawCanvas("train-model") から {@link #draw} を、 train-rotate ドラッグ /
 * train-zoom ホイールから {@link #onRotateDrag} / {@link #onZoomWheel} を呼ぶ。
 */
public final class TrainModelRenderer {

    // === Train detail popup の 3D モデル interactive state (V1 と同じ初期値) ===
    private float modelRotX = 30f;
    private float modelRotY = 225f;
    private float modelZoom = 1.0f;
    private float modelPanX = 0f, modelPanY = 0f;
    private int modelDragStartMx, modelDragStartMy;
    private float modelDragStartRotX, modelDragStartRotY;
    // pan ドラッグの起点 (抽出前は screen の map 用 dragStartPanX/Z を scratch 流用していた)
    private float panDragStartX, panDragStartY;

    // bogey wheelAngle reflection は per-frame preview render から呼ばれるため一度だけ解決してキャッシュ。
    // 旧: 毎フレーム getDeclaredField + getMethod + 無音 catch (§9 sister of SignalRendererMixin)。
    private static java.lang.reflect.Field bogeyWheelAngleField;
    private static java.lang.reflect.Method lerpedGetValueMethod;
    private static boolean bogeyReflectResolved;

    /** 左ドラッグで回転 (shift で pan)。 screen の onElementDrag("train-rotate") から委譲。 */
    public void onRotateDrag(int mouseX, int mouseY, boolean pressed, boolean shift) {
        if (pressed) {
            modelDragStartMx = mouseX;
            modelDragStartMy = mouseY;
            modelDragStartRotX = modelRotX;
            modelDragStartRotY = modelRotY;
            // pan の起点も同時に記録
            panDragStartX = modelPanX;
            panDragStartY = modelPanY;
        } else if (shift) {
            modelPanX = panDragStartX + (mouseX - modelDragStartMx);
            modelPanY = panDragStartY + (mouseY - modelDragStartMy);
        } else {
            modelRotY = modelDragStartRotY + (mouseX - modelDragStartMx) * 1.5f;
            modelRotX = Math.max(-90f, Math.min(90f,
                    modelDragStartRotX + (mouseY - modelDragStartMy) * 1.5f));
        }
    }

    /** ホイールズーム。 screen の onElementWheel("train-zoom") から委譲。 */
    public void onZoomWheel(double scrollY) {
        float factor = scrollY > 0 ? 1.2f : 1f / 1.2f;
        modelZoom = Math.max(0.3f, Math.min(5.0f, modelZoom * factor));
    }

    /**
     * 列車詳細 popup の "車両構成" canvas に列車の 3D モデルを描画 (V1 renderCarriageIcons 移植)。
     * Create の Contraption から各車両のブロックを取り出し、 BlockRenderDispatcher で
     * 直接描画する。 Bogey 車輪は LerpedFloat 経由でアニメーションも反映。
     *
     * canvas 引数 (cx, cy, cw, ch) は popup-local 座標 (overlay の pose 起点)。
     * scissor/translate は overlayX/Y を加算してスクリーン座標へ変換する必要あり。
     */
    public void draw(GuiGraphics g, int cx, int cy, int cw, int ch,
                     Minecraft mc, Font font, UUID selectedTrainId, int overlayX, int overlayY) {
        if (selectedTrainId == null) return;
        if (mc == null || mc.level == null) return;
        // 列車 entity が client にロード済みなら直接描画。 dedicated server で getTrainById が空
        // (= 遠方/未同期) でも、 server 同期の TrainPreviewCache へ fall through してプレビューを出す。
        var train = TrackNetworkScanner.getTrainById(selectedTrainId).orElse(null);

        try {
            // 全車両のブロックとボギー情報を集約 (V1 と同じロジック)
            var allBlocks = new java.util.LinkedHashMap<BlockPos, Object[]>();
            var allBogeys = new java.util.ArrayList<Object[]>();
            int mainAxisOffset = 0;
            boolean useXAxis = false;
            // entity-loaded path (train 在席時のみ): contraption から直接ブロックを集約。
            if (train != null && !train.carriages.isEmpty()) {
            for (var carriage : train.carriages) {
                var dim = carriage.getDimensionalIfPresent(mc.level.dimension());
                if (dim == null || dim.entity == null || dim.entity.get() == null) continue;
                var con = dim.entity.get().getContraption();
                if (con == null || con.getBlocks() == null) continue;
                int cMinX = Integer.MAX_VALUE, cMaxX = Integer.MIN_VALUE;
                int cMinZ = Integer.MAX_VALUE, cMaxZ = Integer.MIN_VALUE;
                for (var pos : con.getBlocks().keySet()) {
                    cMinX = Math.min(cMinX, pos.getX()); cMaxX = Math.max(cMaxX, pos.getX());
                    cMinZ = Math.min(cMinZ, pos.getZ()); cMaxZ = Math.max(cMaxZ, pos.getZ());
                }
                useXAxis = (cMaxX - cMinX) > (cMaxZ - cMinZ);
                break;
            }
            for (int ci = 0; ci < train.carriages.size(); ci++) {
                var carriage = train.carriages.get(ci);
                var dim = carriage.getDimensionalIfPresent(mc.level.dimension());
                if (dim == null || dim.entity == null || dim.entity.get() == null) continue;
                var con = dim.entity.get().getContraption();
                if (con == null || con.getBlocks() == null) continue;
                int cMin = Integer.MAX_VALUE, cMax = Integer.MIN_VALUE;
                for (var pos : con.getBlocks().keySet()) {
                    int v = useXAxis ? pos.getX() : pos.getZ();
                    cMin = Math.min(cMin, v); cMax = Math.max(cMax, v);
                }
                int carriageLen = (cMin == Integer.MAX_VALUE) ? 0 : (cMax - cMin + 1);
                int normalize = (cMin == Integer.MAX_VALUE) ? 0 : -cMin;
                for (var entry : con.getBlocks().entrySet()) {
                    var pos = entry.getKey();
                    var off = useXAxis
                            ? new BlockPos(pos.getX() + normalize + mainAxisOffset, pos.getY(), pos.getZ())
                            : new BlockPos(pos.getX(), pos.getY(), pos.getZ() + normalize + mainAxisOffset);
                    var info = entry.getValue();
                    allBlocks.put(off, new Object[]{info != null ? info.state() : null, info});
                    if (info != null && info.state() != null
                            && info.state().getBlock() instanceof com.simibubi.create.content.trains.bogey.AbstractBogeyBlock<?>) {
                        allBogeys.add(new Object[]{off, carriage});
                    }
                }
                int gap = 1;
                if (ci < train.carriageSpacing.size() && ci + 1 < train.carriages.size()) {
                    int trackSpacing = train.carriageSpacing.get(ci);
                    int nextLen = 0;
                    try {
                        var nextC = train.carriages.get(ci + 1);
                        var nDim = nextC.getDimensionalIfPresent(mc.level.dimension());
                        if (nDim != null && nDim.entity != null && nDim.entity.get() != null) {
                            var nCon = nDim.entity.get().getContraption();
                            if (nCon != null && nCon.getBlocks() != null) {
                                int nMin = Integer.MAX_VALUE, nMax = Integer.MIN_VALUE;
                                for (var p : nCon.getBlocks().keySet()) {
                                    int v = useXAxis ? p.getX() : p.getZ();
                                    nMin = Math.min(nMin, v); nMax = Math.max(nMax, v);
                                }
                                if (nMin != Integer.MAX_VALUE) nextLen = nMax - nMin + 1;
                            }
                        }
                    } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
                    gap = Math.max(1, (int) Math.round(trackSpacing - carriageLen / 2.0 - nextLen / 2.0));
                }
                mainAxisOffset += carriageLen + gap;
            }
            } // end entity-loaded path
            if (allBlocks.isEmpty()) {
                // Entity-based path で 1 両もロードできなかった (= 列車が遠方で client に
                // CarriageContraptionEntity が未配信)。 cache に過去/今回 fetch 済みの
                // データがあればそれを使う。 なければ server に request + loading 表示。
                var cached = com.trainsystemutilities.client.preview.TrainPreviewCache.get(selectedTrainId);
                if (cached == null || cached.carriages().isEmpty()) {
                    com.trainsystemutilities.client.preview.TrainPreviewCache.requestIfNeeded(selectedTrainId);
                    drawPreviewLoadingPlaceholder(g, cx, cy, cw, ch, font);
                    return;
                }
                // cache を allBlocks に展開 (mainAxisOffset を再計算)
                useXAxis = false;
                for (var carriageBlocks : cached.carriages()) {
                    int cMinX = Integer.MAX_VALUE, cMaxX = Integer.MIN_VALUE;
                    int cMinZ = Integer.MAX_VALUE, cMaxZ = Integer.MIN_VALUE;
                    for (var pos : carriageBlocks.blocks().keySet()) {
                        cMinX = Math.min(cMinX, pos.getX()); cMaxX = Math.max(cMaxX, pos.getX());
                        cMinZ = Math.min(cMinZ, pos.getZ()); cMaxZ = Math.max(cMaxZ, pos.getZ());
                    }
                    if (cMinX != Integer.MAX_VALUE) {
                        useXAxis = (cMaxX - cMinX) > (cMaxZ - cMinZ);
                        break;
                    }
                }
                int offset = 0;
                for (int ci = 0; ci < cached.carriages().size(); ci++) {
                    var carriageBlocks = cached.carriages().get(ci);
                    // 対応する Carriage オブジェクト (entity が無くても data は client-side に存在する)。
                    // bogey 描画ループで leadingBogey/trailingBogey を呼ぶために必要。
                    var liveCarriage = (train != null && ci < train.carriages.size()) ? train.carriages.get(ci) : null;
                    int cMin = Integer.MAX_VALUE, cMax = Integer.MIN_VALUE;
                    for (var pos : carriageBlocks.blocks().keySet()) {
                        int v = useXAxis ? pos.getX() : pos.getZ();
                        cMin = Math.min(cMin, v); cMax = Math.max(cMax, v);
                    }
                    int carriageLen = (cMin == Integer.MAX_VALUE) ? 0 : (cMax - cMin + 1);
                    int normalize = (cMin == Integer.MAX_VALUE) ? 0 : -cMin;
                    for (var bEntry : carriageBlocks.blocks().entrySet()) {
                        var pos = bEntry.getKey();
                        var be = bEntry.getValue();
                        var off = useXAxis
                                ? new BlockPos(pos.getX() + normalize + offset, pos.getY(), pos.getZ())
                                : new BlockPos(pos.getX(), pos.getY(), pos.getZ() + normalize + offset);
                        // Object[]{state, StructureBlockInfo} 構造を保つ (PreviewBlockRenderer が beNbt を取り出す)
                        var sbi = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(
                                pos, be.state(), be.beNbt());
                        allBlocks.put(off, new Object[]{be.state(), sbi});
                        // Bogey 検出: 後段の bogey 描画ループ用に [BlockPos, Carriage] を蓄積
                        if (liveCarriage != null
                                && be.state().getBlock() instanceof com.simibubi.create.content.trains.bogey.AbstractBogeyBlock<?>) {
                            allBogeys.add(new Object[]{off, liveCarriage});
                        }
                    }
                    int gap = 1;
                    int[] cachedSpacing = cached.spacing();
                    if (cachedSpacing != null && ci < cachedSpacing.length && ci + 1 < cached.carriages().size()) {
                        int trackSpacing = cachedSpacing[ci];
                        var nextCB = cached.carriages().get(ci + 1);
                        int nMin = Integer.MAX_VALUE, nMax = Integer.MIN_VALUE;
                        for (var p : nextCB.blocks().keySet()) {
                            int v = useXAxis ? p.getX() : p.getZ();
                            nMin = Math.min(nMin, v); nMax = Math.max(nMax, v);
                        }
                        int nextLen = (nMin == Integer.MAX_VALUE) ? 0 : (nMax - nMin + 1);
                        gap = Math.max(1, (int) Math.round(trackSpacing - carriageLen / 2.0 - nextLen / 2.0));
                    }
                    offset += carriageLen + gap;
                }
                if (allBlocks.isEmpty()) {
                    drawPreviewLoadingPlaceholder(g, cx, cy, cw, ch, font);
                    return;
                }
            }

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (var pos : allBlocks.keySet()) {
                minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            }
            float bcx = (minX + maxX) / 2f;
            float bcy = (minY + maxY) / 2f;
            float bcz = (minZ + maxZ) / 2f;
            float range = Math.max(maxX - minX + 1, Math.max(maxY - minY + 1, maxZ - minZ + 1));

            // pose は既に overlay 原点に translate 済み → translate は popup-local 座標を使う。
            // 一方 scissor は SCREEN 座標を要求するので overlayX/Y を加算する。
            // pan: ユーザーが shift+ドラッグで modelPanX/Y を更新 → 中心からのオフセットに加算。
            float poseCenterX = cx + cw / 2f + modelPanX;
            float poseCenterY = cy + ch / 2f + modelPanY;
            // scissor は pose 行列 (overlayScale 含む) から実 screen rect を算出する。
            // overlayX+cx の手動加算は dialogScale 非乗算で高 GUI スケール時に切れる。
            var pm = g.pose().last().pose();
            int screenSx = Math.round(cx * pm.m00() + pm.m30());
            int screenSy = Math.round(cy * pm.m11() + pm.m31());
            float baseScale = Math.min(cw * 0.45f, ch * 0.7f) / range;
            float scale = baseScale * modelZoom;  // ホイールズームを反映

            g.enableScissor(screenSx, screenSy,
                    Math.round((cx + cw) * pm.m00() + pm.m30()),
                    Math.round((cy + ch) * pm.m11() + pm.m31()));
            com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();

            g.pose().pushPose();
            g.pose().translate(poseCenterX, poseCenterY, 500f);
            g.pose().scale(scale, -scale, scale);

            // ユーザー操作によるインタラクティブ回転 (V1 と同じ X→Y 順)
            org.joml.Quaternionf rot = new org.joml.Quaternionf();
            rot.rotateX((float) Math.toRadians(modelRotX));
            rot.rotateY((float) Math.toRadians(modelRotY));
            g.pose().mulPose(rot);
            g.pose().translate(-bcx, -bcy, -bcz);

            var blockRenderer = mc.getBlockRenderer();
            var bufferSource = g.bufferSource();
            int light = 0xF000F0;
            int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;

            for (var entry : allBlocks.entrySet()) {
                var pos = entry.getKey();
                var data = entry.getValue();
                var state = (net.minecraft.world.level.block.state.BlockState) data[0];
                if (state == null || state.isAir()) continue;
                // Create コピーパネル等の BlockEntity-driven な見た目を再現するため
                // info.nbt() を渡して PreviewBlockRenderer 経由で描画 (ModelData 解決)。
                Object infoObj = data.length > 1 ? data[1] : null;
                net.minecraft.nbt.CompoundTag beNbt = null;
                if (infoObj instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo sbi) {
                    beNbt = sbi.nbt();
                }
                g.pose().pushPose();
                g.pose().translate(pos.getX(), pos.getY(), pos.getZ());
                com.trainsystemutilities.client.renderer.PreviewBlockRenderer.renderBlock(
                        state, beNbt, pos, g.pose(), bufferSource, light, overlay);
                g.pose().popPose();
            }

            // ボギー車輪
            try {
                var perCarriage = new java.util.IdentityHashMap<Object, Integer>();
                for (var bd : allBogeys) {
                    var bpos = (BlockPos) bd[0];
                    var carriage = (com.simibubi.create.content.trains.entity.Carriage) bd[1];
                    int idx = perCarriage.getOrDefault(carriage, 0);
                    perCarriage.put(carriage, idx + 1);
                    var bogey = idx == 0 ? carriage.leadingBogey() : carriage.trailingBogey();
                    if (bogey == null) continue;
                    var style = bogey.getStyle();
                    var size = bogey.getSize();
                    float wheelAngle = readBogeyWheelAngle(bogey);
                    g.pose().pushPose();
                    g.pose().translate(bpos.getX() + 0.5, bpos.getY(), bpos.getZ() + 0.5);
                    var bogeyData = allBlocks.get(bpos);
                    var bogeyState = bogeyData != null
                            ? (net.minecraft.world.level.block.state.BlockState) bogeyData[0] : null;
                    if (bogeyState != null
                            && bogeyState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS)) {
                        var axis = bogeyState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS);
                        if (axis == net.minecraft.core.Direction.Axis.X) {
                            g.pose().mulPose(new org.joml.Quaternionf().rotateY((float) Math.toRadians(90)));
                        }
                    }
                    style.render(size, 1.0f, g.pose(), bufferSource, light, overlay,
                            wheelAngle, bogey.bogeyData, true);
                    g.pose().popPose();
                }
            } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }

            bufferSource.endBatch();
            g.pose().popPose();
            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
            g.disableScissor();
        } catch (Exception e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MgmtScreen] GUI op failed", e); }
    }

    /** プレビューデータを server から取得中 / 失敗時のプレースホルダー表示。 */
    private void drawPreviewLoadingPlaceholder(GuiGraphics g, int cx, int cy, int cw, int ch, Font font) {
        String text = net.minecraft.network.chat.Component.translatable("tsu.mc.train_preview_loading").getString();
        int tw = font.width(text);
        int tx = cx + (cw - tw) / 2;
        int ty = cy + ch / 2 - 4;
        g.drawString(font, text, tx, ty, 0xFFAAAAAA, true);
    }

    private static float readBogeyWheelAngle(Object bogey) {
        try {
            if (!bogeyReflectResolved) {
                bogeyReflectResolved = true;
                var f = bogey.getClass().getDeclaredField("wheelAngle");
                f.setAccessible(true);
                bogeyWheelAngleField = f;
                lerpedGetValueMethod = f.get(bogey).getClass().getMethod("getValue", float.class);
            }
            if (bogeyWheelAngleField == null) return 0f;
            return (float) lerpedGetValueMethod.invoke(bogeyWheelAngleField.get(bogey), 1.0f);
        } catch (Exception e) {
            bogeyWheelAngleField = null;  // Create 更新等で失敗したら以後 skip
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[BogeyPreview] wheelAngle reflection failed", e);
            return 0f;
        }
    }
}
