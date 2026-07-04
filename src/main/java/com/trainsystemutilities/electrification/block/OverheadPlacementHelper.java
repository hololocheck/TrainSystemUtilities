package com.trainsystemutilities.electrification.block;

import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 架線柱 / トラスの 8 方向配置を決定する共用 utility。
 *
 * <p>探索優先順位:
 * <ol>
 *   <li>P4: 配置位置の周辺 (= 下 1 + 横 4 + 横下 4 = 9 cell) を scan、 Create
 *       {@link ITrackBlock} が見つかれば その axis から 8 方向角を導出</li>
 *   <li>fallback: プレイヤーの horizontal yaw を 45° 刻みに snap</li>
 * </ol>
 *
 * <p>これにより Create 線路の直線 / カーブいずれでも、 架線柱が線路の角度に追従して
 * 設置される。
 */
public final class OverheadPlacementHelper {

    /** 探索範囲 (= 配置 pos からの相対 offset、 計 9 cell)。 */
    private static final BlockPos[] SCAN_OFFSETS = {
            new BlockPos(0, -1, 0),  // 真下
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),  // 横 (= 同 Y)
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, -1, 0), new BlockPos(-1, -1, 0),  // 横下
            new BlockPos(0, -1, 1), new BlockPos(0, -1, -1)
    };

    private OverheadPlacementHelper() {}

    /**
     * BlockPlaceContext から 8 方向角 (0..7) を解決する。
     *
     * @return 0=N, 1=NE, 2=E, 3=SE, 4=S, 5=SW, 6=W, 7=NW
     */
    public static int resolveAngle8(BlockPlaceContext context) {
        Integer fromTrack = resolveAngleFromNearbyTrack(context.getLevel(), context.getClickedPos(), context.getPlayer());
        if (fromTrack != null) return fromTrack;

        LivingEntity player = context.getPlayer();
        if (player == null) return 0;
        return yawToAngle8(player.getYRot());
    }

    /**
     * 配置位置周辺の Create track を scan し、 見つかれば最も近い track の
     * horizontal axis を 8 方向角に変換して返す。 見つからなければ null。
     *
     * @param level 配置先の Level
     * @param placePos 配置 BlockPos
     * @param lookSource axis 方向選択用 (= 最も近い player look 方向を選ぶ)、 null 許容
     */
    public static Integer resolveAngleFromNearbyTrack(BlockGetter level, BlockPos placePos, LivingEntity lookSource) {
        if (level == null || placePos == null) return null;

        Vec3 lookVec = lookSource == null ? null : lookSource.getLookAngle();

        for (BlockPos offset : SCAN_OFFSETS) {
            BlockPos scanPos = placePos.offset(offset);
            BlockState state = level.getBlockState(scanPos);
            if (!(state.getBlock() instanceof ITrackBlock track)) continue;

            // track の axis 一覧を取得。 horizontal 成分が最大のものを選ぶ。
            List<Vec3> axes;
            try {
                if (level instanceof Level lvl) {
                    axes = track.getTrackAxes(lvl, scanPos, state);
                } else {
                    continue;
                }
            } catch (Throwable t) {
                continue;
            }
            if (axes == null || axes.isEmpty()) continue;

            // lookVec があれば最も近い axis を選ぶ、 無ければ最初の有効 axis
            Vec3 selected = null;
            double bestScore = -1;
            for (Vec3 axis : axes) {
                Vec3 flat = new Vec3(axis.x, 0, axis.z);
                double sqLen = flat.lengthSqr();
                if (sqLen < 1.0E-4) continue;  // vertical only axis は skip
                if (lookVec == null) {
                    if (selected == null) selected = flat.normalize();
                } else {
                    Vec3 flatLook = new Vec3(lookVec.x, 0, lookVec.z);
                    if (flatLook.lengthSqr() < 1.0E-4) {
                        if (selected == null) selected = flat.normalize();
                    } else {
                        flat = flat.normalize();
                        double score = Math.abs(flat.dot(flatLook.normalize()));
                        if (score > bestScore) {
                            bestScore = score;
                            selected = flat;
                        }
                    }
                }
            }

            if (selected != null) {
                return axisToAngle8(selected);
            }
        }
        return null;
    }

    /**
     * horizontal axis (= flat vector) を 8 方向角に変換 (= 45° 刻み)。
     */
    public static int axisToAngle8(Vec3 axis) {
        double rad = Math.atan2(axis.z, axis.x);
        double deg = Math.toDegrees(rad);
        deg = ((deg % 360) + 360) % 360;
        int idx = (int) Math.round(deg / 45.0) % 8;
        return (idx + 2) % 8;
    }

    /**
     * pole ANGLE_8 を snap した horizontal track axis に逆変換。
     */
    public static Vec3 angle8ToHorizontalAxis(int angle8) {
        double rad = Math.toRadians(((angle8 % 8) + 8) % 8 * 45.0);
        return new Vec3(Math.sin(rad), 0, -Math.cos(rad));
    }

    /**
     * 連続 yaw (度) を 8 方向 index に snap (= track 不在時 fallback)。
     */
    public static int yawToAngle8(float yawDegrees) {
        float normalized = ((yawDegrees % 360f) + 360f) % 360f;
        float facing = (normalized + 180f) % 360f;
        int idx = Math.round(facing / 45f) % 8;
        return (idx + 4) % 8;
    }

    /**
     * angle index (0..7) を yaw 度数 (0..360) に変換 (= model rotation 用)。
     */
    public static int angleToYawDegrees(int angle8) {
        return (angle8 * 45) % 360;
    }
}
