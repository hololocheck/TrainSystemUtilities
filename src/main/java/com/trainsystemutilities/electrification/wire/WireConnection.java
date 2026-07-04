package com.trainsystemutilities.electrification.wire;

import com.trainsystemutilities.electrification.blockentity.InsulatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 1 本の架線接続 (碍子 A - 碍子 B 間)。
 *
 * <p>{@code nodeA} と {@code nodeB} は packed pos の小さい方が必ず {@code nodeA} に来るよう
 * {@link #of} で正規化する。{@code facingA / facingB} は対応する碍子の {@code FACING} を
 * 同時に保存する (= 取付点 Vec3 を BlockState ロードなしで再構築できる)。
 *
 * <p>{@code length} は碍子ポスト先端間の実距離 (ブロック単位)。給電損失や max length チェックに使う。
 *
 * <p>{@code sag} は SIMPLE 線専用の「たるみモード」フラグ。
 *
 * <p>{@code customThickness / customTrolleyOffset / customDropperInterval / customRowCount} は
 * {@link WireType#CUSTOM} 専用のパラメータ。他タイプでは無視される。
 */
public record WireConnection(BlockPos nodeA, BlockPos nodeB,
                              Direction facingA, Direction facingB,
                              double length, WireType type, boolean sag,
                              float customThickness, float customTrolleyOffset,
                              float customDropperInterval, int customRowCount) {

    /** カスタムパラメータのデフォルト値。 */
    public static final float DEFAULT_CUSTOM_THICKNESS = 0.05f;
    public static final float DEFAULT_CUSTOM_TROLLEY_OFFSET = 0.5f;
    public static final float DEFAULT_CUSTOM_DROPPER_INTERVAL = 2.5f;
    public static final int DEFAULT_CUSTOM_ROW_COUNT = 1;

    /** デフォルト = TWO_TIER, sag=false, custom デフォルト (= 既存呼び出し互換)。 */
    public static WireConnection of(BlockPos a, BlockPos b,
                                     Direction facingA, Direction facingB,
                                     double length) {
        return of(a, b, facingA, facingB, length, WireType.TWO_TIER, false);
    }

    /** type 指定、sag=false (= 既存呼び出し互換)。 */
    public static WireConnection of(BlockPos a, BlockPos b,
                                     Direction facingA, Direction facingB,
                                     double length, WireType type) {
        return of(a, b, facingA, facingB, length, type, false);
    }

    /** type + sag。custom params はデフォルト。 */
    public static WireConnection of(BlockPos a, BlockPos b,
                                     Direction facingA, Direction facingB,
                                     double length, WireType type, boolean sag) {
        return of(a, b, facingA, facingB, length, type, sag,
                DEFAULT_CUSTOM_THICKNESS, DEFAULT_CUSTOM_TROLLEY_OFFSET,
                DEFAULT_CUSTOM_DROPPER_INTERVAL, DEFAULT_CUSTOM_ROW_COUNT);
    }

    /** 全パラメータ指定。 */
    public static WireConnection of(BlockPos a, BlockPos b,
                                     Direction facingA, Direction facingB,
                                     double length, WireType type, boolean sag,
                                     float customThickness, float customTrolleyOffset,
                                     float customDropperInterval, int customRowCount) {
        if (Long.compare(a.asLong(), b.asLong()) <= 0) {
            return new WireConnection(a, b, facingA, facingB, length, type, sag,
                    customThickness, customTrolleyOffset, customDropperInterval, customRowCount);
        }
        // swap して (a, b, facingA, facingB) → (b, a, facingB, facingA) で正規化
        return new WireConnection(b, a, facingB, facingA, length, type, sag,
                customThickness, customTrolleyOffset, customDropperInterval, customRowCount);
    }

    /** 与えられた node の反対側を返す。属さなければ null。 */
    public BlockPos other(BlockPos node) {
        if (node.equals(nodeA)) return nodeB;
        if (node.equals(nodeB)) return nodeA;
        return null;
    }

    public Direction facingOf(BlockPos node) {
        if (node.equals(nodeA)) return facingA;
        if (node.equals(nodeB)) return facingB;
        return null;
    }

    public boolean touches(BlockPos pos) {
        return pos.equals(nodeA) || pos.equals(nodeB);
    }

    /** A 側の取付点ワールド座標 (= 碍子ポスト先端)。 */
    public Vec3 attachA() {
        return InsulatorBlockEntity.attachmentOf(nodeA, facingA);
    }

    /** B 側の取付点ワールド座標。 */
    public Vec3 attachB() {
        return InsulatorBlockEntity.attachmentOf(nodeB, facingB);
    }
}
