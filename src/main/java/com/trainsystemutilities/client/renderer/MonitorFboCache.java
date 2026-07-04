package com.trainsystemutilities.client.renderer;

import belugalab.mcss3.world.CSSWorldRenderer;
import belugalab.mcss3.world.CapturedDraw;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitor 表示を GPU FBO (RenderTarget) に焼き付けてキャッシュする最適化レイヤー。
 *
 * <p>従来は毎フレーム CSS レイアウト + vertex 出力を行っていたが、内容変化が稀なので
 * <strong>テクスチャに 1 回焼き、以降は textured quad 1 枚を draw する</strong> ことで
 * CPU/GPU の drawcall 数を大幅に削減する。
 *
 * <ul>
 *   <li>各 (monitor, dims) に対して 1 つの {@link RenderTarget} を割当</li>
 *   <li>contentHash 変化時のみ FBO を再描画</li>
 *   <li>毎フレームは {@link #drawTexturedQuad} で textured quad 1 個を出力</li>
 * </ul>
 */
public class MonitorFboCache {

    private static final ConcurrentHashMap<String, FboEntry> cache = new ConcurrentHashMap<>();
    private static long lastCleanupNanos = 0;
    private static final long CLEANUP_INTERVAL_NS = 30_000_000_000L; // 30秒
    private static final long ENTRY_TTL_NS = 60_000_000_000L; // 60秒未使用で破棄

    /**
     * FBO レンダリング専用の immediate buffer source。
     * <strong>共有 bufferSource を使うと他コードがバッファした vertex まで FBO に flush
     * されてしまい、エンティティ点滅バグの原因になる</strong>。
     */
    private static com.mojang.blaze3d.vertex.ByteBufferBuilder fboByteBuilder;
    private static net.minecraft.client.renderer.MultiBufferSource.BufferSource fboBufferSource;

    /**
     * Pending FBO 更新キュー (deferred update for Iris compat)。
     * メイン描画パス中に FBO state を変更すると Iris の multi-pass rendering と衝突し
     * 列車点滅等が発生する。renderInContraption からは requestUpdate でキューに積み、
     * フレーム末尾の {@code RenderLevelStageEvent.AFTER_LEVEL} で一括処理する。
     */
    private static final java.util.concurrent.ConcurrentLinkedQueue<UpdateTask> pendingUpdates =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 1フレームで処理する最大 FBO 更新数 (バーストでフレームが詰まらないようガード)。 */
    private static final int MAX_UPDATES_PER_FRAME = 16;

    private record UpdateTask(String key, int width, int height, int contentHash,
                              CapturedDraw captured, boolean force) {}

    private static net.minecraft.client.renderer.MultiBufferSource.BufferSource getFboBufferSource() {
        if (fboBufferSource == null) {
            fboByteBuilder = new com.mojang.blaze3d.vertex.ByteBufferBuilder(2048);
            fboBufferSource = net.minecraft.client.renderer.MultiBufferSource.immediate(fboByteBuilder);
        }
        return fboBufferSource;
    }

    public static final class FboEntry {
        public final RenderTarget target;
        public final ResourceLocation textureLoc;
        private final FboTextureWrapper wrapper;
        public int width;
        public int height;
        public int contentHash;
        public boolean valid; // FBO に有効な内容が描画済みか
        public long lastUseNanos;

        FboEntry(int width, int height, String key) {
            this.width = width;
            this.height = height;
            this.target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            this.target.setClearColor(0f, 0f, 0f, 0f);
            this.textureLoc = ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "dyn/monitor/" + key.toLowerCase().replaceAll("[^a-z0-9_/.-]", "_"));
            this.wrapper = new FboTextureWrapper(this.target);
            try {
                Minecraft.getInstance().getTextureManager().register(this.textureLoc, this.wrapper);
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn("[MonitorFboCache] failed to register texture {}: {}",
                        textureLoc, e.getMessage());
            }
            this.contentHash = 0;
            this.valid = false;
            this.lastUseNanos = System.nanoTime();
        }

        void destroy() {
            try {
                Minecraft.getInstance().getTextureManager().release(textureLoc);
            } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorFbo] GL op failed", ignored); }
            try {
                target.destroyBuffers();
            } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorFbo] GL op failed", ignored); }
        }
    }

    /** AbstractTexture 派生クラス。getId() を override して FBO の color attachment id を返す。 */
    private static final class FboTextureWrapper extends AbstractTexture {
        private final RenderTarget target;

        FboTextureWrapper(RenderTarget target) {
            this.target = target;
        }

        @Override
        public void load(ResourceManager mgr) {
            // no-op (FBO は外部から更新)
        }

        @Override
        public int getId() {
            return target.getColorTextureId();
        }

        @Override
        public void releaseId() {
            // FBO 側の destroyBuffers が解放するため、ここでは何もしない
        }
    }

    /**
     * Monitor 内容が変化した時に FBO を更新する。
     *
     * @param key          monitor 識別子 (entityId@masterPos など)
     * @param width        FBO 幅 (px)
     * @param height       FBO 高さ (px)
     * @param contentHash  内容ハッシュ (変化した場合のみ再描画)
     * @param captured     描画する {@link CapturedDraw} (CSS-local 座標)
     * @return キャッシュエントリ。textureLoc を使ってテクスチャ描画する
     */
    /**
     * 既存エントリのみ返す (なければ null)。FBO を更新しない。
     * アニメ中など capturedDraw が無い時に古い FBO 内容を使い続けるため。
     */
    public static FboEntry peek(String key, int width, int height) {
        FboEntry entry = cache.get(key);
        if (entry == null) return null;
        if (entry.width != width || entry.height != height) return null;
        if (!entry.valid) return null;
        entry.lastUseNanos = System.nanoTime();
        return entry;
    }

    public static FboEntry getOrUpdate(String key, int width, int height,
                                        int contentHash, CapturedDraw captured) {
        return getOrUpdate(key, width, height, contentHash, captured, false);
    }

    /**
     * @param forceUpdate true なら contentHash が同じでも FBO を再描画する
     *                    (アニメ中で内容ハッシュは同じだが内部状態が変わるケース用)
     */
    public static FboEntry getOrUpdate(String key, int width, int height,
                                        int contentHash, CapturedDraw captured,
                                        boolean forceUpdate) {
        FboEntry entry = cache.get(key);
        if (entry == null || entry.width != width || entry.height != height) {
            if (entry != null) entry.destroy();
            entry = new FboEntry(width, height, key);
            cache.put(key, entry);
        }
        entry.lastUseNanos = System.nanoTime();
        if (forceUpdate || entry.contentHash != contentHash || !entry.valid) {
            renderToFbo(entry.target, width, height, captured);
            entry.contentHash = contentHash;
            entry.valid = true;
        }
        maybeCleanup();
        return entry;
    }

    /**
     * 指定 RenderTarget に CapturedDraw を 1 回描画する。
     *
     * <p>GL 状態を一時的に変更する (FBO 切替・projection matrix・viewport) ため
     * 呼び出し前後で main render pass が壊れないよう保存・復元する。
     */
    private static void renderToFbo(RenderTarget target, int width, int height, CapturedDraw captured) {
        if (captured == null || captured.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        Matrix4f savedProj = RenderSystem.getProjectionMatrix();
        VertexSorting savedSort = RenderSystem.getVertexSorting();

        try {
            target.setClearColor(0f, 0f, 0f, 0f);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true); // also sets viewport to (0,0,width,height)

            // CSS-local 座標で焼く: (0,0)=top-left, (w,h)=bottom-right。OpenGL ortho の
            // top/bottom を逆にすることで Y 方向を CSS 慣行 (Y 下向き) に合わせる。
            Matrix4f ortho = new Matrix4f().setOrtho(0f, width, height, 0f, -1000f, 1000f);
            RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

            // 専用 immediate bufferSource を使う (共有 bufferSource は他コードの vertex を
            // 含む可能性があるため endBatch() でそれらを破壊する。専用なら安全)。
            net.minecraft.client.renderer.MultiBufferSource.BufferSource bs = getFboBufferSource();

            PoseStack identity = new PoseStack();
            CSSWorldRenderer.replayCapture(captured, identity, bs);
            bs.endBatch();
        } catch (Exception e) {
            TrainSystemUtilities.LOGGER.warn("[MonitorFboCache] FBO render failed: {}", e.getMessage());
        } finally {
            // GL 状態の復元
            try { mainTarget.bindWrite(true); } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorFbo] GL op failed", ignored); }
            try { RenderSystem.setProjectionMatrix(savedProj, savedSort); } catch (Exception ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[MonitorFbo] GL op failed", ignored); }
        }
    }

    /**
     * FBO のテクスチャを使って textured quad を描画する。
     * CSS-local 座標系で (0,0)〜(width,height) のクワッドを poseStack の現在の変換で出力。
     */
    public static void drawTexturedQuad(FboEntry entry, PoseStack poseStack,
                                        MultiBufferSource bufferSource,
                                        int width, int height) {
        if (entry == null || !entry.valid) return;
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(entry.textureLoc));
        Matrix4f m = poseStack.last().pose();
        int light = 0xF000F0; // fullbright
        int overlay = OverlayTexture.NO_OVERLAY;
        // CSS では (0,0)=左上、Y 下向き。FBO テクスチャもそれに合わせて焼かれているので
        // UV (0,0)=左上、UV (1,1)=右下 で素直にマッピング。
        vc.addVertex(m, 0,     0,      0).setColor(255,255,255,255).setUv(0, 0).setUv1(overlay&0xFFFF, (overlay>>16)&0xFFFF).setUv2(light&0xFFFF, (light>>16)&0xFFFF).setNormal(0,0,1);
        vc.addVertex(m, 0,     height, 0).setColor(255,255,255,255).setUv(0, 1).setUv1(overlay&0xFFFF, (overlay>>16)&0xFFFF).setUv2(light&0xFFFF, (light>>16)&0xFFFF).setNormal(0,0,1);
        vc.addVertex(m, width, height, 0).setColor(255,255,255,255).setUv(1, 1).setUv1(overlay&0xFFFF, (overlay>>16)&0xFFFF).setUv2(light&0xFFFF, (light>>16)&0xFFFF).setNormal(0,0,1);
        vc.addVertex(m, width, 0,      0).setColor(255,255,255,255).setUv(1, 0).setUv1(overlay&0xFFFF, (overlay>>16)&0xFFFF).setUv2(light&0xFFFF, (light>>16)&0xFFFF).setNormal(0,0,1);
    }

    /** 期限切れエントリ (60秒未使用) を破棄。 */
    private static void maybeCleanup() {
        long now = System.nanoTime();
        if (now - lastCleanupNanos < CLEANUP_INTERVAL_NS) return;
        lastCleanupNanos = now;
        cache.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUseNanos > ENTRY_TTL_NS) {
                e.getValue().destroy();
                return true;
            }
            return false;
        });
        // 上限保護
        if (cache.size() > 500) {
            int target = cache.size() - 250;
            var it = cache.entrySet().iterator();
            int removed = 0;
            while (it.hasNext() && removed < target) {
                var e = it.next();
                e.getValue().destroy();
                it.remove();
                removed++;
            }
        }
    }

    public static void clearAll() {
        cache.values().forEach(FboEntry::destroy);
        cache.clear();
        pendingUpdates.clear();
    }

    /**
     * FBO 更新を deferred キューに登録 (実行は AFTER_LEVEL の processPendingUpdates で行う)。
     * Iris シェーダー併用時の安全な FBO 更新パスとして使用する。
     *
     * <p>同じ key の重複は最後の要求のみ有効になる (LinkedHashMap 的に上書き)。
     */
    public static void requestUpdate(String key, int width, int height,
                                      int contentHash, CapturedDraw captured, boolean force) {
        if (captured == null || captured.isEmpty()) return;
        // 同 key の古い update を退場させてから新規追加 (= 最新だけ実行)。
        pendingUpdates.removeIf(t -> t.key.equals(key));
        pendingUpdates.offer(new UpdateTask(key, width, height, contentHash, captured, force));
    }

    /**
     * AFTER_LEVEL フェーズで呼ばれる: pending FBO 更新を一括処理。
     * 1フレームあたり最大 {@link #MAX_UPDATES_PER_FRAME} 件まで (バーストガード)。
     */
    public static void processPendingUpdates() {
        int count = 0;
        UpdateTask task;
        while (count < MAX_UPDATES_PER_FRAME && (task = pendingUpdates.poll()) != null) {
            try {
                getOrUpdate(task.key, task.width, task.height,
                        task.contentHash, task.captured, task.force);
            } catch (Exception e) {
                // 個別失敗は他の更新に影響させない
            }
            count++;
        }
    }

    // === Iris/シェーダー検知 (FBO 経路と非互換のため、有効時は skip する) ===
    private static volatile boolean cachedShaderActive = false;
    private static volatile long lastShaderCheckNanos = 0;
    private static final long SHADER_CHECK_INTERVAL_NS = 1_000_000_000L; // 1秒
    private static volatile Boolean irisAvailable = null; // null = 未判定

    /**
     * Iris シェーダーパックが現在使用中かどうかを判定する。
     * 1秒間隔でキャッシュし、reflection 呼び出しのオーバーヘッドを抑える。
     * <p>シェーダー状態が変化した瞬間、既存 FBO エントリ (stale なテクスチャ内容) を
     * 一括破棄して、次フレームでクリーンに焼き直されるようにする。
     */
    public static boolean isShaderActive() {
        long now = System.nanoTime();
        if (now - lastShaderCheckNanos > SHADER_CHECK_INTERVAL_NS) {
            lastShaderCheckNanos = now;
            boolean prev = cachedShaderActive;
            cachedShaderActive = checkShaderActive();
            if (prev != cachedShaderActive) {
                // ON ↔ OFF 切替: 古い FBO は無効化
                clearAll();
            }
        }
        return cachedShaderActive;
    }

    private static boolean checkShaderActive() {
        if (irisAvailable != null && !irisAvailable) return false;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Boolean inUse = (Boolean) apiClass.getMethod("isShaderPackInUse").invoke(api);
            irisAvailable = true;
            return inUse != null && inUse;
        } catch (ClassNotFoundException e) {
            irisAvailable = false;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private MonitorFboCache() {}
}
