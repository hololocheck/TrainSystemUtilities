package com.trainsystemutilities.electrification.wire;

import net.minecraft.core.BlockPos;

/**
 * 2 つの碍子位置の正規化ペア (= 接続の dedupe キー)。
 *
 * <p>{@code of(a, b)} は a/b のクリック順に依存せず、常に同じ {@code NodePair} を返す
 * (= packed BlockPos の小さい方を a に置く)。
 */
public record NodePair(BlockPos a, BlockPos b) {
    public static NodePair of(BlockPos x, BlockPos y) {
        return Long.compare(x.asLong(), y.asLong()) <= 0
                ? new NodePair(x, y)
                : new NodePair(y, x);
    }
}
