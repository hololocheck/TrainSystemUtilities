package com.trainsystemutilities.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.client.gui.RangeFrameToggleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * アナウンス設定 GUI から「範囲枠表示」を ON にした鉄道管理 BE 群に対して、
 * その range board の範囲枠 (cyan line box) を世界にオーバーレイ描画する。
 * SAS の {@code RangeRenderer} と同じ視覚スタイル。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class RmbeRangeFrameRenderer {

    private RmbeRangeFrameRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var visible = RangeFrameToggleState.snapshot();
        if (visible.isEmpty()) return;
        // SAS が未導入なら range board 関連 API を呼べないため早期 return。
        if (!com.trainsystemutilities.compat.sas.SasIntegration.isLoaded()) return;

        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());

        for (BlockPos rmbePos : visible) {
            var be = mc.level.getBlockEntity(rmbePos);
            if (!(be instanceof RailwayManagementBlockEntity rmbe)) continue;
            ItemStack range = rmbe.getRangeBoard();
            // 共有先になっていて自前の range board が空なら、共有元の range board を借りる
            if (range.isEmpty()) {
                var src = rmbe.findRangeShareSource();
                if (src != null) range = src.getRangeBoard();
            }
            if (range.isEmpty()) continue;
            BlockPos p1 = belugalab.sas.api.SasApi.getRangePos1(range);
            BlockPos p2 = belugalab.sas.api.SasApi.getRangePos2(range);
            if (p1 == null || p2 == null) continue;
            double minX = Math.min(p1.getX(), p2.getX());
            double minY = Math.min(p1.getY(), p2.getY());
            double minZ = Math.min(p1.getZ(), p2.getZ());
            double maxX = Math.max(p1.getX(), p2.getX()) + 1;
            double maxY = Math.max(p1.getY(), p2.getY()) + 1;
            double maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;
            AABB aabb = new AABB(
                    minX - camera.x, minY - camera.y, minZ - camera.z,
                    maxX - camera.x, maxY - camera.y, maxZ - camera.z);
            LevelRenderer.renderLineBox(pose, consumer, aabb, 0.0f, 1.0f, 1.0f, 0.6f);

            // 減衰モード ON のときは attenuation box (orange) も追加描画。
            // 状態は AnnouncementConfig (server-synced) から取得 (default ON)。
            var cfg = com.trainsystemutilities.client.gui.RailwayAnnouncementClientState
                    .getConfig(rmbePos);
            boolean attOn = cfg == null || cfg.isAttenuationMode();
            if (attOn) {
                int[] ranges = belugalab.sas.api.SasApi.getAttenuationRanges(range);
                if (ranges != null && ranges.length >= 6) {
                    boolean hasDiff = ranges[0] > 0 || ranges[1] > 0 || ranges[2] > 0
                            || ranges[3] > 0 || ranges[4] > 0 || ranges[5] > 0;
                    if (hasDiff) {
                        double aMinX = minX - ranges[1];
                        double aMaxX = maxX + ranges[0];
                        double aMinY = minY - ranges[3];
                        double aMaxY = maxY + ranges[2];
                        double aMinZ = minZ - ranges[5];
                        double aMaxZ = maxZ + ranges[4];
                        AABB attAabb = new AABB(
                                aMinX - camera.x, aMinY - camera.y, aMinZ - camera.z,
                                aMaxX - camera.x, aMaxY - camera.y, aMaxZ - camera.z);
                        LevelRenderer.renderLineBox(pose, consumer, attAabb,
                                1.0f, 0.55f, 0.0f, 0.5f);
                    }
                }
            }
        }
        bufferSource.endBatch();
    }
}
