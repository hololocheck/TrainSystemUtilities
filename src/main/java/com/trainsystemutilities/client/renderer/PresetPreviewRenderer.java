package com.trainsystemutilities.client.renderer;

import com.trainsystemutilities.preset.TrainPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TrainPreset を 3D ブロックモデルとして GUI 内の指定矩形にレンダリングする。
 * ManagementComputerScreen.render3DTrainModel と同じパターンを汎用化したもの。
 *
 * 使い方:
 *   PresetPreviewRenderer.render(g, preset, x, y, w, h, rotY, rotX, zoom);
 *
 * - rotY / rotX: 度単位
 * - zoom: 1.0 = フィット
 * - x/y/w/h: 描画矩形 (scissor 範囲)
 */
public final class PresetPreviewRenderer {

    private PresetPreviewRenderer() {}

    public static void render(GuiGraphics g, TrainPreset preset,
                                int x, int y, int w, int h,
                                float rotY, float rotX, float zoom) {
        render(g, preset, x, y, w, h, rotY, rotX, zoom, 0f, 0f);
    }

    public static void render(GuiGraphics g, TrainPreset preset,
                                int x, int y, int w, int h,
                                float rotY, float rotX, float zoom,
                                float panX, float panY) {
        if (preset == null || preset.blocks.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        try {
            HolderLookup.Provider registries = mc.level.registryAccess();
            var blockLookup = registries.lookupOrThrow(Registries.BLOCK);

            // パレット解決とブロック範囲計算
            BlockState[] palette = new BlockState[preset.palette.size()];
            for (int i = 0; i < preset.palette.size(); i++) {
                try {
                    palette[i] = NbtUtils.readBlockState(blockLookup, preset.palette.get(i));
                } catch (Throwable t) {
                    palette[i] = null;
                }
            }

            float minX = preset.sizeX, minY = preset.sizeY, minZ = preset.sizeZ;
            float maxX = 0, maxY = 0, maxZ = 0;
            boolean any = false;
            for (TrainPreset.Entry entry : preset.blocks) {
                int idx = entry.paletteIdx();
                if (idx < 0 || idx >= palette.length || palette[idx] == null
                        || palette[idx].isAir()) continue;
                var p = entry.relPos();
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX() + 1); maxY = Math.max(maxY, p.getY() + 1); maxZ = Math.max(maxZ, p.getZ() + 1);
                any = true;
            }
            if (!any) return;
            float cx = (minX + maxX) / 2f;
            float cy = (minY + maxY) / 2f;
            float cz = (minZ + maxZ) / 2f;
            float range = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            if (range <= 0) range = 1;

            int centerX = x + w / 2;
            int centerY = y + h / 2;

            // viewport の 80% に収まるフィット (回転で投影が少し膨らむ余裕を残す)
            float baseScale = Math.min(w * 0.80f, h * 0.80f) / range;
            float scale = baseScale * zoom;

            g.enableScissor(x, y, x + w, y + h);
            com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();

            g.pose().pushPose();
            g.pose().translate(centerX + panX, centerY + panY, 500);
            g.pose().scale(scale, -scale, scale);

            org.joml.Quaternionf rot = new org.joml.Quaternionf();
            rot.rotateX((float) Math.toRadians(rotX));
            rot.rotateY((float) Math.toRadians(rotY));
            g.pose().mulPose(rot);

            g.pose().translate(-cx, -cy, -cz);

            var bufferSource = g.bufferSource();
            int light = 0xF000F0;
            int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;

            for (TrainPreset.Entry entry : preset.blocks) {
                int idx = entry.paletteIdx();
                if (idx < 0 || idx >= palette.length || palette[idx] == null) continue;
                BlockState state = palette[idx];
                if (state.isAir()) continue;
                var p = entry.relPos();
                g.pose().pushPose();
                g.pose().translate(p.getX(), p.getY(), p.getZ());
                // Create コピーパネル等の BlockEntity-driven な見た目を再現するため
                // entry.beNbt() を渡して PreviewBlockRenderer 経由で描画 (ModelData 解決)。
                PreviewBlockRenderer.renderBlock(state, entry.beNbt(), p,
                        g.pose(), bufferSource, light, overlay);
                g.pose().popPose();
            }

            bufferSource.endBatch();
            g.pose().popPose();

            com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
            g.disableScissor();
        } catch (Throwable ignored) {
            try { g.disableScissor(); } catch (Throwable ignored2) {}
        }
    }
}
