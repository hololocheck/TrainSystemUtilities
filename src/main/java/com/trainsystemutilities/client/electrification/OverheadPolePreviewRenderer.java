package com.trainsystemutilities.client.electrification;

import belugalab.mcss3.preview.WorldGhostBlockRenderer;
import belugalab.mcss3.preview.WorldGhostBlockRenderer.GhostItem;
import belugalab.mcss3.preview.WorldGhostBlockRenderer.GhostOutline;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceExecutor;
import com.trainsystemutilities.electrification.autoplace.AutoPlacePathFinder;
import com.trainsystemutilities.electrification.item.OverheadPoleAutoToolItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 架線柱配置補助ツールの半透明 hover プレビュー (= Create 設計図風)。
 *
 * <p>MCSS の {@link WorldGhostBlockRenderer} に描画を delegate し、
 * TSU 側は portal 計算ロジックのみ (= AutoPlaceExecutor + axis 揃え + multiTrack 尊重) を担当。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class OverheadPolePreviewRenderer {

    private OverheadPolePreviewRenderer() {}

    private static final float GHOST_ALPHA = 0.55f;

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        LocalPlayer player = mc.player;

        ItemStack stack = findHeldTool(player);
        if (stack.isEmpty()) return;
        if (AutoPlaceConfig.getToolMode(stack) != AutoPlaceConfig.TOOL_MODE_SELECTION) return;
        if (AutoPlaceConfig.getSubMode(stack) != AutoPlaceConfig.SUB_MODE_PLACE) return;

        List<AutoPlaceExecutor.PreviewItem> items;
        BlockPos trackPosForHighlight;
        try {
            var curveHit = com.trainsystemutilities.electrification.autoplace.TrackCurveRaycast
                    .raycast(mc.level, player);
            if (curveHit != null) {
                int scanRange = AutoPlaceConfig.getScanRange(stack);
                int multiTrack = AutoPlaceConfig.getMultiTrackCount(stack);
                net.minecraft.world.phys.Vec3 axis = curveHit.axis();
                net.minecraft.world.phys.Vec3 look = player.getLookAngle();
                if (look.x * axis.x + look.z * axis.z < 0) {
                    axis = new net.minecraft.world.phys.Vec3(-axis.x, axis.y, -axis.z);
                }
                List<net.minecraft.world.phys.Vec3> parallel = null;
                if (multiTrack >= 2) {
                    parallel = com.trainsystemutilities.electrification.autoplace.TrackCurveRaycast
                            .findParallelCurveCenters(mc.level, curveHit.worldPoint(), axis, scanRange);
                    if (parallel.size() < 2) parallel = null;
                }
                items = AutoPlaceExecutor.computePreviewAt(
                        mc.level, curveHit.worldPoint(), axis, stack, parallel);
                trackPosForHighlight = BlockPos.containing(curveHit.worldPoint());
            } else {
                BlockPos trackPos = OverheadPoleAutoToolItem.rayTraceBlock(mc.level, player);
                if (trackPos == null) return;
                if (!AutoPlacePathFinder.isTrack(mc.level, trackPos)) return;
                items = AutoPlaceExecutor.computePreview(mc.level, trackPos, stack);
                trackPosForHighlight = trackPos;
            }
        } catch (Throwable t) {
            return;
        }
        if (items.isEmpty()) return;

        // PreviewItem → MCSS GhostItem 変換
        List<GhostItem> ghostItems = new ArrayList<>(items.size());
        for (AutoPlaceExecutor.PreviewItem item : items) {
            ghostItems.add(new GhostItem(item.pos(), item.state(), item.yawDegrees()));
        }

        // 起点線路 highlight box (= 緑)
        AABB highlight = new AABB(
                trackPosForHighlight.getX(), trackPosForHighlight.getY(), trackPosForHighlight.getZ(),
                trackPosForHighlight.getX() + 1, trackPosForHighlight.getY() + 1, trackPosForHighlight.getZ() + 1);

        // 全体 range box (= シアン)
        AABB bounds = WorldGhostBlockRenderer.computeBounds(ghostItems);

        WorldGhostBlockRenderer.render(
                event.getPoseStack(), event.getCamera(), ghostItems, GHOST_ALPHA,
                bounds, GhostOutline.cyan(),
                highlight, GhostOutline.green(),
                OverheadPolePreviewRenderer::applyYaw);
    }

    /** BE への yaw setter (= MCSS API への adapter)。 */
    private static void applyYaw(net.minecraft.world.level.block.entity.BlockEntity be, float yawDeg) {
        if (be instanceof com.trainsystemutilities.electrification.blockentity.OverheadPoleBlockEntity p) {
            p.setYawDegrees(yawDeg);
        } else if (be instanceof com.trainsystemutilities.electrification.blockentity.OverheadTrussBlockEntity t) {
            t.setYawDegrees(yawDeg);
        }
    }

    private static ItemStack findHeldTool(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.OVERHEAD_POLE_AUTO_TOOL.get())) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(ModItems.OVERHEAD_POLE_AUTO_TOOL.get())) return off;
        return ItemStack.EMPTY;
    }
}
