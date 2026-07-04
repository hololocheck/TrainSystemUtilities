package com.trainsystemutilities.electrification.block;

import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 変電所キュービクル (3×4×2 マルチブロック = 24 ブロック) の幾何ヘルパ。
 *
 * <p><b>レイアウト</b> (FACING=NORTH, 扉が北を向く想定):
 * <ul>
 *   <li>幅 4 ブロック (= width axis, EAST 方向に展開): コアブロックは西端から 2 番目 (= offset 1)</li>
 *   <li>高さ 3 ブロック (= UP 方向): コアブロックは最下段</li>
 *   <li>奥行 2 ブロック (= depth axis, SOUTH 方向に展開): コアブロックは前端 (= FACING 側)</li>
 * </ul>
 *
 * <p>他の FACING (EAST/SOUTH/WEST) では widthDir/depthDir が回転する。
 *
 * <p>BlockBench モデル原点 (0,0,0) はキュービクル前面中央下端に対応する想定。
 * Renderer 側で FACING に応じたオフセット (X/Z ±0.5) で正しい位置に揃える。
 */
public final class SubstationMultiblock {

    /** 幅方向のブロック数 (= 扉が並ぶ正面の長さ)。 */
    public static final int WIDTH = 4;
    /** 高さ方向のブロック数。 */
    public static final int HEIGHT = 3;
    /** 奥行方向のブロック数 (= 正面から背面までの厚み)。 */
    public static final int DEPTH = 2;
    /** マルチブロック内のコアブロックの幅方向オフセット (= 西端から何ブロック目か、0-indexed)。
     *  4 ブロック幅なので 0..3 のいずれか。1 = 西端から 2 番目 (= 中央寄り)。 */
    public static final int CORE_WIDTH_INDEX = 1;
    public static final int TOTAL_BLOCKS = WIDTH * HEIGHT * DEPTH; // 24

    private SubstationMultiblock() {}

    /** 指定 FACING のときの「幅方向 (= 4 ブロック並ぶ方向)」。FACING の時計回り 90° 隣。
     *  FACING=NORTH → widthDir=EAST */
    public static Direction widthDir(Direction facing) {
        return facing.getClockWise();
    }

    /** 指定 FACING のときの「奥行方向 (= 2 ブロック並ぶ方向、正面から背面へ)」。FACING の反対側。
     *  FACING=NORTH → depthDir=SOUTH */
    public static Direction depthDir(Direction facing) {
        return facing.getOpposite();
    }

    /** コアブロック位置 + FACING から、マルチブロック全 24 マスの座標リストを返す。
     *  コアブロック自身も含む。 */
    public static List<BlockPos> getAllPositions(BlockPos corePos, Direction facing) {
        Direction wd = widthDir(facing);
        Direction dd = depthDir(facing);
        List<BlockPos> positions = new ArrayList<>(TOTAL_BLOCKS);
        for (int i = 0; i < WIDTH; i++) {
            int widthOffset = i - CORE_WIDTH_INDEX;  // -1, 0, 1, 2 (for i=0..3)
            for (int j = 0; j < HEIGHT; j++) {
                for (int k = 0; k < DEPTH; k++) {
                    BlockPos p = corePos
                            .relative(wd, widthOffset)
                            .relative(Direction.UP, j)
                            .relative(dd, k);
                    positions.add(p);
                }
            }
        }
        return positions;
    }

    /** コアブロックを除いた 23 マスの座標リスト。 */
    public static List<BlockPos> getDummyPositions(BlockPos corePos, Direction facing) {
        List<BlockPos> all = getAllPositions(corePos, facing);
        all.remove(corePos);
        return all;
    }

    /** ダミーブロック位置からコアブロック位置を逆引き。
     *  ダミー周辺の SubstationBlock を探索し、その FACING でダミーセットを再計算して
     *  本ダミーが含まれていれば対応するコアと判定。 */
    public static BlockPos findCore(Level level, BlockPos dummyPos) {
        // コアはダミーから最大で widthDir 方向 ±2、UP 方向 -2、depthDir 方向 -1 の範囲内
        // 検索範囲を ±3 で広めに取る
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 0; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = dummyPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(candidate);
                    if (!(state.getBlock() instanceof SubstationBlock)) continue;
                    Direction facing = state.getValue(SubstationBlock.FACING);
                    if (getAllPositions(candidate, facing).contains(dummyPos)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** 指定位置にコア + ダミー 23 マスを配置できるか (= 全マスが置換可能か)。 */
    public static boolean canPlace(Level level, BlockPos corePos, Direction facing) {
        for (BlockPos p : getAllPositions(corePos, facing)) {
            BlockState s = level.getBlockState(p);
            if (!s.canBeReplaced()) return false;
        }
        return true;
    }

    /** ダミーブロックを 23 マスに配置 (コアブロック自身はすでに置かれている前提)。 */
    public static void placeDummies(Level level, BlockPos corePos, Direction facing) {
        BlockState dummyState = ModBlocks.SUBSTATION_DUMMY.get().defaultBlockState();
        for (BlockPos p : getDummyPositions(corePos, facing)) {
            level.setBlock(p, dummyState, net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }
}
