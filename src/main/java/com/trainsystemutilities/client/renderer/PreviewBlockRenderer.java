package com.trainsystemutilities.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 3D ブロックプレビュー用の renderSingleBlock ラッパ。
 *
 * <p>vanilla の {@code BlockRenderDispatcher.renderSingleBlock(state, ...)} は BlockState
 * のみを受け取るため、Create のコピーパネル等の BlockEntity-driven な見た目を持つ
 * ブロックは正しく描画されない (素のパネル形状でテクスチャ無し、または missing texture)。
 *
 * <p>本クラスは Copycat 系のブロックに対してのみ一時的な BlockEntity を生成して
 * {@link BlockEntity#getModelData()} を取得し、NeoForge の ModelData 対応版
 * renderSingleBlock を呼び出す。Create の Copycat 系 BE は getModelData() を override
 * して material の {@link BlockState} を ModelData に格納するため、この経路で正しい
 * 模倣テクスチャが描画される。
 *
 * <p>Copycat 以外の BE 持ちブロックには ModelData を渡さない。理由: Create の
 * rail/gear/axle 等は level.getBlockState(neighbor) で連結状態を計算するが、
 * プレビュー環境では隣接情報がないため空 ModelData → 非表示の副作用が出る。
 *
 * <p>パフォーマンス: BE インスタンス生成は ~10μs/call。(state, beNbt identity) で
 * キャッシュし、各 BE につき 1 回だけ作る。
 */
public final class PreviewBlockRenderer {

    private PreviewBlockRenderer() {}

    /** ModelData の transient cache。key は (state, beNbt identityHash) で同一プレビュー内で再利用。 */
    private static final ConcurrentHashMap<CacheKey, ModelData> modelDataCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 2048;

    private record CacheKey(BlockState state, int nbtIdentity) {}

    /**
     * 3D プレビュー用にブロックを描画する。{@code beNbt} があり対象ブロックが
     * Copycat 系の場合は ModelData を渡して material テクスチャを再現する。
     *
     * @param state    描画する BlockState
     * @param beNbt    BlockEntity NBT (なければ null)
     * @param pos      ブロック位置 (BE 一時生成用)
     * @param pose     現在のローカル変換が積まれた pose
     * @param buffer   描画先の MultiBufferSource
     * @param light    packed light (例: 0xF000F0 = full bright)
     * @param overlay  packed overlay (通常は OverlayTexture.NO_OVERLAY)
     */
    public static void renderBlock(BlockState state, CompoundTag beNbt,
                                    BlockPos pos, PoseStack pose,
                                    MultiBufferSource buffer, int light, int overlay) {
        if (state == null || state.isAir()) return;
        var blockRenderer = Minecraft.getInstance().getBlockRenderer();
        ModelData modelData = resolveModelData(state, beNbt, pos);
        try {
            blockRenderer.renderSingleBlock(state, pose, buffer, light, overlay, modelData, null);
        } catch (Throwable ignored) {
            // 一部 mod のブロックは preview 環境で例外を投げる: 無視して次へ
        }
    }

    private static ModelData resolveModelData(BlockState state, CompoundTag beNbt, BlockPos pos) {
        if (beNbt == null || !state.hasBlockEntity()) return ModelData.EMPTY;
        if (!isCopycatLikeBlock(state)) return ModelData.EMPTY;

        CacheKey key = new CacheKey(state, System.identityHashCode(beNbt));
        ModelData cached = modelDataCache.get(key);
        if (cached != null) return cached;

        ModelData computed = computeModelData(state, beNbt, pos);
        if (modelDataCache.size() > MAX_CACHE_ENTRIES) modelDataCache.clear();
        modelDataCache.put(key, computed);
        return computed;
    }

    /**
     * Copycat / Mimic / Disguise 系ブロック (= 別ブロックの見た目を模倣するため
     * BE NBT に material を持つブロック) かどうかを判定。
     */
    private static boolean isCopycatLikeBlock(BlockState state) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) return false;
        String path = key.getPath();
        return path.contains("copycat") || path.contains("mimic")
                || path.contains("disguise") || path.contains("facade");
    }

    private static ModelData computeModelData(BlockState state, CompoundTag beNbt, BlockPos pos) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return ModelData.EMPTY;
            BlockEntity be = BlockEntity.loadStatic(pos, state, beNbt, mc.level.registryAccess());
            if (be == null) return ModelData.EMPTY;
            // 一部の BE は getModelData 内で level を参照する。
            try { be.setLevel(mc.level); } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[PreviewBlock] BE setLevel failed", ignored); }
            ModelData data = be.getModelData();
            return data != null ? data : ModelData.EMPTY;
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.debug(
                    "[PreviewBlockRenderer] getModelData failed for {}: {}",
                    state.getBlock(), t.toString());
            return ModelData.EMPTY;
        }
    }

    /** プレビューを閉じるときに呼ぶと cache を解放できる (なくても上限ガードで cap される)。 */
    public static void clearCache() {
        modelDataCache.clear();
    }
}
