package com.trainsystemutilities.client.transit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 徒歩ナビゲーションパスをワールド内に半透明のベクター線として描画する。
 *
 * <p>パスは {@link TransitNavClientState#path()} の BlockPos 列。各隣接ペアを
 * Y 平面上の四角形 (line quad) で繋ぐ。地面より +0.05 上で描画し z-fighting を回避。
 *
 * <p>色: シアン半透明、線幅 0.6 ブロック。視認性のため発光 (full bright)。
 *
 * <p>レンダリング段階: {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}
 * (固体ブロックの後、エンティティと同等)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class TransitNavRenderer {

    private TransitNavRenderer() {}

    private static final float LINE_WIDTH = 0.6f;
    /** 地面 (= 立ち位置の足元 = 標準ブロック上面) より +0.3 ブロック (= 30cm) 上にラインを描画。
     *  0.05 (= 5cm) では地面と Z-fighting や視角で埋まって見える問題があった。 */
    private static final float Y_OFFSET = 0.3f;
    private static final int COLOR_RGBA = 0xCC4FC3F7; // 80% シアン
    private static final int GOAL_COLOR_RGBA = 0xCCFFD54F; // ゴール: 黄色

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!TransitNavClientState.active()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // 自動クリアチェック (目的地到着)
        TransitNavClientState.checkAutoClear(mc.player);
        if (!TransitNavClientState.active()) return;

        // 経路逸脱チェック → 自動再計算 (元の番線指定を保持)
        if (TransitNavClientState.playerOffPath(mc.player)
                && TransitNavClientState.shouldAutoRecalc()
                && !TransitNavClientState.isPending()) {
            int platform = TransitNavClientState.targetPlatform();
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.info(
                    "[NavPath] auto-recalc (player off path) platform={}", platform);
            TransitNavClientState.markPending();
            com.trainsystemutilities.network.NavPathRequestPayload.send(
                    TransitNavClientState.targetGroupId(), platform);
        }

        Camera cam = event.getCamera();
        var camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(RenderType.debugQuads());

        List<BlockPos> path = TransitNavClientState.path();
        // Head trim: プレイヤーに最も近い waypoint より手前は描画しない
        int trimStart = Math.max(0, TransitNavClientState.nearestPathIndex(mc.player) - 1);

        // LOD 距離 (ブロック単位) — これより遠い segment は薄く描画。
        // 値を大幅に拡大 = 200 ブロック先までは 100% 視認、それ以上で薄める。
        double lodNear = 80, lodFar = 250;
        double camX = camPos.x, camY = camPos.y, camZ = camPos.z;

        // パス簡略化 + Catmull-Rom スプラインで滑らかな曲線に変換
        List<BlockPos> trimmed = path.subList(Math.min(trimStart, path.size()), path.size());
        List<BlockPos> simplified = PathSmoother.simplify(trimmed, 0.6);
        List<org.joml.Vector3f> smooth = PathSmoother.catmullRom(simplified, 8);

        // 連続帯描画: 各点で「前後 segment の平均接線」から法線を作り、両側に extrude。
        // これで急カーブでも quad 同士の接合に隙間が出ない (= mitered joint)。
        if (smooth.size() >= 2) {
            int n = smooth.size();
            org.joml.Vector3f[] left = new org.joml.Vector3f[n];
            org.joml.Vector3f[] right = new org.joml.Vector3f[n];
            for (int i = 0; i < n; i++) {
                org.joml.Vector3f prev = i > 0 ? smooth.get(i - 1) : smooth.get(i);
                org.joml.Vector3f next = i + 1 < n ? smooth.get(i + 1) : smooth.get(i);
                float tx = next.x - prev.x;
                float tz = next.z - prev.z;
                float tlen = (float) Math.sqrt(tx * tx + tz * tz);
                if (tlen < 1e-4f) { tx = 1; tz = 0; tlen = 1; }
                tx /= tlen; tz /= tlen;
                // XZ 平面で 90 度回した法線 (= LINE_WIDTH/2)
                float nx = -tz * LINE_WIDTH * 0.5f;
                float nz = tx * LINE_WIDTH * 0.5f;
                org.joml.Vector3f cur = smooth.get(i);
                left[i] = new org.joml.Vector3f(cur.x - nx, cur.y, cur.z - nz);
                right[i] = new org.joml.Vector3f(cur.x + nx, cur.y, cur.z + nz);
            }
            // 連続 quad
            for (int i = 0; i + 1 < n; i++) {
                org.joml.Vector3f a = smooth.get(i);
                org.joml.Vector3f b = smooth.get(i + 1);
                double mx = (a.x + b.x) * 0.5;
                double my = (a.y + b.y) * 0.5;
                double mz = (a.z + b.z) * 0.5;
                double dx = mx - camX, dy = my - camY, dz = mz - camZ;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                int color = applyLod(COLOR_RGBA, dist, lodNear, lodFar);
                drawJointQuad(vc, matrix, left[i], right[i], right[i + 1], left[i + 1], color);
            }
        }
        // ゴールマーカー (常時 full opacity)
        if (!path.isEmpty()) {
            BlockPos goal = path.get(path.size() - 1);
            drawGoalMarker(vc, matrix, goal);
        }
        // 起点マーカーは head trim 後の先頭点
        if (trimStart < path.size()) {
            BlockPos start = path.get(trimStart);
            drawStartMarker(vc, matrix, start);
        }

        buffer.endBatch(RenderType.debugQuads());
        pose.popPose();
    }

    /** カメラ距離による LOD alpha 補正。near 以下は full、far 以遠は 70% (= 視認できるレベル維持)、間は線形。 */
    private static int applyLod(int color, double dist, double near, double far) {
        float t;
        if (dist <= near) t = 1f;
        else if (dist >= far) t = 0.7f;
        else t = (float) (1f - 0.3f * (dist - near) / (far - near));
        int origA = (color >>> 24) & 0xFF;
        int newA = Math.max(0, Math.min(255, (int)(origA * t)));
        return (newA << 24) | (color & 0x00FFFFFF);
    }

    /** Mitered joint quad: 4 隅を直接受け取って quad を描く。 */
    private static void drawJointQuad(VertexConsumer vc, Matrix4f matrix,
                                       org.joml.Vector3f bl, org.joml.Vector3f br,
                                       org.joml.Vector3f tr, org.joml.Vector3f tl, int color) {
        float yOff = Y_OFFSET - 0.5f;
        int aA = (color >>> 24) & 0xFF;
        int aR = (color >>> 16) & 0xFF;
        int aG = (color >>> 8) & 0xFF;
        int aB = color & 0xFF;
        vc.addVertex(matrix, bl.x, bl.y + yOff, bl.z)
                .setColor(aR, aG, aB, aA).setUv(0, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, br.x, br.y + yOff, br.z)
                .setColor(aR, aG, aB, aA).setUv(0, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, tr.x, tr.y + yOff, tr.z)
                .setColor(aR, aG, aB, aA).setUv(1, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, tl.x, tl.y + yOff, tl.z)
                .setColor(aR, aG, aB, aA).setUv(1, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
    }

    /** Catmull-Rom 補間後の Vector3f 2 点間を地面平行クワッドで描画。 */
    private static void drawSmoothSegment(VertexConsumer vc, Matrix4f matrix,
                                           org.joml.Vector3f a, org.joml.Vector3f b, int color) {
        float ax = a.x, ay = a.y + Y_OFFSET - 0.5f, az = a.z;
        float bx = b.x, by = b.y + Y_OFFSET - 0.5f, bz = b.z;
        float dx = bx - ax;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001f) return;
        float nx = -dz / len * LINE_WIDTH * 0.5f;
        float nz = dx / len * LINE_WIDTH * 0.5f;
        int aA = (color >>> 24) & 0xFF;
        int aR = (color >>> 16) & 0xFF;
        int aG = (color >>> 8) & 0xFF;
        int aB = color & 0xFF;
        vc.addVertex(matrix, ax - nx, ay, az - nz)
                .setColor(aR, aG, aB, aA).setUv(0, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, ax + nx, ay, az + nz)
                .setColor(aR, aG, aB, aA).setUv(0, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, bx + nx, by, bz + nz)
                .setColor(aR, aG, aB, aA).setUv(1, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, bx - nx, by, bz - nz)
                .setColor(aR, aG, aB, aA).setUv(1, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
    }

    /** 2 点を繋ぐ線分を地面平行のクワッドで描画。 */
    private static void drawSegment(VertexConsumer vc, Matrix4f matrix,
                                     BlockPos a, BlockPos b, int color) {
        float ax = a.getX() + 0.5f, az = a.getZ() + 0.5f;
        float bx = b.getX() + 0.5f, bz = b.getZ() + 0.5f;
        // Y 座標は両端の高さ平均 + ground offset
        float ay = a.getY() + Y_OFFSET;
        float by = b.getY() + Y_OFFSET;
        float dx = bx - ax;
        float dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001f) return;
        // 垂直法線 (xz 平面で 90 度)
        float nx = -dz / len * LINE_WIDTH * 0.5f;
        float nz = dx / len * LINE_WIDTH * 0.5f;
        int aA = (color >>> 24) & 0xFF;
        int aR = (color >>> 16) & 0xFF;
        int aG = (color >>> 8) & 0xFF;
        int aB = color & 0xFF;
        // クワッド (xz 平面で線の幅を持たせる)
        vc.addVertex(matrix, ax - nx, ay, az - nz)
                .setColor(aR, aG, aB, aA).setUv(0, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, ax + nx, ay, az + nz)
                .setColor(aR, aG, aB, aA).setUv(0, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, bx + nx, by, bz + nz)
                .setColor(aR, aG, aB, aA).setUv(1, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, bx - nx, by, bz - nz)
                .setColor(aR, aG, aB, aA).setUv(1, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
    }

    /** 終点に黄色いリング (大きい四角)。 */
    private static void drawGoalMarker(VertexConsumer vc, Matrix4f matrix, BlockPos pos) {
        float cx = pos.getX() + 0.5f;
        float cz = pos.getZ() + 0.5f;
        float y = pos.getY() + Y_OFFSET + 0.01f;
        float r = 0.7f;
        int color = GOAL_COLOR_RGBA;
        int aA = (color >>> 24) & 0xFF;
        int aR = (color >>> 16) & 0xFF;
        int aG = (color >>> 8) & 0xFF;
        int aB = color & 0xFF;
        // ▲ 三角形マーカーを 4 つで star / fan の代わりに simple 四角形
        vc.addVertex(matrix, cx - r, y, cz - r)
                .setColor(aR, aG, aB, aA).setUv(0, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, cx - r, y, cz + r)
                .setColor(aR, aG, aB, aA).setUv(0, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, cx + r, y, cz + r)
                .setColor(aR, aG, aB, aA).setUv(1, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, cx + r, y, cz - r)
                .setColor(aR, aG, aB, aA).setUv(1, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
    }

    /** 起点に小さい緑の円 (= 出発印)。 */
    private static void drawStartMarker(VertexConsumer vc, Matrix4f matrix, BlockPos pos) {
        float cx = pos.getX() + 0.5f;
        float cz = pos.getZ() + 0.5f;
        float y = pos.getY() + Y_OFFSET + 0.01f;
        float r = 0.4f;
        int color = 0xCC66BB6A;
        int aA = (color >>> 24) & 0xFF;
        int aR = (color >>> 16) & 0xFF;
        int aG = (color >>> 8) & 0xFF;
        int aB = color & 0xFF;
        vc.addVertex(matrix, cx - r, y, cz - r)
                .setColor(aR, aG, aB, aA).setUv(0, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, cx - r, y, cz + r)
                .setColor(aR, aG, aB, aA).setUv(0, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, cx + r, y, cz + r)
                .setColor(aR, aG, aB, aA).setUv(1, 1).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
        vc.addVertex(matrix, cx + r, y, cz - r)
                .setColor(aR, aG, aB, aA).setUv(1, 0).setOverlay(0).setLight(0xF000F0).setNormal(0, 1, 0);
    }
}
