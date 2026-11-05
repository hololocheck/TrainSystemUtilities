package com.trainsystemutilities.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.StationRangeToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 駅範囲指定ツールの選択範囲をワールド空間にワイヤーフレームで描画する。
 *
 * <p>{@link com.trainsystemutilities.client.renderer.TrainPresetRangeRenderer}
 * と同じ描画パイプライン (LevelRenderer.renderLineBox + smooth follow)。
 * 列車プリセットツールとの視覚的一貫性のため色は緑 (0, 1, 0) で区別。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class StationRangeRenderer {

    private static final double MAX_LOOK_DISTANCE = 64.0;
    /** 視線追従スムージング (= BelugaExperience 標準 SmoothFollow、 LERP_SPEED 12)。 */
    private static final com.manta.api.hud.SmoothFollow SMOOTH =
            new com.manta.api.hud.SmoothFollow();

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();

        if (mainHand.is(ModItems.STATION_RANGE_TOOL.get())) {
            renderToolOutline(mainHand, event.getPoseStack(), camera, bufferSource);
        }
        if (offHand.is(ModItems.STATION_RANGE_TOOL.get())) {
            renderToolOutline(offHand, event.getPoseStack(), camera, bufferSource);
        }

        bufferSource.endBatch();
    }

    private static void renderToolOutline(ItemStack stack, PoseStack poseStack, Vec3 camera,
                                          MultiBufferSource bufferSource) {
        if (stack.isEmpty()) return;
        int toolMode = StationRangeToolItem.getToolMode(stack);

        // VIEW mode: 既存グループの枠を全て表示
        if (toolMode == StationRangeToolItem.TOOL_MODE_VIEW) {
            String dim = Minecraft.getInstance().level == null ? null
                    : Minecraft.getInstance().level.dimension().location().toString();
            for (var g : com.trainsystemutilities.station.StationGroupClientCache.all()) {
                if (dim != null && !dim.equals(g.dimensionId())) continue;
                renderBox(poseStack, camera, bufferSource,
                        g.minPos(), g.maxPos(), 0.4f, 0.7f, 1.0f, 0.55f); // 水色
            }
            return;
        }
        // GUI mode: outline 描画なし
        if (toolMode == StationRangeToolItem.TOOL_MODE_GUI) {
            return;
        }

        BlockPos pos1 = StationRangeToolItem.getPos1(stack);
        BlockPos pos2 = StationRangeToolItem.getPos2(stack);
        int editMode = StationRangeToolItem.getEditMode(stack);

        // editMode 1: pos1 を視線先に追従 (黄色)
        if (editMode == 1) {
            BlockPos look = getLookTargetPos();
            if (look == null) return;
            if (pos2 != null) {
                BlockPos smooth = SMOOTH.update(look);
                renderBox(poseStack, camera, bufferSource, smooth, pos2, 1f, 1f, 0f, 0.3f);
            } else {
                SMOOTH.reset();
                renderBox(poseStack, camera, bufferSource, look, look, 1f, 1f, 0f, 0.4f);
            }
            return;
        }
        if (editMode == 2) {
            BlockPos look = getLookTargetPos();
            if (look == null) return;
            if (pos1 != null) {
                BlockPos smooth = SMOOTH.update(look);
                renderBox(poseStack, camera, bufferSource, pos1, smooth, 1f, 1f, 0f, 0.3f);
            } else {
                SMOOTH.reset();
                renderBox(poseStack, camera, bufferSource, look, look, 1f, 1f, 0f, 0.4f);
            }
            return;
        }

        // 通常モード (editMode 0)
        if (pos1 == null && pos2 == null) {
            SMOOTH.reset();
            BlockPos look = getLookTargetPos();
            if (look == null) return;
            renderBox(poseStack, camera, bufferSource, look, look, 0f, 1f, 0f, 0.4f);
            return;
        }
        if (pos1 != null && pos2 == null) {
            BlockPos look = getLookTargetPos();
            if (look == null) {
                renderBox(poseStack, camera, bufferSource, pos1, pos1, 0f, 1f, 0f, 0.4f);
                return;
            }
            BlockPos smooth = SMOOTH.update(look);
            renderBox(poseStack, camera, bufferSource, pos1, smooth, 0f, 1f, 0f, 0.3f);
            return;
        }
        if (pos1 == null) return;
        // 両方確定
        SMOOTH.reset();
        renderBox(poseStack, camera, bufferSource, pos1, pos2, 0f, 1f, 0f, 0.5f);
    }


    /** 視線の先のブロック。 実体は {@code LookTarget.blockPos} (4 実装を 1 本へ)。 */
    private static BlockPos getLookTargetPos() {
        Minecraft mc = Minecraft.getInstance();
        return com.manta.api.hud.LookTarget.blockPos(mc.player, mc.level, MAX_LOOK_DISTANCE);
    }

    /** 2 点が張る箱を camera 相対で描く。 実体は {@code WorldOutline.blockBox} (5 実装を 1 本へ)。 */
    private static void renderBox(PoseStack poseStack, Vec3 camera, MultiBufferSource bufferSource,
                                  BlockPos p1, BlockPos p2, float r, float g, float b, float a) {
        com.manta.api.render.WorldOutline.blockBox(poseStack, bufferSource, p1, p2, camera, r, g, b, a, false);
    }
}
