package com.trainsystemutilities.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.TrainPresetToolItem;
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
 * 列車プリセットツールの選択範囲をワールド空間にワイヤーフレームで描画する。
 * Selection モード時のみ表示。
 *
 * - Pos1 のみ設定: Pos2 をプレイヤー視線先に追従 (なめらか補間)
 * - Pos1 / Pos2 両方設定: 確定範囲を太めに表示
 * - 編集モード (RANGE_EDIT_MODE = 1 or 2): 該当 pos を視線先に追従
 *
 * 色: シアン (Selection)、オレンジ (Place モードでマーカー位置プレビュー時、将来 Phase 4)。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class TrainPresetRangeRenderer {

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

        if (mainHand.is(ModItems.TRAIN_PRESET_TOOL.get())) {
            renderToolOutline(mainHand, event.getPoseStack(), camera, bufferSource);
        }
        if (offHand.is(ModItems.TRAIN_PRESET_TOOL.get())) {
            renderToolOutline(offHand, event.getPoseStack(), camera, bufferSource);
        }

        bufferSource.endBatch();
    }

    private static void renderToolOutline(ItemStack stack, PoseStack poseStack, Vec3 camera,
                                            MultiBufferSource bufferSource) {
        if (stack.isEmpty()) return;
        // Selection モード以外では描画しない
        if (TrainPresetToolItem.getToolMode(stack) != TrainPresetToolItem.TOOL_MODE_SELECTION) {
            SMOOTH.reset();
            return;
        }

        BlockPos pos1 = TrainPresetToolItem.getPos1(stack);
        BlockPos pos2 = TrainPresetToolItem.getPos2(stack);
        int editMode = TrainPresetToolItem.getEditMode(stack);

        // 編集モード: 選択中 pos を視線先に追従
        if (editMode == 1) {
            BlockPos look = getLookTargetPos();
            if (look == null) return;
            if (pos2 != null) {
                BlockPos smooth = SMOOTH.update(look);
                renderBox(poseStack, camera, bufferSource, smooth, pos2, 0f, 1f, 1f, 0.3f);
            } else {
                SMOOTH.reset();
                renderBox(poseStack, camera, bufferSource, look, look, 0f, 1f, 1f, 0.4f);
            }
            return;
        }
        if (editMode == 2) {
            BlockPos look = getLookTargetPos();
            if (look == null) return;
            if (pos1 != null) {
                BlockPos smooth = SMOOTH.update(look);
                renderBox(poseStack, camera, bufferSource, pos1, smooth, 0f, 1f, 1f, 0.3f);
            } else {
                SMOOTH.reset();
                renderBox(poseStack, camera, bufferSource, look, look, 0f, 1f, 1f, 0.4f);
            }
            return;
        }

        // 通常モード
        if (pos1 == null && pos2 == null) {
            SMOOTH.reset();
            BlockPos look = getLookTargetPos();
            if (look == null) return;
            renderBox(poseStack, camera, bufferSource, look, look, 0f, 1f, 1f, 0.4f);
            return;
        }
        if (pos1 != null && pos2 == null) {
            BlockPos look = getLookTargetPos();
            if (look == null) {
                renderBox(poseStack, camera, bufferSource, pos1, pos1, 0f, 1f, 1f, 0.4f);
                return;
            }
            BlockPos smooth = SMOOTH.update(look);
            renderBox(poseStack, camera, bufferSource, pos1, smooth, 0f, 1f, 1f, 0.3f);
            return;
        }
        if (pos1 == null) return;
        // 両方確定
        SMOOTH.reset();
        renderBox(poseStack, camera, bufferSource, pos1, pos2, 0f, 1f, 1f, 0.5f);
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
