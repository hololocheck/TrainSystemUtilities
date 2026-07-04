package com.trainsystemutilities.electrification.autoplace;

import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 線路情報 utility (= 旧自動配置アルゴリズムの残骸、 1 クリック配置 helper 用)。
 *
 * <p>自動配置アルゴリズムは破棄され、 ここには Create {@link ITrackBlock} に関する
 * 静的判定のみ残る。
 */
public final class AutoPlacePathFinder {

    private AutoPlacePathFinder() {}

    /** pos の block が Create の {@link ITrackBlock} か。 */
    public static boolean isTrack(Level level, BlockPos pos) {
        if (level == null || pos == null) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof ITrackBlock;
    }

    /** track の axis 方向 (= horizontal flat vector) を取得。 複数 axis なら最初の horizontal。 */
    public static Vec3 getHorizontalAxis(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return null;
        try {
            java.util.List<Vec3> axes = track.getTrackAxes(level, pos, state);
            if (axes == null || axes.isEmpty()) return null;
            for (Vec3 axis : axes) {
                Vec3 flat = new Vec3(axis.x, 0, axis.z);
                if (flat.lengthSqr() > 1.0E-4) return flat.normalize();
            }
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[AutoPlace] track axis read failed", ignored); }
        return null;
    }

    /** axis (= reference) と pos の track の axis が ~16° 以内なら true。 */
    public static boolean axisMatches(Level level, BlockPos pos, Vec3 referenceAxis) {
        Vec3 thisAxis = getHorizontalAxis(level, pos);
        if (thisAxis == null || referenceAxis == null) return false;
        return Math.abs(thisAxis.dot(referenceAxis)) > 0.96;
    }

    /**
     * trackPos から perpendicular 方向に同 axis の並走 track を scan して最外側を返す。
     * 並走無しなら [trackPos, trackPos]。
     */
    public static BlockPos[] findParallelOuterTracks(Level level, BlockPos trackPos, Vec3 axis,
                                                      int expectedMaxOffset) {
        BlockPos outerNeg = trackPos;
        BlockPos outerPos = trackPos;
        if (axis == null) return new BlockPos[]{outerNeg, outerPos};
        Vec3 perp = new Vec3(-axis.z, 0, axis.x).normalize();
        for (int d = 1; d <= expectedMaxOffset; d++) {
            BlockPos checkPos = new BlockPos(
                    trackPos.getX() + (int) Math.round(perp.x * d),
                    trackPos.getY(),
                    trackPos.getZ() + (int) Math.round(perp.z * d));
            if (isTrack(level, checkPos) && axisMatches(level, checkPos, axis)) outerPos = checkPos;
            BlockPos checkNeg = new BlockPos(
                    trackPos.getX() - (int) Math.round(perp.x * d),
                    trackPos.getY(),
                    trackPos.getZ() - (int) Math.round(perp.z * d));
            if (isTrack(level, checkNeg) && axisMatches(level, checkNeg, axis)) outerNeg = checkNeg;
        }
        return new BlockPos[]{outerNeg, outerPos};
    }
}
