package com.trainsystemutilities.client.renderer;

import com.manta.api.ir.IrBuilder;
import com.manta.api.ir.IrNode;
import com.manta.api.screen.JsonLayoutHandler;
import com.manta.api.world.CSSWorldRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.LineSymbol;
import com.trainsystemutilities.structure.block.StationNameSignBlock;
import com.trainsystemutilities.structure.blockentity.StationNameSignBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 駅名サインの表示面 (Manta V3 IR) をワールドへ描く。
 *
 * <p>BER ではなく {@link RenderLevelStageEvent} 側に置いてあるのは、 共有 {@code MultiBufferSource}
 * の flush 順序を {@link MonitorWorldRenderer} と揃えるため。
 *
 * <p><b>面変換の z 向き (重要):</b> CSS 座標系は「z+ = 手前 (視線側)」を前提に IR が内部 z
 * (bg 0.01 &lt; border 0.02 &lt; text 0.03) を積む。 y 反転と一緒に z まで反転する変換
 * ({@code scale(1,-1,-1)}) を使うと この積みが視線と逆になり、 <b>IR テキストが同じ IR の不透明
 * 背景 (帯 / バッジ) の裏に隠れる</b>。 本 renderer は {@code scale(1,-1,1)} で CSS z+ を視線側へ
 * 向けており、 テキスト / 帯 / バッジ全てを通常の JSON layout (IR) だけで描ける。
 * (モニター系は歴史的に z 反転のままで、 背景がほぼ透明なため表面化していない。)
 *
 * <p>面のジオメトリはモデル実測: 白い面は Z −31.25..+31.25 / Y 0.75..15.25 の X=±3 両面。
 * 1 ブロック = 128 px 規約より キャンバスは 500 × 116 px。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class StationSignWorldRenderer {

    private static final int CANVAS_W = 500;   // 62.5 voxel
    private static final int CANVAS_H = 116;   // 14.5 voxel
    /** 白い面の上端 (床から voxel 15.25) の px 換算。 */
    private static final float CANVAS_TOP_PX = 122f;
    /** 板の表面 (モデル X = ±3) + z-fighting 回避分。 */
    private static final float PANEL_Z = 3f / 16f + 0.002f;

    /** 路線記号バッジ (キャンバス座標)。 layout JSON の sign-badge-* テキストと対で合わせる。 */
    private static final int BADGE_X = 12;
    private static final int BADGE_Y = 6;
    private static final int BADGE_SIZE = 48;
    private static final int BADGE_BORDER = 4;

    private static final int SCAN_RANGE = 64;

    private static volatile IrNode sharedIr;
    private static volatile IrNode sharedBadgeTextIr;

    private static final Map<Long, SignHandler> handlers = new HashMap<>();
    private static final Map<Long, CSSWorldRenderer> renderers = new HashMap<>();
    private static final Map<Long, CSSWorldRenderer> badgeRenderers = new HashMap<>();

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        boolean didRender = false;

        for (BlockEntity be : nearbySigns(level, mc.player.blockPosition())) {
            StationNameSignBlockEntity sign = (StationNameSignBlockEntity) be;
            if (!sign.isMaster() || !sign.isInGroup()) continue;
            try {
                Direction facing = sign.getBlockState().getValue(StationNameSignBlock.FACING);
                Direction axis = StationNameSignBlock.widthAxis(facing);
                BlockPos pos = sign.getBlockPos();

                poseStack.pushPose();
                poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
                // master セル中心 → 4 セルの中央 (モデル原点) へ
                poseStack.translate(axis.getStepX() * 0.5, 0, axis.getStepZ() * 0.5);
                renderFace(sign, poseStack, bufferSource, facing, false);
                renderFace(sign, poseStack, bufferSource, facing, true);
                poseStack.popPose();
                didRender = true;
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.error("Station sign render error at {}: {}",
                        be.getBlockPos(), e.getMessage(), e);
            }
        }

        if (didRender) {
            bufferSource.endBatch();
        }
        if (handlers.size() > 64) {
            handlers.clear();
            renderers.clear();
            badgeRenderers.clear();
        }
    }

    /** 白い面 1 枚ぶん。 back=true で裏面 (180° 回転)。 */
    private static void renderFace(StationNameSignBlockEntity be, PoseStack poseStack,
                                   MultiBufferSource bufferSource, Direction facing, boolean back) {
        long key = (be.getBlockPos().asLong() << 1) ^ (back ? 1L : 0L);

        poseStack.pushPose();
        poseStack.translate(0.5, 0, 0.5);
        float rot = switch (facing) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> -90f;
            case EAST -> 90f;
            default -> 0f;
        };
        if (back) rot += 180f;
        poseStack.mulPose(Axis.YP.rotationDegrees(rot));

        poseStack.translate(-(CANVAS_W / 128f) / 2f, 0, PANEL_Z);
        poseStack.scale(1f / 128f, 1f / 128f, 1f / 128f);
        poseStack.translate(0, CANVAS_TOP_PX, 0);
        // y のみ反転し、 z は CSS の向き (z+ = 視線側) のまま残す。 z まで反転すると IR テキストが
        // 同じツリーの不透明背景の裏へ回る (クラス javadoc 参照)。
        poseStack.scale(1, -1, 1);

        SignHandler handler = handlers.computeIfAbsent(key, k -> new SignHandler());
        handler.update(be);

        renderers.computeIfAbsent(key, k -> nonEmissiveRenderer())
                .renderV3FromIr(getSharedIr(), handler, poseStack, bufferSource);

        // バッジはモニターの路線記号バッジと同一構造で描く: 不透明の面 quad は白背景 1 枚だけ、
        // 縁の角丸リングと区切り線は text バッファ (VectorRenderer)、 文字は IR テキスト。
        // translucent バッファは毎フレーム視点距離で quad を並べ替えるため、 ほぼ同一平面の
        // 不透明 quad を複数重ねると移動時に順序が入れ替わってちらつく (z を離しても消えない —
        // 実機で確認)。 重なる面 quad を 1 枚にすることでちらつきの発生源そのものを断つ。
        LineSymbol sym = be.getSymbol();
        if (sym != null) {
            int radius = Math.round(sym.getBorderRadius() * BADGE_SIZE / 40f);
            int borderColor = parseHexColor(sym.getBorderColor(), 0xFF4fc3f7);
            int inner = BADGE_SIZE - BADGE_BORDER * 2;
            int dividerW = inner - 8;

            CSSWorldRenderer br = badgeRenderers.computeIfAbsent(key, k -> nonEmissiveRenderer());
            poseStack.pushPose();
            poseStack.translate(0, 0, 0.04f);
            br.renderV3FromIr(buildBadgeBgIr(radius), null, poseStack, bufferSource);

            var vc = com.manta.api.draw.VectorRenderer.getWorldBufferText(bufferSource);
            com.manta.api.draw.VectorRenderer.strokeRoundedRect(vc, poseStack.last().pose(),
                    BADGE_X, BADGE_Y, BADGE_SIZE, BADGE_SIZE, borderColor,
                    (float) BADGE_BORDER, (float) radius, 0.05f);
            com.manta.api.draw.VectorRenderer.textFillRect(vc, poseStack.last().pose(),
                    BADGE_X + (BADGE_SIZE - dividerW) / 2f, BADGE_Y + BADGE_SIZE / 2f - 1f,
                    dividerW, 2f, borderColor, 0.05f);

            poseStack.translate(0, 0, 0.06f);
            br.renderV3FromIr(getBadgeTextIr(), handler, poseStack, bufferSource);
            poseStack.popPose();
        }

        poseStack.popPose();
    }

    /** バッジの白背景 (不透明の面 quad はこの 1 枚だけ)。 縁 / 区切り線 / 文字は上へ別バッファで載る。 */
    private static IrNode buildBadgeBgIr(int radius) {
        return IrBuilder.div()
                .addClass("sign-badge")
                .rect(BADGE_X, BADGE_Y, BADGE_SIZE, BADGE_SIZE)
                .bgColor(0xFFFFFFFF)
                .borderRadius(radius)
                .build();
    }

    /** モニターと違い看板は自発光しないので emissive を切る (既定 true = 全明で描かれ、
     *  シェーダー使用時に白飛びする)。 */
    private static CSSWorldRenderer nonEmissiveRenderer() {
        CSSWorldRenderer r = new CSSWorldRenderer(Minecraft.getInstance().font);
        r.setEmissive(false);
        return r;
    }

    private static List<BlockEntity> nearbySigns(Level level, BlockPos center) {
        List<BlockEntity> result = new ArrayList<>();
        Set<Long> checked = new HashSet<>();
        for (int x = -SCAN_RANGE; x <= SCAN_RANGE; x += 16) {
            for (int z = -SCAN_RANGE; z <= SCAN_RANGE; z += 16) {
                BlockPos cp = center.offset(x, 0, z);
                long ck = ((long) (cp.getX() >> 4) << 32) | ((long) (cp.getZ() >> 4) & 0xFFFFFFFFL);
                if (!checked.add(ck)) continue;
                var chunk = level.getChunkAt(cp);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof StationNameSignBlockEntity
                            && be.getBlockPos().distSqr(center) < (double) SCAN_RANGE * SCAN_RANGE) {
                        result.add(be);
                    }
                }
            }
        }
        return result;
    }

    private static IrNode getSharedIr() {
        IrNode ir = sharedIr;
        if (ir == null) {
            synchronized (StationSignWorldRenderer.class) {
                ir = sharedIr;
                if (ir == null) {
                    ir = compileLayout("layouts/renderers/station-name-sign.json");
                    sharedIr = ir;
                }
            }
        }
        return ir;
    }

    private static IrNode getBadgeTextIr() {
        IrNode ir = sharedBadgeTextIr;
        if (ir == null) {
            synchronized (StationSignWorldRenderer.class) {
                ir = sharedBadgeTextIr;
                if (ir == null) {
                    ir = compileLayout("layouts/renderers/station-name-sign-badge.json");
                    sharedBadgeTextIr = ir;
                }
            }
        }
        return ir;
    }

    private static IrNode compileLayout(String path) {
        String json = com.trainsystemutilities.client.gui.TsuLayouts.load(path);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        return com.manta.api.ir.compiler.JsonToIrCompiler.compile(root).root();
    }

    static int parseHexColor(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** per-frame の表示値を JSON layout の binding へ渡す handler。 */
    private static final class SignHandler implements JsonLayoutHandler {
        private String stationName = "";
        private String prevName = "";
        private String nextName = "";
        private String symLetters = "";
        private String symNumber = "";
        private boolean hasSymbol;
        private int bandColor = 0xFF4fc3f7;
        private int nameScale = 400;

        void update(StationNameSignBlockEntity be) {
            stationName = be.getStationName();
            prevName = be.getPrevStationName();
            nextName = be.getNextStationName();
            LineSymbol sym = be.getSymbol();
            hasSymbol = sym != null;
            if (sym != null) {
                bandColor = parseHexColor(sym.getBorderColor(), 0xFF4fc3f7);
                symLetters = sym.getLetters();
                symNumber = sym.getNumberStr();
            } else {
                symLetters = "";
                symNumber = "";
            }

            int w = Minecraft.getInstance().font.width(stationName);
            int avail = hasSymbol ? CANVAS_W - BADGE_X * 2 - BADGE_SIZE : CANVAS_W - 40;
            nameScale = w <= 0 ? 400 : Math.max(100, Math.min(400, avail * 100 / w));
        }

        private static boolean has(String[] classes, String name) {
            if (classes == null) return false;
            for (String c : classes) if (name.equals(c)) return true;
            return false;
        }

        @Override
        public String getDynamicText(String[] classes, String defaultText) {
            if (has(classes, "sign-name")) return stationName;
            if (has(classes, "sign-prev")) return prevName;
            if (has(classes, "sign-next")) return nextName;
            if (has(classes, "sign-badge-letters")) return symLetters;
            if (has(classes, "sign-badge-number")) return symNumber;
            return null;
        }

        @Override
        public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
            if ("nameScale".equals(key)) return nameScale;
            if ("sideScale".equals(key)) return 180;
            if ("badgeScale".equals(key)) return 150;
            return null;
        }

        @Override
        public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
            if ("bandColor".equals(key)) return bandColor;
            return null;
        }

        @Override
        public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
            if ("signVisible".equals(key)) return true;
            if ("bandVisible".equals(key)) return hasSymbol;
            return null;
        }
    }
}
