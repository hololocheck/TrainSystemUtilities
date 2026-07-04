package com.trainsystemutilities.station;

import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 範囲内に存在する Create 駅ブロックを列挙する。
 *
 * <p>Server-side のみ。BlockPos AABB を一括スキャンし、Create の
 * {@link StationBlockEntity} の GlobalStation UUID と座標ペアを返す。
 */
public final class StationGroupScanner {

    private StationGroupScanner() {}

    /**
     * @param facing 駅ブロックの HORIZONTAL_FACING (= 列車進行方向)。
     *               Direction が取れなければ {@link Direction#NORTH} を fallback。
     */
    public record DetectedStation(UUID id, BlockPos pos, Direction facing) {}

    /**
     * AABB (両端含む) 内の Create 駅ブロックを全て返す。
     * GlobalStation 未登録 (= まだ track graph 構築中) のものはスキップ。
     *
     * <p>大きすぎる範囲 (例: 100×100×100 = 1M ブロック) では BE 走査に時間がかかるため、
     * BlockEntity のリストを直接イテレートせず、各 chunk の BE map を経由する方が速い。
     * MVP では純粋に BlockPos ループ + getBlockEntity を使う (実用範囲では十分)。
     */
    public static List<DetectedStation> scan(ServerLevel level, BlockPos minP, BlockPos maxP) {
        List<DetectedStation> out = new ArrayList<>();
        // SECURITY / DoS: 範囲の上限を設けて巨大な scan を防ぐ
        long volume = ((long) maxP.getX() - minP.getX() + 1)
                    * ((long) maxP.getY() - minP.getY() + 1)
                    * ((long) maxP.getZ() - minP.getZ() + 1);
        if (volume <= 0 || volume > 1_000_000L) return out;

        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = minP.getX(); x <= maxP.getX(); x++) {
            for (int z = minP.getZ(); z <= maxP.getZ(); z++) {
                for (int y = minP.getY(); y <= maxP.getY(); y++) {
                    cur.set(x, y, z);
                    var be = level.getBlockEntity(cur);
                    if (be instanceof StationBlockEntity sbe) {
                        var globalStation = sbe.getStation();
                        if (globalStation == null) continue;
                        Direction facing;
                        try {
                            var bs = level.getBlockState(cur);
                            facing = bs.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                                    ? bs.getValue(BlockStateProperties.HORIZONTAL_FACING)
                                    : Direction.NORTH;
                        } catch (Exception e) {
                            facing = Direction.NORTH;
                        }
                        out.add(new DetectedStation(globalStation.getId(), cur.immutable(), facing));
                    }
                }
            }
        }
        return out;
    }
}
