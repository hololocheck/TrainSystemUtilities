package com.trainsystemutilities.client.electrification;

import belugalab.mcss3.preview.GuiBlock3DRenderer;
import belugalab.mcss3.preview.GuiBlock3DRenderer.Block3DEntry;
import com.trainsystemutilities.electrification.block.InsulatorBlock;
import com.trainsystemutilities.electrification.block.OverheadPoleBlock;
import com.trainsystemutilities.electrification.block.OverheadTrussBlock;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 架線柱配置補助ツールの GUI 内 3D プレビュー。
 *
 * <p>MCSS の {@link GuiBlock3DRenderer} に描画を delegate、 TSU 側は portal を構成する
 * BlockState list を組み立てるロジックのみを担当。
 */
public final class OverheadPortalPreviewRenderer {

    private OverheadPortalPreviewRenderer() {}

    private static Block cachedTrackBlock;

    private static Block trackBlock() {
        if (cachedTrackBlock != null) return cachedTrackBlock;
        Block b = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("create", "track"));
        if (b != null && b != net.minecraft.world.level.block.Blocks.AIR) {
            cachedTrackBlock = b;
        } else {
            cachedTrackBlock = net.minecraft.world.level.block.Blocks.RAIL;
        }
        return cachedTrackBlock;
    }

    public static void render(GuiGraphics g, int x, int y, int w, int h,
                                int height, int clearance, int multiTrack,
                                boolean cantilever, boolean placeTruss, boolean placeIns,
                                float rotY, float rotX, float zoom, float panX, float panY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // === portal を構成する Block3DEntry list を生成 ===
        List<Block3DEntry> entries = new ArrayList<>();
        Block pole = ModBlocks.OVERHEAD_POLE.get();
        Block truss = ModBlocks.OVERHEAD_TRUSS.get();
        Block ins = ModBlocks.INSULATOR.get();
        Block track = trackBlock();

        int trackGap = 4;
        int totalGap = (multiTrack - 1) * trackGap;
        int leftPoleOffset = (int) (1.5 + clearance) + totalGap / 2;
        int rightPoleOffset = (int) (1.5 + clearance) + totalGap / 2;
        int polesPerStack = height + 1;
        int trackLen = 6;

        BlockState trackState = track.defaultBlockState();
        for (int i = 0; i < multiTrack; i++) {
            int trackX = -totalGap / 2 + i * trackGap;
            for (int dz = -trackLen; dz <= trackLen; dz++) {
                entries.add(new Block3DEntry(new BlockPos(trackX, 0, dz), trackState));
            }
        }

        int leftX = -leftPoleOffset;
        int rightX = rightPoleOffset;
        BlockState poleState = pole.defaultBlockState()
                .setValue(OverheadPoleBlock.ANGLE_8, 0);
        if (!cantilever) {
            for (int yy = 0; yy < polesPerStack; yy++) {
                entries.add(new Block3DEntry(new BlockPos(leftX, yy, 0), poleState));
            }
        }
        for (int yy = 0; yy < polesPerStack; yy++) {
            entries.add(new Block3DEntry(new BlockPos(rightX, yy, 0), poleState));
        }

        if (placeTruss) {
            int trussY = height - 1;
            int trussSteps = Math.max(Math.abs(rightX - leftX), 1);
            for (int s = 1; s < trussSteps; s++) {
                int trussX = leftX + s;
                boolean isEndStart = (s == 1);
                boolean isEndLast = (s == trussSteps - 1);
                int useAngle = isEndLast ? 4 : 0;
                boolean isCorner = isEndLast || (isEndStart && !cantilever);
                BlockState trussState = truss.defaultBlockState()
                        .setValue(OverheadTrussBlock.ANGLE_8, useAngle)
                        .setValue(OverheadTrussBlock.CORNER, isCorner);
                entries.add(new Block3DEntry(new BlockPos(trussX, trussY, 0), trussState));
            }
        }

        if (placeIns) {
            int insY = height - 2;
            if (insY >= 1) {
                BlockState insState = ins.defaultBlockState()
                        .setValue(InsulatorBlock.FACING, Direction.DOWN);
                for (int i = 0; i < multiTrack; i++) {
                    int trackX = -totalGap / 2 + i * trackGap;
                    entries.add(new Block3DEntry(new BlockPos(trackX, insY, 0), insState));
                }
            }
        }

        // MCSS API へ delegate (= fit camera + Lighting + scissor + BER smart 描画)
        GuiBlock3DRenderer.render(g, x, y, w, h, entries, rotY, rotX, zoom, panX, panY);
    }
}
