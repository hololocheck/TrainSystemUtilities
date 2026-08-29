package com.trainsystemutilities.client.wiki;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ブロックアイテムの実描画メッシュ書き出し — web wiki の 3D プレビュー用。
 *
 * <p>ゲームの {@code ItemRenderer.render(stack, GUI, ...)} に記録用
 * {@link VertexConsumer} を差し込み、<b>実際に GPU へ送られる頂点そのもの</b>
 * (位置・UV・頂点色・法線、GUI 変換適用済み) と、各 {@link RenderType} が参照する
 * テクスチャを {@code screenshots/wiki/models/<ns>__<path>/} に書き出す。
 * vanilla の焼き込みモデルも GeckoLib BER (BEWLR 委譲) も同一機構で捕れる —
 * 幾何の再実装はゼロで、「ゲームが描くものと全く同じ」が構造で成立する。
 *
 * <p>照明は shader 入力を再現するため、{@code GlStateManager.setupGui3DDiffuseLighting}
 * (/Flat) の行列変換を JOML そのもので計算して mesh.json に書く。式の出典は
 * decompile 済み GlStateManager / Lighting (2026-08-28 実読)。
 *
 * <p>座標系はアイコンキャプチャと同じ GUI スロット空間: translate(8,8,0) →
 * scale(16,-16,16)。ビューワが [0,16]² の正射影で描けば初期フレーム =
 * スロットアイコンと同一になる。
 *
 * <p>失敗は {@code models/_errors.txt} に id ごとに残す (sync 側が非空を赤にする)。
 * 成功一覧は {@code models/_manifest.json}。
 */
final class WikiItemMeshExport {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-WikiItemMesh");

    private WikiItemMeshExport() {}

    /** 記録用バッファ: RenderType ごとに頂点列を蓄積。挿入順 = 描画順を保存する。 */
    private static final class Recorder implements MultiBufferSource {
        final Map<RenderType, Sink> sinks = new LinkedHashMap<>();

        @Override
        public VertexConsumer getBuffer(RenderType rt) {
            return sinks.computeIfAbsent(rt, r -> new Sink());
        }
    }

    /** 1 RenderType 分の頂点記録。fluent API のプリミティブ 6 種だけ実装すれば、
     *  default の putBulkData / 11 引数 addVertex も全てここへ落ちる。 */
    private static final class Sink implements VertexConsumer {
        final List<float[]> verts = new ArrayList<>(); // x,y,z,u,v,nx,ny,nz,r,g,b,a
        private float[] cur;

        private void flush() {
            if (cur != null) verts.add(cur);
            cur = null;
        }

        void finish() { flush(); }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            cur = new float[] { x, y, z, 0, 0, 0, 0, 1, 1, 1, 1, 1 };
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (cur != null) {
                cur[8] = r / 255f; cur[9] = g / 255f; cur[10] = b / 255f; cur[11] = a / 255f;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (cur != null) { cur[3] = u; cur[4] = v; }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) { return this; } // overlay: NO_OVERLAY 固定

        @Override
        public VertexConsumer setUv2(int u, int v) { return this; } // light: 0xF000F0 固定

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (cur != null) { cur[5] = x; cur[6] = y; cur[7] = z; }
            return this;
        }
    }

    /** 対象 namespace の全 BlockItem を書き出す。render thread 前提。
     *  @return 成功件数 */
    static int exportNamespaces(Minecraft mc, String[] namespaces) {
        Path root = mc.gameDirectory.toPath().resolve("screenshots").resolve("wiki")
                .resolve("models");
        List<String> errors = new ArrayList<>();
        List<String> okIds = new ArrayList<>();
        try {
            Files.createDirectories(root);
            // cleanroom: 前回の出力・manifest・errors を先に消す (stale 出荷経路を殺す)
            for (String ns : namespaces) deleteModelDirs(root, ns);
            Files.deleteIfExists(root.resolve("_manifest.json"));
            Files.deleteIfExists(root.resolve("_errors.txt"));
        } catch (Throwable t) {
            LOGGER.error("[WikiItemMesh] cleanroom failed: {}", t.getMessage(), t);
            return 0;
        }
        for (String ns : namespaces) {
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (!ns.equals(key.getNamespace())) continue;
                if (!(item instanceof BlockItem)) continue; // アイテムは対象外 (ユーザー指定)
                try {
                    exportOne(mc, item, key, root);
                    okIds.add(key.toString());
                } catch (Throwable t) {
                    LOGGER.error("[WikiItemMesh] export failed: {}", key, t);
                    errors.add(key + " : " + t);
                }
            }
        }
        // 書き込み順は fail-closed: _errors を先に書き、失敗したら _manifest を
        // 書かない (manifest 欠落は sync 側で赤)。逆順だと errors 書込失敗で
        // 「manifest あり・errors 無し = 全緑」に見える (2026-08-28 レビュー)。
        try {
            if (!errors.isEmpty()) {
                Files.writeString(root.resolve("_errors.txt"), String.join("\n", errors));
            }
            okIds.sort(Comparator.naturalOrder());
            StringBuilder sb = new StringBuilder("{\"v\":1,\"ids\":[");
            for (int i = 0; i < okIds.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(okIds.get(i)).append('"');
            }
            sb.append("]}");
            Files.writeString(root.resolve("_manifest.json"), sb.toString());
        } catch (Throwable t) {
            LOGGER.error("[WikiItemMesh] result write failed (manifest withheld): {}",
                    t.getMessage(), t);
        }
        LOGGER.info("[WikiItemMesh] exported {} meshes, {} errors", okIds.size(), errors.size());
        return okIds.size();
    }

    private static void deleteModelDirs(Path root, String namespace) throws Exception {
        try (var dirs = Files.newDirectoryStream(root, namespace + "__*")) {
            for (Path d : dirs) {
                if (!Files.isDirectory(d)) continue;
                try (var files = Files.newDirectoryStream(d)) {
                    for (Path f : files) Files.deleteIfExists(f);
                }
                Files.deleteIfExists(d);
            }
        }
    }

    private static void exportOne(Minecraft mc, Item item, ResourceLocation key, Path root)
            throws Exception {
        ItemStack stack = new ItemStack(item);
        BakedModel model = mc.getItemRenderer().getModel(stack, null, null, 0);

        // GuiGraphics.renderItem と同じ変換 (z の定数 150 は純平行移動なので落とす)
        Recorder rec = new Recorder();
        PoseStack pose = new PoseStack();
        pose.translate(8.0f, 8.0f, 0.0f);
        pose.scale(16.0f, -16.0f, 16.0f);
        mc.getItemRenderer().render(stack, ItemDisplayContext.GUI, false, pose, rec,
                15728880, OverlayTexture.NO_OVERLAY, model);
        rec.sinks.values().forEach(Sink::finish);

        int totalVerts = rec.sinks.values().stream().mapToInt(s -> s.verts.size()).sum();
        if (totalVerts == 0) throw new IllegalStateException("no vertices recorded");

        Path dir = root.resolve(key.getNamespace() + "__" + key.getPath());
        Files.createDirectories(dir);

        // GlStateManager.setupGui3DDiffuseLighting / setupGuiFlatDiffuseLighting の転記
        // (Lighting.DIFFUSE_LIGHT_0/1 を行列変換した先が shader の Light0/1_Direction)
        boolean flat = !model.usesBlockLight();
        Matrix4f lm = flat
                ? new Matrix4f().rotationY((float) (-Math.PI / 8))
                        .rotateX((float) (Math.PI * 3.0 / 4.0))
                : new Matrix4f().scaling(1.0f, -1.0f, 1.0f)
                        .rotateYXZ(1.0821041F, 3.2375858F, 0.0F)
                        .rotateYXZ((float) (-Math.PI / 8), (float) (Math.PI * 3.0 / 4.0), 0.0F);
        Vector3f l0 = lm.transformDirection(new Vector3f(0.2F, 1.0F, -0.7F).normalize(),
                new Vector3f());
        Vector3f l1 = lm.transformDirection(new Vector3f(-0.2F, 1.0F, 0.7F).normalize(),
                new Vector3f());

        // テクスチャ解決 + グループ化。atlas はスプライト単位に分割して UV を局所化する。
        List<String> texFiles = new ArrayList<>();
        Map<String, Integer> texIndex = new LinkedHashMap<>();
        StringBuilder groups = new StringBuilder();
        boolean first = true;
        for (Map.Entry<RenderType, Sink> e : rec.sinks.entrySet()) {
            RenderType rt = e.getKey();
            Sink sink = e.getValue();
            if (sink.verts.isEmpty()) continue;
            if (sink.verts.size() % 4 != 0) {
                throw new IllegalStateException(
                        "non-quad vertex count " + sink.verts.size() + " for " + rt);
            }
            if (rt.toString().toLowerCase(Locale.ROOT).contains("glint")) {
                // enchant glint は別テクスチャの加算オーバーレイで、静的メッシュに
                // 落とすと二重ジオメトリの誤描画になる。黙って歪めるより赤。
                throw new IllegalStateException("glint layer not exportable: " + rt);
            }
            ResourceLocation tex = textureOf(rt);
            String kind = kindOf(rt);
            if (tex != null && isAtlas(mc, tex)) {
                emitAtlasGroups(mc, tex, sink, kind, dir, texFiles, texIndex, groups,
                        first ? null : ",", modelSprites(model));
            } else {
                int ti = ensureTexture(mc, tex, dir, texFiles, texIndex);
                emitGroup(groups, first ? null : ",", ti, kind, sink.verts, null);
            }
            first = false;
        }

        // 初期ビュー = アイコンパスの枠取りをそのまま再生 (plain/recenter は等倍、
        // fit はアイコンと同じ窓)。幾何中心 pan の独自枠取りは作らない。
        WikiItemIconCapture.Framing fr = WikiItemIconCapture.FRAMINGS.get(key);
        double vZoom = 1, vPanX = 0, vPanY = 0;
        if (fr != null && "recenter".equals(fr.mode)) {
            vPanX = fr.a / 4.0; // 64px セル → 16 GUI 単位
            vPanY = fr.b / 4.0;
        } else if (fr != null && "fit".equals(fr.mode)) {
            // オーバースキャン px → GUI 単位 (中央セル原点へ平行移動)
            double cxU = fr.a / 8.0 - WikiItemIconCapture.LOGICAL_SIZE;
            double cyU = fr.b / 8.0 - WikiItemIconCapture.LOGICAL_SIZE;
            double winU = fr.c / 8.0;
            vZoom = 16.0 / winU;
            vPanX = 8.0 - cxU;
            vPanY = 8.0 - cyU;
        }

        StringBuilder json = new StringBuilder(1 << 16);
        json.append("{\"v\":2,\"id\":\"").append(key).append("\",");
        json.append("\"view\":{\"zoom\":").append(f(vZoom))
            .append(",\"panX\":").append(f(vPanX))
            .append(",\"panY\":").append(f(vPanY)).append("},");
        json.append("\"flatLight\":").append(flat).append(',');
        json.append("\"l0\":").append(vec(l0)).append(',');
        json.append("\"l1\":").append(vec(l1)).append(',');
        json.append("\"texs\":[");
        for (int i = 0; i < texFiles.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(texFiles.get(i)).append('"');
        }
        json.append("],\"groups\":[").append(groups).append("]}");
        Files.writeString(dir.resolve("mesh.json"), json.toString());
    }

    private static String vec(Vector3f v) {
        return "[" + f(v.x) + "," + f(v.y) + "," + f(v.z) + "]";
    }

    private static String f(double d) {
        String s = String.format(Locale.ROOT, "%.5f", d);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** RenderType 名から描画クラスを決める。emissive は頂点照明をかけず、MC 側は
     *  半透明ブレンドで描く型 (entity_translucent_emissive / eyes) — ビューワも
     *  半透明パスで描く。glint はここに来る前に fail-loud で落とす。 */
    private static String kindOf(RenderType rt) {
        String n = rt.toString().toLowerCase(Locale.ROOT);
        if (n.contains("emissive") || n.contains("eyes")) return "emissive";
        if (n.contains("translucent")) return "translucent";
        return "cutout";
    }

    // ---- texture resolution -------------------------------------------------

    private static final Pattern TOSTRING_TEX =
            Pattern.compile("Optional\\[([a-z0-9_.-]+:[a-z0-9_./-]+)\\]");

    /** RenderType が bind するテクスチャ。reflection (FML は package を open する) が
     *  一次、失敗時は toString 表現から拾う。 */
    private static ResourceLocation textureOf(RenderType rt) {
        try {
            var stateM = rt.getClass().getDeclaredMethod("state");
            stateM.setAccessible(true);
            Object state = stateM.invoke(rt);
            var texF = state.getClass().getDeclaredField("textureState");
            texF.setAccessible(true);
            Object shard = texF.get(state);
            var cut = shard.getClass().getMethod("cutoutTexture");
            cut.setAccessible(true);
            @SuppressWarnings("unchecked")
            Optional<ResourceLocation> o = (Optional<ResourceLocation>) cut.invoke(shard);
            if (o.isPresent()) return o.get();
        } catch (Throwable t) {
            Matcher m = TOSTRING_TEX.matcher(rt.toString());
            if (m.find()) return ResourceLocation.parse(m.group(1));
            LOGGER.warn("[WikiItemMesh] texture unresolved for {}: {}", rt, t.toString());
        }
        return null;
    }

    private static boolean isAtlas(Minecraft mc, ResourceLocation tex) {
        return tex.getPath().startsWith("textures/atlas/")
                && mc.getTextureManager().getTexture(tex) instanceof TextureAtlas;
    }

    /** モデルが意図するスプライト集合 (焼き込み quad の getSprite)。UV 帰属の一次候補。
     *  seed 42 固定は renderModelLists と同じ。 */
    private static List<TextureAtlasSprite> modelSprites(BakedModel model) {
        List<TextureAtlasSprite> out = new ArrayList<>();
        RandomSource rand = RandomSource.create();
        for (Direction d : Direction.values()) {
            rand.setSeed(42L);
            for (BakedQuad q : model.getQuads(null, d, rand)) addUnique(out, q.getSprite());
        }
        rand.setSeed(42L);
        for (BakedQuad q : model.getQuads(null, null, rand)) addUnique(out, q.getSprite());
        return out;
    }

    private static void addUnique(List<TextureAtlasSprite> list, TextureAtlasSprite s) {
        if (s != null && !list.contains(s)) list.add(s);
    }

    /** atlas グループ: 頂点 UV をスプライトへ帰属させ、スプライトごとに 1 グループ
     *  として UV を [0,1] に局所化して出す。帰属は quad 単位 (4 頂点の UV 平均点)。
     *
     * <p>帰属順: (1) モデル候補スプライトの矩形包含 → (2) 候補が無い場合 (BER 由来)
     * のみ atlas 全体の包含 → (3) 最近傍のモデル候補。(3) は Blockbench export が
     * UV を 16 の範囲外へ
     * はみ出させたケース (fe_inverter — 実描画では隣スプライトへの bleed になる)。
     * その quad の局所 UV は [0,1] の外に出るが、ビューワ側の CLAMP_TO_EDGE が
     * 端 texel で埋める — bleed した数 texel だけゲームと異なりうる (実測で
     * アイコン 64px では不可視)。 */
    private static void emitAtlasGroups(Minecraft mc, ResourceLocation atlasLoc, Sink sink,
            String kind, Path dir, List<String> texFiles, Map<String, Integer> texIndex,
            StringBuilder out, String sep, List<TextureAtlasSprite> candidates)
            throws Exception {
        TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(atlasLoc);
        Map<ResourceLocation, TextureAtlasSprite> byName = atlasSprites(atlas);
        Map<TextureAtlasSprite, List<float[]>> bySprite = new LinkedHashMap<>();
        for (int q = 0; q < sink.verts.size(); q += 4) {
            float cu = 0, cv = 0;
            for (int i = 0; i < 4; i++) {
                cu += sink.verts.get(q + i)[3];
                cv += sink.verts.get(q + i)[4];
            }
            cu /= 4f; cv /= 4f;
            TextureAtlasSprite sprite = null;
            for (TextureAtlasSprite s : candidates) {
                if (contains(s, cu, cv)) { sprite = s; break; }
            }
            if (sprite == null && candidates.isEmpty()) {
                // BER 由来 (モデル候補なし) のみ atlas 全体で解決。候補があるのに
                // そこへ入らない中心は bleed であり、全体包含は「たまたま隣に
                // いた他 mod の sprite」を返す (fe_inverter で実測) ので使わない。
                sprite = findSprite(byName, cu, cv);
            }
            if (sprite == null && !candidates.isEmpty()) {
                double best = Double.MAX_VALUE;
                for (TextureAtlasSprite s : candidates) {
                    double du = Math.max(0, Math.max(s.getU0() - cu, cu - s.getU1()));
                    double dv = Math.max(0, Math.max(s.getV0() - cv, cv - s.getV1()));
                    double d2 = du * du + dv * dv;
                    if (d2 < best) { best = d2; sprite = s; }
                }
                LOGGER.warn("[WikiItemMesh] uv ({}, {}) outside all sprites; "
                        + "clamped to nearest model sprite {}", cu, cv,
                        sprite.contents().name());
            }
            if (sprite == null) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "no sprite contains uv (%.5f, %.5f) in %s", cu, cv, atlasLoc));
            }
            List<float[]> list = bySprite.computeIfAbsent(sprite, s -> new ArrayList<>());
            for (int i = 0; i < 4; i++) list.add(sink.verts.get(q + i));
        }
        boolean needSep = sep != null;
        for (Map.Entry<TextureAtlasSprite, List<float[]>> e : bySprite.entrySet()) {
            TextureAtlasSprite sp = e.getKey();
            int ti = ensureSpriteTexture(mc, sp, dir, texFiles, texIndex);
            emitGroup(out, needSep ? "," : null, ti, kind, e.getValue(), sp);
            needSep = true;
        }
    }

    private static boolean contains(TextureAtlasSprite s, float u, float v) {
        return u >= s.getU0() && u <= s.getU1() && v >= s.getV0() && v <= s.getV1();
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, TextureAtlasSprite> atlasSprites(TextureAtlas atlas)
            throws Exception {
        var f = TextureAtlas.class.getDeclaredField("texturesByName");
        f.setAccessible(true);
        return (Map<ResourceLocation, TextureAtlasSprite>) f.get(atlas);
    }

    private static TextureAtlasSprite findSprite(
            Map<ResourceLocation, TextureAtlasSprite> byName, float u, float v) {
        for (TextureAtlasSprite s : byName.values()) {
            if (u >= s.getU0() && u <= s.getU1() && v >= s.getV0() && v <= s.getV1()) return s;
        }
        return null;
    }

    /** 頂点列を 1 グループとして JSON へ。sprite 非 null なら UV をスプライト局所へ変換。
     *  wrap: MC は非 atlas テクスチャに GL 既定の REPEAT を使う (TextureUtil は
     *  wrap を設定しない — decompile 実読)。atlas スプライトは局所化で [0,1] 外 =
     *  bleed になるので clamp (既記録の逸脱)。 */
    private static void emitGroup(StringBuilder out, String sep, int texIdx, String kind,
            List<float[]> verts, TextureAtlasSprite sprite) {
        if (sep != null) out.append(sep);
        out.append("{\"tex\":").append(texIdx).append(",\"kind\":\"").append(kind).append('"');
        out.append(",\"wrap\":\"").append(sprite == null ? "repeat" : "clamp").append('"');
        StringBuilder pos = new StringBuilder(), uv = new StringBuilder(),
                nrm = new StringBuilder(), col = new StringBuilder();
        for (int i = 0; i < verts.size(); i++) {
            float[] w = verts.get(i);
            if (i > 0) { pos.append(','); uv.append(','); nrm.append(','); col.append(','); }
            pos.append(f(w[0])).append(',').append(f(w[1])).append(',').append(f(w[2]));
            float u = w[3], v = w[4];
            if (sprite != null) {
                u = (u - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
                v = (v - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
            }
            uv.append(f(u)).append(',').append(f(v));
            nrm.append(f(w[5])).append(',').append(f(w[6])).append(',').append(f(w[7]));
            col.append(f(w[8])).append(',').append(f(w[9])).append(',')
               .append(f(w[10])).append(',').append(f(w[11]));
        }
        out.append(",\"pos\":[").append(pos).append(']');
        out.append(",\"uv\":[").append(uv).append(']');
        out.append(",\"nrm\":[").append(nrm).append(']');
        out.append(",\"col\":[").append(col).append(']');
        out.append('}');
    }

    // ---- texture export -----------------------------------------------------

    /** 非 atlas テクスチャ 1 枚をリソースからコピー (無ければ GL からダウンロード)。 */
    private static int ensureTexture(Minecraft mc, ResourceLocation tex, Path dir,
            List<String> texFiles, Map<String, Integer> texIndex) throws Exception {
        String keyName = tex == null ? "<none>" : tex.toString();
        Integer existing = texIndex.get(keyName);
        if (existing != null) return existing;
        int idx = texFiles.size();
        String file = "t" + idx + ".png";
        if (tex == null) {
            // テクスチャ無し RenderType (lines 等)。1x1 白で代替。
            try (NativeImage img = new NativeImage(1, 1, false)) {
                img.setPixelRGBA(0, 0, 0xFFFFFFFF);
                img.writeToFile(dir.resolve(file));
            }
        } else {
            var res = mc.getResourceManager().getResource(tex);
            if (res.isPresent()) {
                try (InputStream in = res.get().open()) {
                    Files.copy(in, dir.resolve(file));
                }
            } else {
                downloadTexture(mc, tex, dir.resolve(file));
            }
        }
        texFiles.add(file);
        texIndex.put(keyName, idx);
        return idx;
    }

    /** スプライト 1 枚: ソース PNG をコピー。アニメーション帯 (ソース寸法 ≠ スプライト
     *  寸法) は atlas に載っている先頭フレームだけ切り出す。 */
    private static int ensureSpriteTexture(Minecraft mc, TextureAtlasSprite sp, Path dir,
            List<String> texFiles, Map<String, Integer> texIndex) throws Exception {
        String keyName = "sprite:" + sp.contents().name();
        Integer existing = texIndex.get(keyName);
        if (existing != null) return existing;
        int idx = texFiles.size();
        String file = "t" + idx + ".png";
        ResourceLocation name = sp.contents().name();
        ResourceLocation png = ResourceLocation.fromNamespaceAndPath(
                name.getNamespace(), "textures/" + name.getPath() + ".png");
        int sw = sp.contents().width(), sh = sp.contents().height();
        var res = mc.getResourceManager().getResource(png);
        if (res.isEmpty()) throw new IllegalStateException("sprite source missing: " + png);
        try (InputStream in = res.get().open(); NativeImage src = NativeImage.read(in)) {
            if (src.getWidth() == sw && src.getHeight() == sh) {
                try (InputStream in2 = mc.getResourceManager().getResource(png).get().open()) {
                    Files.copy(in2, dir.resolve(file)); // バイト同一コピー
                }
            } else {
                try (NativeImage frame = new NativeImage(sw, sh, false)) {
                    for (int y = 0; y < sh; y++)
                        for (int x = 0; x < sw; x++)
                            frame.setPixelRGBA(x, y, src.getPixelRGBA(x, y));
                    frame.writeToFile(dir.resolve(file));
                }
            }
        }
        texFiles.add(file);
        texIndex.put(keyName, idx);
        return idx;
    }

    /** リソースに実体が無い動的テクスチャ (フォント等) を GL から読み出す。 */
    private static void downloadTexture(Minecraft mc, ResourceLocation tex, Path out)
            throws Exception {
        AbstractTexture t = mc.getTextureManager().getTexture(tex);
        RenderSystem.bindTexture(t.getId());
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) throw new IllegalStateException("texture has no size: " + tex);
        try (NativeImage img = new NativeImage(w, h, false)) {
            img.downloadTexture(0, false);
            img.writeToFile(out);
        }
    }

}
