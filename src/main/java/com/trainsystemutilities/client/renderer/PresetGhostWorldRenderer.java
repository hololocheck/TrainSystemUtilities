package com.trainsystemutilities.client.renderer;

import com.simibubi.create.content.trains.track.ITrackBlock;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.TrainPresetClientCache;
import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.network.TrainPresetDataRequestPayload;
import com.trainsystemutilities.preset.PresetPlacer;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Place モードのワールドプレビュー描画。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class PresetGhostWorldRenderer {

    private static String lastRequestedKey = null;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        ItemStack stack = findHeldTool(mc);
        if (stack.isEmpty()) return;
        if (TrainPresetToolItem.getToolMode(stack) != TrainPresetToolItem.TOOL_MODE_PLACE) return;

        String presetKey = stack.get(ModDataComponents.SELECTED_PRESET.get());
        if (presetKey == null || presetKey.isEmpty()) return;

        TrainPreset preset = TrainPresetClientCache.getPresetData(presetKey);
        if (preset == null) {
            if (!presetKey.equals(lastRequestedKey)) {
                lastRequestedKey = presetKey;
                int sep = presetKey.indexOf('/');
                if (sep > 0) {
                    String authorDir = presetKey.substring(0, sep);
                    String fileName = presetKey.substring(sep + 1);
                    PacketDistributor.sendToServer(new TrainPresetDataRequestPayload(authorDir, fileName));
                }
            }
            return;
        }
        lastRequestedKey = presetKey;

        BlockPos markerPos = TrainPresetToolItem.getPlaceOrigin(stack);
        int manualRotY = TrainPresetToolItem.getPlaceRotY(stack);
        int storedAutoRotY = TrainPresetToolItem.getPlaceAutoRotY(stack);
        int sub = TrainPresetToolItem.getPlaceSubMode(stack);

        if (markerPos != null) {
            var markerDirection = TrainPresetToolItem.getPlaceMarkerDirection(stack);
            int autoRotY = storedAutoRotY >= 0
                    ? storedAutoRotY
                    : PresetPlacer.resolveAutoRotY(mc.level, markerPos, preset, mc.player != null ? mc.player.getLookAngle() : null);
            int effectiveRotY = Math.floorMod(autoRotY + manualRotY, 4);
            BlockPos placementOrigin = PresetPlacer.resolvePlacementOrigin(mc.level, markerPos, preset, effectiveRotY);
            Vec3 markerVec = markerDirection != null
                    ? PresetPlacer.resolveDisplayMarkerVec(mc.level, markerPos, markerDirection)
                    : PresetPlacer.resolveDisplayMarkerVec(mc.level, markerPos, preset, effectiveRotY);
            renderPreview(event, mc, markerPos, markerDirection, markerVec, placementOrigin, preset, effectiveRotY, sub, false);
        } else if (sub == TrainPresetToolItem.SUB_ORIGIN) {
            BlockPos look = lookTarget(mc);
            if (look != null) {
                var markerDirection = PresetPlacer.resolveMarkerDirection(mc.level, look, mc.player != null ? mc.player.getLookAngle() : null);
                int autoRotY = PresetPlacer.resolveAutoRotY(mc.level, look, preset, mc.player != null ? mc.player.getLookAngle() : null);
                int effectiveRotY = Math.floorMod(autoRotY + manualRotY, 4);
                BlockPos placementOrigin = PresetPlacer.resolvePlacementOrigin(mc.level, look, preset, effectiveRotY);
                Vec3 markerVec = markerDirection != null
                        ? PresetPlacer.resolveDisplayMarkerVec(mc.level, look, markerDirection)
                        : PresetPlacer.resolveDisplayMarkerVec(mc.level, look, preset, effectiveRotY);
                renderPreview(event, mc, look, markerDirection, markerVec, placementOrigin, preset, effectiveRotY, sub, true);
            }
        }
    }

    private static BlockPos lookTarget(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = eye.add(look.scale(64.0));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static void renderPreview(RenderLevelStageEvent event, Minecraft mc,
                                      BlockPos markerPos, Direction markerDirection, Vec3 markerVec,
                                      BlockPos placementOrigin, TrainPreset preset,
                                      int rotY, int sub, boolean ghost) {
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack pose = event.getPoseStack();

        int r = ((rotY % 4) + 4) % 4;
        int sx = (r == 1 || r == 3) ? preset.sizeZ : preset.sizeX;
        int sz = (r == 1 || r == 3) ? preset.sizeX : preset.sizeZ;
        int sy = preset.sizeY;

        BlockPos anchorRot = rotateRel(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ,
                preset.sizeX, preset.sizeZ, rotY);

        double minX = placementOrigin.getX() - anchorRot.getX();
        double minY = placementOrigin.getY();
        double minZ = placementOrigin.getZ() - anchorRot.getZ();
        double maxX = minX + sx;
        double maxY = minY + (sy - preset.anchorRelY);
        double maxZ = minZ + sz;

        float r1;
        float g1;
        float b1;
        if (sub == TrainPresetToolItem.SUB_PLACE) {
            r1 = 0.4f;
            g1 = 1.0f;
            b1 = 0.4f;
        } else {
            r1 = 0.31f;
            g1 = 0.76f;
            b1 = 0.97f;
        }
        float boxAlpha = ghost ? 0.4f : 0.85f;
        float markerAlpha = ghost ? 0.5f : 1.0f;

        AABB aabb = new AABB(
                minX - cam.x, minY - cam.y, minZ - cam.z,
                maxX - cam.x, maxY - cam.y, maxZ - cam.z);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, consumer, aabb, r1, g1, b1, boxAlpha);

        boolean renderedOverlay = renderAssemblyMarker(mc, pose, bufferSource, cam, markerPos, markerDirection, ghost);
        if (!renderedOverlay && markerVec != null) {
            double half = 0.25;
            AABB markerBox = new AABB(
                    markerVec.x - half - cam.x, markerVec.y - 0.0625 - cam.y, markerVec.z - half - cam.z,
                    markerVec.x + half - cam.x, markerVec.y + 0.5625 - cam.y, markerVec.z + half - cam.z);
            LevelRenderer.renderLineBox(pose, consumer, markerBox, 1.0f, 0.85f, 0.1f, markerAlpha);
        }

        bufferSource.endBatch();
    }

    private static boolean renderAssemblyMarker(Minecraft mc, PoseStack pose, MultiBufferSource.BufferSource bufferSource,
                                                Vec3 cam, BlockPos markerPos, Direction markerDirection, boolean ghost) {
        if (mc.level == null || markerPos == null || markerDirection == null) return false;

        BlockState trackState = mc.level.getBlockState(markerPos);
        if (!(trackState.getBlock() instanceof ITrackBlock track)) return false;

        pose.pushPose();
        pose.translate(markerPos.getX() - cam.x, markerPos.getY() - cam.y, markerPos.getZ() - cam.z);

        PartialModel assemblyOverlay = track.prepareAssemblyOverlay(mc.level, markerPos, trackState, markerDirection, pose);
        if (assemblyOverlay == null) {
            pose.popPose();
            return false;
        }

        int color = ghost ? 0xD9C15A : 0xFFC73A;
        int lightColor = LevelRenderer.getLightColor(mc.level, markerPos);
        SuperByteBuffer sbb = CachedBuffers.partial(assemblyOverlay, trackState);
        sbb.color(color);
        sbb.light(lightColor);
        sbb.renderInto(pose, bufferSource.getBuffer(RenderType.cutoutMipped()));
        pose.popPose();
        return true;
    }

    private static BlockPos rotateRel(int relX, int relY, int relZ, int sizeX, int sizeZ, int rotY) {
        int r = ((rotY % 4) + 4) % 4;
        return switch (r) {
            case 1 -> new BlockPos(sizeZ - 1 - relZ, relY, relX);
            case 2 -> new BlockPos(sizeX - 1 - relX, relY, sizeZ - 1 - relZ);
            case 3 -> new BlockPos(relZ, relY, sizeX - 1 - relX);
            default -> new BlockPos(relX, relY, relZ);
        };
    }

    private static ItemStack findHeldTool(Minecraft mc) {
        return belugalab.tsu.api.HeldTools.find(mc.player, ModItems.TRAIN_PRESET_TOOL.get());
    }
}
