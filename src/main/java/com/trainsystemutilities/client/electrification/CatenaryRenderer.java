package com.trainsystemutilities.client.electrification;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.wire.WireType;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 架線描画。{@link WireType} に応じて単線・二段・複線・高所オフセットなどを使い分ける。
 *
 * <p>サグ無し (= 実物張力で水平)、すべて完全直線。dropper のみ垂直線。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class CatenaryRenderer {

    private CatenaryRenderer() {}

    // ===== 共通 サイズ =====
    private static final float MESSENGER_WIDTH = 0.06f;
    private static final float CONTACT_WIDTH = 0.05f;
    private static final float DROPPER_WIDTH = 0.03f;
    private static final float SIMPLE_WIDTH = 0.05f;

    // ===== レイアウト =====
    /** 標準二段の上下間隔。 */
    private static final double TROLLEY_OFFSET_Y = 0.5;
    /** 高所オフセット (HIGH_OFFSET) の上下間隔 (= 大型車両用、より広い)。 */
    private static final double TROLLEY_OFFSET_Y_HIGH = 0.9;
    /** TWIN_2ROW の左右セット間隔 (= 複線間距離、ブロック)。 */
    private static final double TWIN_LATERAL_OFFSET = 0.7;
    /** dropper 配置間隔。 */
    private static final double DROPPER_INTERVAL = 2.5;
    /** 接続が短すぎる場合は dropper を省略。 */
    private static final double DROPPER_MIN_LENGTH = 3.0;

    // ===== 色 =====
    // 架線は通電状態やワールド光に関係なく、必ず不透明の真っ黒で描画する。
    private static final int WIRE_COLOR = 0xFF000000;

    /** 描画距離カリング。 */
    private static final double CULL_DISTANCE_SQ = 256 * 256;

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ClientWireStore.size() == 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = pose.last().pose();

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        // RenderType.lines() を使う理由: debugQuads() は POSITION_COLOR format で lighting なし
        // のはずが、Iris シェーダ環境では夜間に暗色サーフェスが warm tint (= 銅色) になる問題が出る。
        // lines() は POSITION_COLOR_NORMAL format で、normal vector を持つことでシェーダ側が
        // 一貫した処理を行いやすく、色変化を回避できる。
        VertexConsumer vc = buffer.getBuffer(RenderType.lines());

        for (ClientWireStore.RenderWire w : ClientWireStore.all()) {
            Vec3 a = w.attachA();
            Vec3 b = w.attachB();

            // 視野距離カリング
            double midX = (a.x + b.x) * 0.5;
            double midY = (a.y + b.y) * 0.5;
            double midZ = (a.z + b.z) * 0.5;
            double dx = midX - camPos.x;
            double dy = midY - camPos.y;
            double dz = midZ - camPos.z;
            if (dx * dx + dy * dy + dz * dz > CULL_DISTANCE_SQ) continue;

            int color = WIRE_COLOR;

            // type で dispatch
            switch (w.type()) {
                case SIMPLE -> {
                    if (w.sag()) renderSimpleSag(vc, matrix, a, b, color);
                    else renderSimple(vc, matrix, a, b, color);
                }
                case TWO_TIER -> renderTwoTier(vc, matrix, a, b, color, TROLLEY_OFFSET_Y);
                case TWIN_2ROW -> renderTwin2Row(vc, matrix, a, b, color);
                case HIGH_OFFSET -> renderTwoTier(vc, matrix, a, b, color, TROLLEY_OFFSET_Y_HIGH);
                case CUSTOM -> renderCustom(vc, matrix, a, b, color,
                        w.customThickness(), w.customTrolleyOffset(),
                        w.customDropperInterval(), w.customRowCount());
            }
        }

        pose.popPose();
        buffer.endBatch(RenderType.lines());
    }

    /** SIMPLE: 黒 1 本線、直線。 */
    private static void renderSimple(VertexConsumer vc, Matrix4f matrix,
                                      Vec3 a, Vec3 b, int color) {
        drawStraightQuad(vc, matrix, a, b, SIMPLE_WIDTH, color);
    }

    /** SIMPLE + たるみモード: 重力でたるんだカテナリ曲線 (= y = a*cosh(x/a) 風)。
     *  実装は近似カテナリを n セグメントの折線で描画。垂れる量は端点距離に比例。 */
    private static final int SAG_SEGMENTS = 12;
    /** 中央でのたるみ最大量 (= 端点距離に対する比率)。0.06 ≒ 端点 10m で 0.6m 垂れる。 */
    private static final double SAG_RATIO = 0.06;

    private static void renderSimpleSag(VertexConsumer vc, Matrix4f matrix,
                                          Vec3 a, Vec3 b, int color) {
        double length = a.distanceTo(b);
        // 短い線は折線にしても見栄えしないので直線で代替
        if (length < 1.5) {
            drawStraightQuad(vc, matrix, a, b, SIMPLE_WIDTH, color);
            return;
        }
        double sagAmount = length * SAG_RATIO;
        Vec3 prev = a;
        for (int i = 1; i <= SAG_SEGMENTS; i++) {
            double t = (double) i / SAG_SEGMENTS;
            // 4 * t * (1 - t) で 0..1..0 の放物線、中央 1.0 で最大
            double sagFactor = 4.0 * t * (1.0 - t);
            Vec3 base = lerp(a, b, t);
            Vec3 cur = new Vec3(base.x, base.y - sagAmount * sagFactor, base.z);
            drawStraightQuad(vc, matrix, prev, cur, SIMPLE_WIDTH, color);
            prev = cur;
        }
    }

    /** TWO_TIER / HIGH_OFFSET: 吊架線 + トロリ線 + dropper。 */
    private static void renderTwoTier(VertexConsumer vc, Matrix4f matrix,
                                       Vec3 a, Vec3 b, int color, double trolleyOffset) {
        Vec3 contactA = new Vec3(a.x, a.y - trolleyOffset, a.z);
        Vec3 contactB = new Vec3(b.x, b.y - trolleyOffset, b.z);

        drawStraightQuad(vc, matrix, a, b, MESSENGER_WIDTH, color);
        drawStraightQuad(vc, matrix, contactA, contactB, CONTACT_WIDTH, color);
        drawDroppers(vc, matrix, a, b, trolleyOffset, color);
    }

    /** CUSTOM: 接続ごとに記録された thickness / trolleyOffset / dropperInterval / rowCount で描画。
     *  rowCount=2 のときは左右に 2 セット並列、trolleyOffset<=0 のときは単段 (= SIMPLE 風)。 */
    private static void renderCustom(VertexConsumer vc, Matrix4f matrix,
                                       Vec3 a, Vec3 b, int color,
                                       float thickness, float trolleyOffset,
                                       float dropperInterval, int rowCount) {
        float t = Math.max(0.005f, Math.min(0.5f, thickness));
        float to = Math.max(0f, Math.min(2.0f, trolleyOffset));
        float di = Math.max(0.5f, Math.min(10.0f, dropperInterval));
        int rc = Math.max(1, Math.min(2, rowCount));

        if (rc == 1) {
            renderCustomSingle(vc, matrix, a, b, color, t, to, di);
        } else {
            // 2 列並列 (TWIN_2ROW と同じ手法)
            double tx = b.x - a.x;
            double tz = b.z - a.z;
            double tlen = Math.sqrt(tx * tx + tz * tz);
            if (tlen < 1e-4) {
                renderCustomSingle(vc, matrix, a, b, color, t, to, di);
                return;
            }
            tx /= tlen; tz /= tlen;
            double nx = -tz * TWIN_LATERAL_OFFSET * 0.5;
            double nz = tx * TWIN_LATERAL_OFFSET * 0.5;
            renderCustomSingle(vc, matrix,
                    new Vec3(a.x + nx, a.y, a.z + nz),
                    new Vec3(b.x + nx, b.y, b.z + nz), color, t, to, di);
            renderCustomSingle(vc, matrix,
                    new Vec3(a.x - nx, a.y, a.z - nz),
                    new Vec3(b.x - nx, b.y, b.z - nz), color, t, to, di);
        }
    }

    private static void renderCustomSingle(VertexConsumer vc, Matrix4f matrix,
                                             Vec3 a, Vec3 b, int color,
                                             float thickness, float trolleyOffset, float dropperInterval) {
        if (trolleyOffset <= 0.01f) {
            // 単段 (= SIMPLE 風)
            drawStraightQuad(vc, matrix, a, b, thickness, color);
            return;
        }
        // 二段 (messenger + trolley + dropper)
        Vec3 contactA = new Vec3(a.x, a.y - trolleyOffset, a.z);
        Vec3 contactB = new Vec3(b.x, b.y - trolleyOffset, b.z);
        drawStraightQuad(vc, matrix, a, b, thickness * 1.2f, color);          // messenger (太め)
        drawStraightQuad(vc, matrix, contactA, contactB, thickness, color);   // trolley
        // dropper
        double length = a.distanceTo(b);
        if (length >= DROPPER_MIN_LENGTH) {
            int dropperCount = (int) Math.floor(length / dropperInterval);
            if (dropperCount >= 1) {
                for (int i = 1; i < dropperCount; i++) {
                    double frac = (double) i / dropperCount;
                    Vec3 top = lerp(a, b, frac);
                    Vec3 bot = new Vec3(top.x, top.y - trolleyOffset, top.z);
                    drawStraightQuad(vc, matrix, top, bot, thickness * 0.6f, color);
                }
            }
        }
    }

    /** TWIN_2ROW: TWO_TIER を XZ 平面で左右に 2 セット並列描画。 */
    private static void renderTwin2Row(VertexConsumer vc, Matrix4f matrix,
                                        Vec3 a, Vec3 b, int color) {
        // 接線方向 (= XZ 平面のワイヤー進行) の左右に直交する方向を求める
        double tx = b.x - a.x;
        double tz = b.z - a.z;
        double tlen = Math.sqrt(tx * tx + tz * tz);
        if (tlen < 1e-4) {
            // 真上下方向の wire (= 通常起こらない) は単一二段で描画
            renderTwoTier(vc, matrix, a, b, color, TROLLEY_OFFSET_Y);
            return;
        }
        tx /= tlen;
        tz /= tlen;
        // XZ 平面で 90° 回転した法線 × half offset
        double nx = -tz * TWIN_LATERAL_OFFSET * 0.5;
        double nz = tx * TWIN_LATERAL_OFFSET * 0.5;

        // 左セット
        Vec3 aLeft = new Vec3(a.x + nx, a.y, a.z + nz);
        Vec3 bLeft = new Vec3(b.x + nx, b.y, b.z + nz);
        renderTwoTier(vc, matrix, aLeft, bLeft, color, TROLLEY_OFFSET_Y);

        // 右セット
        Vec3 aRight = new Vec3(a.x - nx, a.y, a.z - nz);
        Vec3 bRight = new Vec3(b.x - nx, b.y, b.z - nz);
        renderTwoTier(vc, matrix, aRight, bRight, color, TROLLEY_OFFSET_Y);
    }

    /** 二段の dropper 配置。 */
    private static void drawDroppers(VertexConsumer vc, Matrix4f matrix,
                                      Vec3 a, Vec3 b, double trolleyOffset, int color) {
        double length = a.distanceTo(b);
        if (length < DROPPER_MIN_LENGTH) return;

        int dropperCount = (int) Math.floor(length / DROPPER_INTERVAL);
        if (dropperCount < 1) return;

        for (int i = 1; i < dropperCount; i++) {
            double t = (double) i / dropperCount;
            Vec3 topPoint = lerp(a, b, t);
            Vec3 bottomPoint = new Vec3(topPoint.x, topPoint.y - trolleyOffset, topPoint.z);
            drawStraightQuad(vc, matrix, topPoint, bottomPoint, DROPPER_WIDTH, color);
        }
    }

    /**
     * 線分 a → b を {@link RenderType#lines} 用 line vertices として描画。
     * width は 1px の line として無視されるが、太く見せたい場合は呼び出し側で
     * 複数本を並列に呼ぶ ({@link #drawThickLine})。
     */
    private static void drawStraightQuad(VertexConsumer vc, Matrix4f matrix,
                                          Vec3 a, Vec3 b, float width, int color) {
        // 視覚的太さを近似: 0.05 以上の太さなら 2 本、0.10 以上なら 3 本平行線
        int lineCount = Math.max(1, Math.round(width / 0.04f));
        if (lineCount == 1) {
            drawLine(vc, matrix, a, b, color);
            return;
        }
        // 平行線で太さ表現
        double tx = b.x - a.x;
        double tz = b.z - a.z;
        double tlen = Math.sqrt(tx * tx + tz * tz);
        if (tlen < 1e-4) {
            drawLine(vc, matrix, a, b, color);
            return;
        }
        tx /= tlen; tz /= tlen;
        // XZ 平面に垂直なベクトル
        double px = -tz, pz = tx;
        // 線間隔 (= 太さ/lineCount)
        double step = width / (lineCount - 1);
        for (int i = 0; i < lineCount; i++) {
            double offset = (i - (lineCount - 1) * 0.5) * step;
            Vec3 a2 = new Vec3(a.x + px * offset, a.y, a.z + pz * offset);
            Vec3 b2 = new Vec3(b.x + px * offset, b.y, b.z + pz * offset);
            drawLine(vc, matrix, a2, b2, color);
        }
    }

    /** {@link RenderType#lines} 用の単一線分。1px。各 vertex は normal が必要。 */
    private static void drawLine(VertexConsumer vc, Matrix4f matrix, Vec3 a, Vec3 b, int color) {
        int alpha = (color >> 24) & 0xFF;
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx, ny, nz;
        if (len > 1e-6) {
            nx = (float) (dx / len);
            ny = (float) (dy / len);
            nz = (float) (dz / len);
        } else {
            nx = 0; ny = 1; nz = 0;
        }
        vc.addVertex(matrix, (float) a.x, (float) a.y, (float) a.z)
                .setColor(red, green, blue, alpha)
                .setNormal(nx, ny, nz);
        vc.addVertex(matrix, (float) b.x, (float) b.y, (float) b.z)
                .setColor(red, green, blue, alpha)
                .setNormal(nx, ny, nz);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }

    private static void vertex(VertexConsumer vc, Matrix4f matrix,
                                float x, float y, float z, int r, int g, int b, int a) {
        vc.addVertex(matrix, x, y, z).setColor(r, g, b, a);
    }
}
