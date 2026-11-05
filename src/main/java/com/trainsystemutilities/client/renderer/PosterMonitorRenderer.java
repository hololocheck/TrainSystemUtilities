package com.trainsystemutilities.client.renderer;

import com.manta.api.world.CSSWorldRenderer;
import com.manta.api.screen.JsonLayoutHandler.ImageRef;
import com.manta.api.state.AnimationSlot;
import com.manta.api.state.BoolSlot;
import com.manta.api.state.ImageFitSlot;
import com.manta.api.state.ImageSlot;
import com.manta.api.state.MantaState;
import com.manta.api.state.NumberSlot;
import com.manta.api.anim.Animation;
import com.manta.api.anim.Easing;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.trainsystemutilities.client.gui.TsuLayouts;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.block.MonitorBlock;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity;
import com.trainsystemutilities.blockentity.PosterManagementBlockEntity.AnimationType;
import com.trainsystemutilities.item.MonitorLinkCardItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.*;

/**
 * ポスター管理ブロックにリンクされたモニターに画像を描画するレンダラー。
 * Manta の CSSWorldRenderer を使用して全描画をCSSで制御。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class PosterMonitorRenderer {

    private static CSSWorldRenderer cssRenderer;
    private static final Set<BlockPos> posterManagedMonitors = new HashSet<>();
    private static final Map<Long, PosterAnimState> animStates = new HashMap<>();
    /** The layout as a document (= JSON load 1 回のみ; Manta 6 Phase 6 - Manta decides the engine per its registry)。 サイズは handler 経由で動的。 */
    private static CSSWorldRenderer.Document posterDocument;

    private static CSSWorldRenderer.Document getPosterDocument() {
        if (posterDocument == null) {
            posterDocument = CSSWorldRenderer.Document.parse(
                    TsuLayouts.load("layouts/renderers/poster-monitor.json"));
        }
        return posterDocument;
    }

    public static boolean isPosterManaged(BlockPos pos) {
        return posterManagedMonitors.contains(pos);
    }

    private static class PosterAnimState {
        int currentIndex = 0;
        int previousIndex = -1;
        long slideStartNano = System.nanoTime();
        long transitionStartNano = 0;
        boolean transitioning = false;
        boolean phaseOut = false; // true=退場中, false=登場中
        boolean domDirty = true;
        boolean hasBack = false;
        // 表面[0] / 裏面[1] 別に Renderer / Handler をキャッシュ (IR は共通 template)
        CSSWorldRenderer[] renderers = new CSSWorldRenderer[2];
        PosterFace[] faces = new PosterFace[2];
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) return;

        if (cssRenderer == null) {
            cssRenderer = new CSSWorldRenderer(mc.font);
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        BlockPos playerPos = mc.player.blockPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        boolean didRender = false;
        posterManagedMonitors.clear();

        for (BlockEntity be : getPosterBlockEntities(level, playerPos, 64)) {
            if (!(be instanceof PosterManagementBlockEntity posterBE)) continue;

            var card = posterBE.getItem(0);
            List<BlockPos> positions = MonitorLinkCardItem.getRegisteredPositions(card);
            if (positions.isEmpty()) continue;
            posterManagedMonitors.addAll(positions);

            List<UUID> imageIds = posterBE.getActiveImageIds();
            // 1枚のみアニメーションがOFFで1枚の場合、または有効画像なしの場合
            if (imageIds.isEmpty() || !posterBE.isMonitorEnabled()) continue;

            Set<BlockPos> rendered = new HashSet<>();
            for (BlockPos monitorPos : positions) {
                if (rendered.contains(monitorPos)) continue;
                BlockState state = level.getBlockState(monitorPos);
                if (!MonitorBlock.isMonitorBlock(state)) continue;

                Direction facing = state.getValue(MonitorBlock.FACING);
                List<BlockPos> group = findConnectedMonitors(level, monitorPos, facing, positions);
                rendered.addAll(group);

                Direction right = facing.getClockWise();
                int minH = 0, maxH = 0, minV = 0, maxV = 0;
                BlockPos origin = group.get(0);
                for (BlockPos gp : group) {
                    BlockPos diff = gp.subtract(origin);
                    int h = diff.getX() * right.getStepX() + diff.getZ() * right.getStepZ();
                    int v = diff.getY();
                    minH = Math.min(minH, h); maxH = Math.max(maxH, h);
                    minV = Math.min(minV, v); maxV = Math.max(maxV, v);
                }
                int groupW = maxH - minH + 1;
                int groupH = maxV - minV + 1;
                BlockPos frontOrigin = origin.offset(right.getStepX() * maxH, minV, right.getStepZ() * maxH);

                float zOffset = 0.505f;
                Block block = state.getBlock();
                if (block instanceof com.trainsystemutilities.block.ThinDoubleSidedMonitorBlock tdb) {
                    zOffset = tdb.getThickness() / 32f + 0.005f;
                }

                boolean isDoubleSided = group.stream().anyMatch(
                        gp -> MonitorBlock.isDoubleSidedMonitor(level.getBlockState(gp)));

                long posKey = posterBE.getBlockPos().asLong();
                PosterAnimState anim = animStates.computeIfAbsent(posKey, k -> new PosterAnimState());
                anim.hasBack = isDoubleSided;
                updateSlideshow(anim, posterBE);

                renderPosterFace(poseStack, bufferSource, cam, frontOrigin, facing,
                        groupW, groupH, posterBE, anim, false, zOffset);

                if (isDoubleSided) {
                    BlockPos backOrigin = origin.offset(right.getStepX() * minH, minV, right.getStepZ() * minH);
                    renderPosterFace(poseStack, bufferSource, cam, backOrigin, facing,
                            groupW, groupH, posterBE, anim, true, zOffset);
                }
                didRender = true;
            }
        }

        if (didRender) bufferSource.endBatch();

        // animStatesクリーンアップ（64エントリ超過時）
        if (animStates.size() > 64) {
            var it = animStates.entrySet().iterator();
            int rem = animStates.size() - 64;
            while (it.hasNext() && rem > 0) { it.next(); it.remove(); rem--; }
        }
    }

    private static void updateSlideshow(PosterAnimState anim, PosterManagementBlockEntity be) {
        List<UUID> images = be.getActiveImageIds();
        if (images == null || images.isEmpty()) { anim.currentIndex = 0; anim.transitioning = false; return; }
        boolean needsAnim = images.size() > 1 || (images.size() == 1 && be.isAnimateSingle());
        if (!needsAnim) { anim.currentIndex = 0; anim.transitioning = false; return; }
        if (anim.currentIndex >= images.size()) anim.currentIndex = 0;

        long now = System.nanoTime();
        long intervalNano = (long)(be.getSlideInterval() * 1_000_000_000L);
        long halfDurationNano = (long)(be.getAnimationDuration() * 500_000_000L); // 半分ずつ

        if (anim.transitioning) {
            long elapsed = now - anim.transitionStartNano;
            if (anim.phaseOut && elapsed >= halfDurationNano) {
                // 退場完了 → 登場開始
                anim.phaseOut = false;
                anim.transitionStartNano = now;
                anim.domDirty = true;
            } else if (!anim.phaseOut && elapsed >= halfDurationNano) {
                // 登場完了 → 静止
                anim.transitioning = false;
                anim.slideStartNano = now;
                anim.domDirty = true;
            }
        } else if (now - anim.slideStartNano >= intervalNano) {
            // 切替開始: まず退場フェーズ
            anim.previousIndex = anim.currentIndex;
            anim.currentIndex = (anim.currentIndex + 1) % images.size();
            anim.transitionStartNano = now;
            anim.transitioning = true;
            anim.phaseOut = true;
            anim.domDirty = true;
        }
    }

    /**
     * CSS方式でポスター画像を描画。
     * background-image: url() + @keyframes アニメーションで全制御。
     */
    private static void renderPosterFace(PoseStack poseStack, MultiBufferSource bufferSource,
                                          Vec3 cam, BlockPos pos, Direction facing,
                                          int monitorW, int monitorH,
                                          PosterManagementBlockEntity be, PosterAnimState anim,
                                          boolean isBack, float zOffset) {
        if (monitorW <= 0 || monitorH <= 0) return;
        List<UUID> images = be.getImageIds();
        if (images == null || images.isEmpty()) return;

        int cssW = monitorW * 128;
        int cssH = monitorH * 128;
        int pad = 16; // ベゼルパディング

        poseStack.pushPose();
        poseStack.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        poseStack.translate(0.5, 0.5, 0.5);
        float rotation = switch (facing) {
            case NORTH -> 180f; case SOUTH -> 0f; case WEST -> -90f; case EAST -> 90f; default -> 0f;
        };
        if (isBack) rotation += 180f;
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        poseStack.translate(-0.5, -0.5, zOffset);

        float scale = 1.0f / 128.0f;
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0, cssH, 0);
        poseStack.scale(1, -1, -1);

        // 表面/裏面でDOM/CSS/Rendererを分離
        int faceIdx = isBack ? 1 : 0;
        if (anim.renderers[faceIdx] == null) {
            anim.renderers[faceIdx] = new CSSWorldRenderer(Minecraft.getInstance().font);
        }

        // Manta 7 Phase 5: the face WRITES its values into its page (nothing is asked) - the page is
        // opened at the monitor's css size (its root's cssW / cssH are that size), then drawn.
        if (anim.faces[faceIdx] == null) {
            anim.faces[faceIdx] = new PosterFace();
        }
        PosterFace face = anim.faces[faceIdx];
        CSSWorldRenderer r = anim.renderers[faceIdx];
        face.push(r.page(getPosterDocument(), face, cssW, cssH), be, anim, cssW, cssH, pad);
        r.render(getPosterDocument(), face, poseStack, bufferSource);

        poseStack.popPose();
        poseStack.popPose();
    }

    /**
     * One monitor face's poster values, WRITTEN into its page each frame (Manta 7 Phase 5): what the
     * pull handler answered, in the same state - the padding and content box, the image and how it
     * fits, the slide animation while a transition runs, and whether the poster shows. The page's
     * root size keys (cssW / cssH) are written by the renderer with the size the page opened at.
     */
    private static final class PosterFace {
        private Object page;
        private MantaState st;
        private NumberSlot nPad, nContentW, nContentH;
        private ImageFitSlot fPoster;
        private ImageSlot iPoster;
        private AnimationSlot aPoster;
        private BoolSlot bVisible;

        void push(Object opened, PosterManagementBlockEntity be, PosterAnimState anim, int cssW, int cssH, int pad) {
            if (opened == null) {
                return;
            }
            if (opened != page) {
                page = opened;
                st = MantaState.of(opened);
                nPad = st.number("pad");
                nContentW = st.number("contentW");
                nContentH = st.number("contentH");
                // The fit is elided with the image (no slot): pushed by its bgImageFitKey. Asking number() for it
                // threw on the render thread - a client crash whenever a poster was in view (manta 2.60.0-2.61.0).
                fPoster = st.imageFit("posterFit");
                iPoster = st.image("posterImg");
                aPoster = st.animation("posterAnim");
                bVisible = st.bool("posterVisible");
                st.textByKeyOnly();
            }
            st.set(nPad, pad);
            st.set(nContentW, cssW - pad * 2);
            st.set(nContentH, cssH - pad * 2);
            // 「モニターに合わせる」ON = CONTAIN (全体を収める) / OFF = COVER (埋めて切り抜く)。
            // 値は IrNode.bgImageFit と同じ 0=STRETCH / 1=COVER / 2=CONTAIN。
            st.set(fPoster, be.isFitToMonitor() ? 2 : 1);
            ImageRef image = image(be, anim);
            if (image == null) {
                st.clear(iPoster);
            } else {
                st.set(iPoster, image);
            }
            Animation slide = slide(be, anim, cssW, cssH);
            if (slide == null) {
                st.clear(aPoster);
            } else {
                st.set(aPoster, slide);
            }
            List<UUID> images = be.getActiveImageIds();
            st.set(bVisible, !images.isEmpty() && be.isMonitorEnabled());
        }

        /** The image shown: the previous one while it slides out, else the current one; none until its texture is loaded. */
        private static ImageRef image(PosterManagementBlockEntity be, PosterAnimState anim) {
            List<UUID> images = be.getActiveImageIds();
            if (images.isEmpty()) return null;
            // phaseOut: show previous image. Otherwise show current.
            int idx;
            if (anim.transitioning && anim.phaseOut
                    && anim.previousIndex >= 0 && anim.previousIndex < images.size()) {
                idx = anim.previousIndex;
            } else {
                idx = Math.min(anim.currentIndex, images.size() - 1);
            }
            UUID imageId = images.get(idx);
            ResourceLocation tex = PosterTextureManager.getOrRequest(imageId);
            if (tex == null) return null;
            // ImageNode は ImageRef の width/height からアスペクト比を出す。 ここで固定値を返すと
            // 非正方モニターでも常に正方形に描画されるため、 decode 時の実寸を渡すこと。
            int[] size = PosterTextureManager.getDimensions(imageId);
            if (size == null) return null; // texture と dimensions は同一 cache 由来 (LRU 追い出しの競合時のみ)
            return new ImageRef(tex, size[0], size[1]);
        }

        /** The slide while a transition runs, else none. */
        private static Animation slide(PosterManagementBlockEntity be, PosterAnimState anim, int cssW, int cssH) {
            if (!anim.transitioning) return null;
            long durationMs = (long)(be.getAnimationDuration() * 500); // half-duration
            // The slide covers the whole face (MANTA_6_CUTOVER decision 24a, 2026-09-12): the
            // document's scissor sits on its root (the face) instead of poster-clip (the padding),
            // so BOTH engines clip the image at the monitor's edge - Manta 5's ScissorNode clips in
            // world space too, and taking the scissor off altogether left the Manta 5 fallback with
            // no clip at all (final reading, 2026-09-12). cssW - 2 * pad stopped at the inner edge.
            float sh = (float) cssW;
            float sv = (float) cssH;
            AnimationType type = be.getAnimationType();
            return buildSlideAnim(type, anim.phaseOut, durationMs, sh, sv);
        }

        private static Animation buildSlideAnim(AnimationType type, boolean phaseOut,
                                                  long durationMs, float sh, float sv) {
            Animation.Builder b = Animation.of(durationMs).easing(phaseOut ? Easing.EASE_IN : Easing.EASE_OUT);
            return switch (type) {
                case SLIDE_LEFT -> (phaseOut
                        ? b.translateX(0, -sh) : b.translateX(sh, 0)).build();
                case SLIDE_RIGHT -> (phaseOut
                        ? b.translateX(0, sh) : b.translateX(-sh, 0)).build();
                case SLIDE_UP -> (phaseOut
                        ? b.translateY(0, -sv) : b.translateY(sv, 0)).build();
                case SLIDE_DOWN -> (phaseOut
                        ? b.translateY(0, sv) : b.translateY(-sv, 0)).build();
                case FLIP -> (phaseOut
                        ? b.scaleX(1f, 0f) : b.scaleX(0f, 1f)).build();
                case ZOOM_IN -> (phaseOut
                        ? b.opacity(1f, 0f) : b.scale(0.8f, 1f).opacity(0f, 1f)).build();
                // Phase 5f++++ Step 12.D: opacity を Animation で正しく補間
                case FADE -> (phaseOut
                        ? b.opacity(1f, 0f) : b.opacity(0f, 1f)).build();
                case SLIDE_LEFT_FADE -> (phaseOut
                        ? b.translateX(0, -sh / 2).opacity(1f, 0f)
                        : b.translateX(sh / 2, 0).opacity(0f, 1f)).build();
                case SLIDE_UP_FADE -> (phaseOut
                        ? b.translateY(0, -sv / 2).opacity(1f, 0f)
                        : b.translateY(sv / 2, 0).opacity(0f, 1f)).build();
                case ZOOM_FADE -> (phaseOut
                        ? b.opacity(1f, 0f) : b.scale(1.2f, 1f).opacity(0f, 1f)).build();
                case NONE -> null;
            };
        }
    }

    @SuppressWarnings("unused")

    // ===== ヘルパー =====

    private static List<BlockPos> findConnectedMonitors(Level level, BlockPos start, Direction facing,
                                                         List<BlockPos> allowedPositions) {
        List<BlockPos> connected = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        Set<BlockPos> allowed = new HashSet<>(allowedPositions);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) continue;
            if (!allowed.contains(current)) continue;
            BlockState state = level.getBlockState(current);
            if (!MonitorBlock.isMonitorBlock(state)) continue;
            if (state.getValue(MonitorBlock.FACING) != facing) continue;

            connected.add(current);
            queue.add(current.above());
            queue.add(current.below());
            queue.add(current.relative(facing.getClockWise()));
            queue.add(current.relative(facing.getCounterClockWise()));
        }
        return connected;
    }

    private static List<BlockEntity> getPosterBlockEntities(Level level, BlockPos center, int range) {
        List<BlockEntity> result = new ArrayList<>();
        Set<Long> checked = new HashSet<>();
        for (int x = -range; x <= range; x += 16) {
            for (int z = -range; z <= range; z += 16) {
                BlockPos cp = center.offset(x, 0, z);
                long key = ((long)(cp.getX() >> 4) << 32) | ((long)(cp.getZ() >> 4) & 0xFFFFFFFFL);
                if (!checked.add(key)) continue;
                var chunk = level.getChunkAt(cp);
                if (chunk != null) {
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof PosterManagementBlockEntity && be.getBlockPos().distSqr(center) < range * range) {
                            result.add(be);
                        }
                    }
                }
            }
        }
        return result;
    }
}
