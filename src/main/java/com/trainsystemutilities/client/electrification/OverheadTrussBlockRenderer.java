package com.trainsystemutilities.client.electrification;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import com.trainsystemutilities.electrification.blockentity.OverheadTrussBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Geckolib BER: 架線トラスの描画 + ANGLE_8 (= 8 方向) rotation を適用。
 * model 切替 (通常 / 角) は GeoModel 側で BlockState を読んで自動選択。
 */
public class OverheadTrussBlockRenderer extends GeoBlockRenderer<OverheadTrussBlockEntity> {

    public OverheadTrussBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(new OverheadTrussGeoModel());
    }

    /** 描画距離拡張: デフォルト 64 → 256 ブロック。 Create 列車に追従して遠景でも消えない。 */
    @Override
    public int getViewDistance() { return 256; }

    /** Y offset: bone Y 17..35 で構造を持つ geo を normal cardinal と整合させる shift。 */
    private static final float Y_OFFSET = -17.0f / 16.0f;

    /**
     * **16 方向 (= ANGLE_16) 用 offset テーブル** (= [dx, dy, dz])。
     *
     * <p>各 angle16 (= 0..15) に対し model の表示位置を微調整する。 中間角度 (= odd) では
     * diagonal model を 22.5° rotate で表示するため、 cardinal/diagonal 純粋角度とは別の
     * offset が必要。 ユーザーが視覚チェックして値をチューニング可能。
     *
     * <p>index = round(yawDeg / 22.5) % 16
     */
    private static final float[][] ANGLE16_OFFSETS = {
        // angle16, 方向,    [dx,    dy,         dz]
        /*  0 = 0°    N */ { 0f,    0f,          0f },
        /*  1 = 22.5°    */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /*  2 = 45°  NE */ { 0f,    Y_OFFSET,    0f },  // diagonal 純粋
        /*  3 = 67.5°    */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /*  4 = 90°   E */ { 0f,    0f,          0f },
        /*  5 = 112.5°   */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /*  6 = 135° SE */ { 0f,    Y_OFFSET,    0f },  // diagonal 純粋
        /*  7 = 157.5°   */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /*  8 = 180°  S */ { 0f,    0f,          0f },
        /*  9 = 202.5°   */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /* 10 = 225° SW */ { 0f,    Y_OFFSET,    0f },  // diagonal 純粋
        /* 11 = 247.5°   */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /* 12 = 270°  W */ { 0f,    0f,          0f },
        /* 13 = 292.5°   */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
        /* 14 = 315° NW */ { 0f,    Y_OFFSET,    0f },  // diagonal 純粋
        /* 15 = 337.5°   */ { 0f,    Y_OFFSET,    0f },  // 中間: 要調整
    };

    private static float[] getOffsetForYaw(float yawDeg) {
        int idx = Math.round(yawDeg / 22.5f) % 16;
        if (idx < 0) idx += 16;
        return ANGLE16_OFFSETS[idx];
    }

    private static final int BEAM_COLOR = 0xFF202020;  // 黒 (= truss 色)
    private static final float BEAM_THICKNESS = 0.125f;  // 太さ = 2 voxel
    private static final float BEAM_HEIGHT_GAP = 1.0f;   // 上下 beam の Y 間隔
    private static final float BRACE_INTERVAL = 0.5f;    // ブレース間隔
    private static final float BRACE_THICKNESS = 0.1875f; // 太い (= 3 voxel)
    private static final float KNEE_BRACE_LEN = 1.0f;  // 端の三角補強の足の長さ (= 1 block)
    private static final float BEAM_OVERHANG = 0.5f;  // beam を pole 位置から外側に突き出す長さ

    /** 塗りつぶし quad 用 RenderType (= debugQuads は no-lighting 不透明)。 */
    private static final RenderType FILL_QUADS = RenderType.debugQuads();

    /**
     * **動的 truss 構造描画**: anchorA → anchorB を結ぶ 3D 立体トラス。
     *
     * <p>構成:
     * <ul>
     *   <li>上下 2 段 beam (= 太さ 2 voxel の 3D box)</li>
     *   <li>両端の縦支柱 (= 太い box)</li>
     *   <li>V 字 ブレース (= 細い斜め線、 連続配置)</li>
     * </ul>
     */
    private static void renderDynamicBeam(OverheadTrussBlockEntity be, PoseStack pose,
                                            MultiBufferSource buffer) {
        Vec3 a = be.getAnchorA();
        Vec3 b = be.getAnchorB();
        if (a == null || b == null) return;

        BlockPos pos = be.getBlockPos();
        Vec3 la = new Vec3(a.x - pos.getX(), a.y - pos.getY(), a.z - pos.getZ());
        Vec3 lb = new Vec3(b.x - pos.getX(), b.y - pos.getY(), b.z - pos.getZ());

        Vec3 dir = lb.subtract(la);
        double length = dir.length();
        if (length < 1.0E-4) return;

        // pose を anchorA に移動 + dir 方向に Y 軸回転
        // 初期 beam が +Z 方向 (0,0,length) で、 rotation 後に dir に一致させる:
        //   rot(θ) * (0,0,L) = (L sin θ, 0, L cos θ)、 θ = atan2(dir.x, dir.z)
        pose.pushPose();
        pose.translate((float) la.x, (float) la.y, (float) la.z);
        float yawDeg = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yawDeg));
        float beamLen = (float) length;

        VertexConsumer vc = buffer.getBuffer(FILL_QUADS);
        Matrix4f m = pose.last().pose();

        int r = (BEAM_COLOR >> 16) & 0xFF;
        int g = (BEAM_COLOR >>  8) & 0xFF;
        int bC = BEAM_COLOR & 0xFF;
        int aC = (BEAM_COLOR >> 24) & 0xFF;
        float ht = BEAM_THICKNESS * 0.5f;

        // 下段 / 上段 beam: pole 位置 (= z=0、 z=beamLen) より外側に overhang して描画
        float overhang = BEAM_OVERHANG;
        float beamStart = -overhang;
        float beamEnd = beamLen + overhang;
        drawAxisBox(vc, m, -ht, -ht, beamStart, ht, ht, beamEnd, r, g, bC, aC);
        drawAxisBox(vc, m, -ht, BEAM_HEIGHT_GAP - ht, beamStart, ht, BEAM_HEIGHT_GAP + ht, beamEnd, r, g, bC, aC);

        // 両端 縦支柱 (= pole 中心 z=0、 z=beamLen)
        drawAxisBox(vc, m, -ht, -ht, -ht, ht, BEAM_HEIGHT_GAP + ht, ht, r, g, bC, aC);
        drawAxisBox(vc, m, -ht, -ht, beamLen - ht, ht, BEAM_HEIGHT_GAP + ht, beamLen + ht, r, g, bC, aC);

        // **両端 knee brace** (= pole 内側、 上段 beam 下面 → 下段 beam 上面 の「\」 / 「/」):
        // beam の中心ではなく外面に接続して Z-fighting / 埋没を回避
        float kneeLen = Math.min(KNEE_BRACE_LEN, beamLen * 0.4f);
        float kneeYTop = BEAM_HEIGHT_GAP - ht;  // 上段 beam の下面
        float kneeYBot = ht;                    // 下段 beam の上面
        // 端 A: 上段 beam 下面 (z=0, y=kneeYTop) → 下段 beam 上面 内側 (z=kneeLen, y=kneeYBot)
        drawTiltedBrace(vc, pose, 0f, kneeYTop, kneeLen, kneeYBot, BRACE_THICKNESS, r, g, bC, aC);
        // 端 B mirror
        drawTiltedBrace(vc, pose, beamLen, kneeYTop, beamLen - kneeLen, kneeYBot, BRACE_THICKNESS, r, g, bC, aC);

        // V 字 ブレース (= 立体 box、 zig-zag) — knee brace 領域 (= 両端 kneeLen) は skip
        int braces = Math.max(2, (int) Math.floor(beamLen / BRACE_INTERVAL));
        float perBraceLen = beamLen / braces;
        int kneeSkipCount = (int) Math.ceil(kneeLen / perBraceLen);
        for (int i = kneeSkipCount; i < braces - kneeSkipCount; i++) {
            float z1 = (float) i / braces * beamLen;
            float z2 = (float) (i + 1) / braces * beamLen;
            // 偶数: / (下→上)、 奇数: \ (上→下)
            float y1, y2;
            if (i % 2 == 0) {
                y1 = ht; y2 = BEAM_HEIGHT_GAP - ht;
            } else {
                y1 = BEAM_HEIGHT_GAP - ht; y2 = ht;
            }
            drawTiltedBrace(vc, pose, z1, y1, z2, y2, BRACE_THICKNESS, r, g, bC, aC);
        }

        pose.popPose();
    }

    /** axis-aligned box (= 6 face) を fill quad で描画。 */
    private static void drawAxisBox(VertexConsumer vc, Matrix4f m,
                                      float x1, float y1, float z1, float x2, float y2, float z2,
                                      int r, int g, int b, int a) {
        // 下面 (y = y1、 normal -Y)
        addQuad(vc, m, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, 0, -1, 0, r, g, b, a);
        // 上面 (y = y2、 normal +Y)
        addQuad(vc, m, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, 0, 1, 0, r, g, b, a);
        // 北面 (z = z1、 normal -Z)
        addQuad(vc, m, x1, y2, z1, x2, y2, z1, x2, y1, z1, x1, y1, z1, 0, 0, -1, r, g, b, a);
        // 南面 (z = z2、 normal +Z)
        addQuad(vc, m, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, 0, 0, 1, r, g, b, a);
        // 西面 (x = x1、 normal -X)
        addQuad(vc, m, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, -1, 0, 0, r, g, b, a);
        // 東面 (x = x2、 normal +X)
        addQuad(vc, m, x2, y2, z1, x2, y2, z2, x2, y1, z2, x2, y1, z1, 1, 0, 0, r, g, b, a);
    }

    private static void addQuad(VertexConsumer vc, Matrix4f m,
                                  float x1, float y1, float z1, float x2, float y2, float z2,
                                  float x3, float y3, float z3, float x4, float y4, float z4,
                                  float nx, float ny, float nz, int r, int g, int b, int a) {
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(nx, ny, nz);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a).setNormal(nx, ny, nz);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a).setNormal(nx, ny, nz);
        vc.addVertex(m, x4, y4, z4).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }

    /** 斜め V 字ブレース: 立体 box を pose stack で斜め回転して描画。 */
    private static void drawTiltedBrace(VertexConsumer vc, PoseStack pose,
                                          float z1, float y1, float z2, float y2,
                                          float thickness, int r, int g, int b, int a) {
        float dy = y2 - y1, dz = z2 - z1;
        float length = (float) Math.sqrt(dy * dy + dz * dz);
        if (length < 1.0E-4f) return;

        pose.pushPose();
        float midY = (y1 + y2) * 0.5f;
        float midZ = (z1 + z2) * 0.5f;
        pose.translate(0f, midY, midZ);
        float pitchDeg = (float) Math.toDegrees(Math.atan2(dy, dz));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-pitchDeg));

        float halfLen = length * 0.5f;
        float ht = thickness * 0.5f;
        drawAxisBox(vc, pose.last().pose(),
                -ht, -ht, -halfLen, ht, ht, halfLen, r, g, b, a);

        pose.popPose();
    }

    private static void line(VertexConsumer vc, Matrix4f m,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a).setNormal(nx, ny, nz);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }

    @Override
    public void render(OverheadTrussBlockEntity be, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // 動的 anchor 方式は廃止。 旧 8 方向 model 描画のみ使う。
        int angle8 = be.getBlockState().getValue(OverheadTrussBlock.ANGLE_8);
        boolean corner = be.getBlockState().getValue(OverheadTrussBlock.CORNER);
        boolean diagonal = (angle8 % 2 == 1);
        // BE float yaw 優先、 無ければ ANGLE_8 ベース
        float yawDeg = be.hasCustomYaw() ? be.getYawDegrees() : (angle8 * 45f);
        boolean needsRotation = Math.abs(yawDeg) > 0.01f;
        // 16 方向用 offset テーブルから取得 (= cardinal/diagonal/中間角度ごとに別値)
        float[] offset;
        if (be.hasCustomYaw()) {
            offset = getOffsetForYaw(yawDeg);
        } else {
            // 旧 ANGLE_8 配置: 既存の corner/diagonal 判定で Y_OFFSET
            boolean needsYOffset = (corner || diagonal);
            offset = needsYOffset ? new float[]{0f, Y_OFFSET, 0f} : new float[]{0f, 0f, 0f};
        }
        boolean needsOffset = (offset[0] != 0f || offset[1] != 0f || offset[2] != 0f);

        if (!needsRotation && !needsOffset) {
            super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
            return;
        }

        poseStack.pushPose();
        if (needsOffset) {
            poseStack.translate(offset[0], offset[1], offset[2]);
        }
        if (needsRotation) {
            poseStack.translate(0.5, 0, 0.5);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yawDeg));
            poseStack.translate(-0.5, 0, -0.5);
        }
        super.render(be, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }
}
