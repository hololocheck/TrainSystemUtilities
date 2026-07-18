package com.trainsystemutilities.station.routing;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.HashMap;
import java.util.Map;

/**
 * worker thread から world を読むための chunk キャッシュ付き read-only ビュー。
 *
 * <p>{@code ServerChunkCache.getChunk} は「呼び出し元が main thread でなければ
 * {@code CompletableFuture.supplyAsync(..., mainThreadProcessor).join()}」を **最初に** 判定する
 * (= 4 要素の lastChunk キャッシュはその後ろ)。 つまり worker thread から
 * {@code level.getBlockState(pos)} を呼ぶと 1 ブロックにつき 1 回 server main thread への往復が
 * 発生し、 worker はその完了までブロックする。 数十万ブロックを走査する経路解析では main thread
 * がこのタスクで飽和し、 keep-alive が滞って全クライアントが Timed out で切断される
 * (= マルチサーバーで駅グループ作成時に接続が切れる不具合の原因)。
 *
 * <p>本クラスは chunk を 1 度だけ取得して保持し、 以降は {@link LevelChunk#getBlockState}
 * (= section の null / 範囲 guard を通る) で読む。 main thread への往復回数が「ブロック数」から
 * 「chunk 数」に落ちるため、 走査量に関わらず main thread への影響が実質ゼロになる。
 *
 * <p>スレッド安全ではない — 1 worker タスクにつき 1 インスタンスを生成して使い捨てる。
 * 未ロード chunk は取得しない (= worker から forceload させない) ため、 範囲外は air として扱う。
 */
public final class OffThreadBlockView implements BlockGetter {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    /** chunk key → chunk。 未ロード chunk は null を格納して再取得を防ぐ。 */
    private final Map<Long, LevelChunk> chunks = new HashMap<>();

    public OffThreadBlockView(ServerLevel level) {
        this.level = level;
    }

    /** pos を含む chunk。 未ロードなら null。 main thread 往復は chunk あたり 1 回だけ。 */
    private LevelChunk chunkAt(BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.asLong(cx, cz);
        LevelChunk c = chunks.get(key);
        if (c == null && !chunks.containsKey(key)) {
            // load=false: 未ロード chunk を worker から読込 / 生成させない。
            c = level.getChunkSource().getChunk(cx, cz, false);
            chunks.put(key, c);
        }
        return c;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (isOutsideBuildHeight(pos)) return AIR;
        LevelChunk c = chunkAt(pos);
        return c == null ? AIR : c.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (isOutsideBuildHeight(pos)) return Fluids.EMPTY.defaultFluidState();
        LevelChunk c = chunkAt(pos);
        return c == null ? Fluids.EMPTY.defaultFluidState() : c.getFluidState(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (isOutsideBuildHeight(pos)) return null;
        LevelChunk c = chunkAt(pos);
        return c == null ? null : c.getBlockEntity(pos);
    }

    @Override
    public int getHeight() { return level.getHeight(); }

    @Override
    public int getMinBuildHeight() { return level.getMinBuildHeight(); }
}
