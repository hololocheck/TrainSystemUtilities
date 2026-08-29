package com.trainsystemutilities.client.wiki;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animation.AnimationController;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * アニメーションを持つブロックの姿勢を、時間軸に沿って複数フレーム書き出す。
 *
 * <p>web 側のモデルプレビューで「開く / 閉じる」を再生するための材料。静止 1 枚を
 * 出す {@link WikiItemMeshExport} と同じ記録経路を使い、同じ頂点列を<b>時刻だけ変えて</b>
 * 何度も取る。したがってトポロジ・UV・テクスチャは base mesh と完全に同じで、
 * ビューワは動く頂点の位置 (と法線) だけ差し替えれば良い。
 *
 * <p><b>時刻の合わせ方</b> — ここが唯一難しい所で、2026-08-29 のレビューで 2 つの罠が
 * 見つかっている:
 * <ul>
 *   <li>GeckoLib が {@code process} に渡す時刻は {@code GeoModel.animTime} (モデル生成
 *       からの累積) であって、{@code Blaze3D.getTime()*20} ではない。原点が違うので
 *       外から絶対時刻を計算すると全フレームが {@code adjustedTick=0} に潰れる。
 *       → <b>controller の {@code lastPollTime} を読んで、その時計で相対指定する。</b></li>
 *   <li>{@code HOLD_ON_LAST_FRAME} は終端で {@code animationState=PAUSED} を書き、
 *       PAUSED の間 {@code process} は bone を一切触らない。放置すると「動いてはいる
 *       が中身は 1 tick ぶんの戻り補間」という、maxDelta では見抜けないデータが出る。
 *       → <b>毎フレーム RUNNING に戻してから描く。</b></li>
 * </ul>
 * 実際に達成された時刻はフレームごとに JSON に書く (等間隔サンプルという仮定を
 * 測定値にする)。
 *
 * <p>出力は {@code screenshots/wiki/models/<ns>__<path>/anim.json}。動く頂点だけを
 * index 付きで持ち、base mesh の頂点数と一致しなければ FATAL。
 */
final class WikiItemAnimExport {

    private static final Logger LOGGER = LoggerFactory.getLogger("TSU-WikiItemAnim");

    /** 開始 (t=0) と終端を含めて FRAMES+1 枚。 */
    private static final int FRAMES = 12;
    /** これ未満しか動かない頂点は「静止」とみなして持たない (GUI 単位)。 */
    private static final float MOVE_EPSILON = 0.002f;

    private WikiItemAnimExport() {}

    /** 駆動方式。
     *  <ul>
     *    <li>{@code TICK_SEEK} — GeckoLib の時計を目的の時刻へ合わせて描く。
     *        アニメ JSON をそのまま再生している普通のブロック (ホームドア・改札)。</li>
     *    <li>{@code RENDER_T} — モデルが独自の進捗 T で姿勢を決めているもの
     *        (パンタグラフ)。GeckoLib を seek しても
     *        {@code PantographGeoModel.setCustomAnimations} が T から計算した値で
     *        上書きするので、時計をいくら動かしても 1 ミリも動かない
     *        (2026-08-29 の実機実行で max delta 0.00000 として観測)。
     *        こちらは「展開状態にして描き続け、T が 1 に収束するまでの実際の姿勢」を
     *        1 フレームずつ拾う — ゲームが実際に見せる動きそのもの。</li>
     *  </ul> */
    private enum Driver { TICK_SEEK, RENDER_T }

    /** 1 つのアニメ対象。state は BE を「動いた後」/「元」に切り替える操作。 */
    private record Target(String id, String label, String animation, double seconds,
                          Driver driver, Consumer<BlockEntity> setMoved,
                          Consumer<BlockEntity> restore) {}

    /** 対象は TSU の 3 ブロック。いずれもトグルなので web もトグルボタンになる。
     *  animation 名は<b>実際に再生される名前</b>を書くこと (パンタの述語が流すのは
     *  deploy 側の名前で、ファイル上の "unfold" ではない)。 */
    private static final List<Target> TARGETS = List.of(
            new Target("trainsystemutilities:pantograph", "展開 / 折畳", "fold", 0.375,
                    Driver.RENDER_T,
                    be -> ((com.trainsystemutilities.electrification.blockentity
                            .PantographBlockEntity) be).setDeployed(true),
                    be -> ((com.trainsystemutilities.electrification.blockentity
                            .PantographBlockEntity) be).setDeployed(false)),
            new Target("trainsystemutilities:platform_screen_door", "開 / 閉", "open", 1.25,
                    Driver.TICK_SEEK,
                    be -> ((com.trainsystemutilities.structure.blockentity
                            .PlatformScreenDoorBlockEntity) be).setOpen(true),
                    be -> ((com.trainsystemutilities.structure.blockentity
                            .PlatformScreenDoorBlockEntity) be).setOpen(false)),
            new Target("trainsystemutilities:ticket_gate", "開 / 閉", "open", 0.375,
                    Driver.TICK_SEEK,
                    be -> ((com.trainsystemutilities.structure.blockentity
                            .TicketGateBlockEntity) be).setOpen(true),
                    be -> ((com.trainsystemutilities.structure.blockentity
                            .TicketGateBlockEntity) be).setOpen(false)));

    // GeckoLib の内部フィールド。名前は 4.8.4 の bytecode で確認済み。
    private static Field fieldTickOffset;
    private static Field fieldLastPollTime;
    private static Field fieldAnimState;
    private static Object stateRunning;
    private static boolean reflectionOk = false;
    static {
        try {
            fieldTickOffset = AnimationController.class.getDeclaredField("tickOffset");
            fieldTickOffset.setAccessible(true);
            fieldLastPollTime = AnimationController.class.getDeclaredField("lastPollTime");
            fieldLastPollTime.setAccessible(true);
            fieldAnimState = AnimationController.class.getDeclaredField("animationState");
            fieldAnimState.setAccessible(true);
            Class<?> stateClass = Class.forName(
                    "software.bernie.geckolib.animation.AnimationController$State");
            for (Object c : stateClass.getEnumConstants()) {
                if (c.toString().equals("RUNNING")) stateRunning = c;
            }
            reflectionOk = stateRunning != null;
        } catch (Throwable t) {
            LOGGER.warn("[WikiItemAnim] GeckoLib reflection failed: {}", t.toString());
        }
    }

    /** @return 書き出せたアニメの数。失敗は errors に積む (sync 側が FATAL 判定)。 */
    static int exportAll(Minecraft mc, Path root, List<String> errors) {
        if (!reflectionOk) {
            errors.add("animation export : GeckoLib reflection unavailable");
            return 0;
        }
        int done = 0;
        for (Target t : TARGETS) {
            try {
                exportOne(mc, t, root);
                done++;
            } catch (Throwable e) {
                LOGGER.error("[WikiItemAnim] failed: {}", t.id(), e);
                errors.add(t.id() + " (anim) : " + e);
            }
        }
        LOGGER.info("[WikiItemAnim] exported {} animation(s)", done);
        return done;
    }

    private static void exportOne(Minecraft mc, Target target, Path root) throws Exception {
        ResourceLocation key = ResourceLocation.parse(target.id());
        Item item = BuiltInRegistries.ITEM.get(key);
        ItemStack stack = new ItemStack(item);
        if (stack.isEmpty()) throw new IllegalStateException("item not registered: " + target.id());
        Path dir = root.resolve(key.getNamespace() + "__" + key.getPath());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("mesh dir missing (mesh export first): " + dir);
        }
        int baseVertexCount = meshVertexCount(dir.resolve("mesh.json"));

        BlockEntity be = com.trainsystemutilities.client.GeoBlockItemRenderer.blockEntityFor(stack);
        if (!(be instanceof GeoBlockEntity geo)) {
            throw new IllegalStateException("not a GeoBlockEntity: " + target.id());
        }

        // RENDER_T の静止 T を先に測る。パンタの待機姿勢は T=0 とは限らず、
        // 実測では 0.083 だった (静止メッシュは必ずここで撮られている)。
        // 0..1 を撮ると frame0 が静止姿勢と 3.1 ずれ、「閉じても元に戻らない」になる。
        double restT = 0.0;
        if (target.driver() == Driver.RENDER_T) {
            WikiItemMeshExport.settle(mc, stack);
            restT = ((com.trainsystemutilities.electrification.blockentity
                    .PantographBlockEntity) be).getCurrentRenderT();
        }

        // frame 0 は「静止のまま撮った 1 枚」。静止メッシュと同じ状態・同じ手順で
        // 撮るので、両者が一致することが**定義**になる (計算で合わせにいかない)。
        // パンタは駆動値 T が 0 でも静止姿勢と一致せず、3.19 ずれていた —
        // 原因を推し量るより、同じ撮り方をするのが正しい (2026-08-29)。
        WikiItemMeshExport.settle(mc, stack);
        var restFrame = grab(mc, stack, baseVertexCount, 0);

        try {
            target.setMoved().accept(be);
            // 目的のアニメが選ばれるまで 1 度描いて controller を作らせる
            WikiItemMeshExport.recordFrame(mc, stack);
            AnimationController<?> ctrl = controllerFor(geo, be);

            List<float[]> positions = new ArrayList<>();
            List<float[]> normals = new ArrayList<>();
            List<Double> progress = new ArrayList<>(); // 0..1 の進捗 (単調増加)

            positions.add(restFrame.positions());
            normals.add(restFrame.normals());
            progress.add(0.0);

            if (target.driver() == Driver.TICK_SEEK) {
                for (int i = 1; i <= FRAMES; i++) {
                    double wantTicks = target.seconds() * 20.0 * i / FRAMES;
                    // 時計は controller のものを読む (絶対時刻を外から作らない)
                    double pollTime = fieldLastPollTime.getDouble(ctrl);
                    fieldTickOffset.setDouble(ctrl, pollTime - wantTicks);
                    // HOLD_ON_LAST_FRAME で PAUSED になっていると bone を触らない
                    fieldAnimState.set(ctrl, stateRunning);
                    var frame = grab(mc, stack, baseVertexCount, i);
                    positions.add(frame.positions());
                    normals.add(frame.normals());
                    double achievedTicks = fieldLastPollTime.getDouble(ctrl)
                            - fieldTickOffset.getDouble(ctrl);
                    progress.add(achievedTicks / (target.seconds() * 20.0));
                }
            } else {
                // RENDER_T: 展開状態のまま描き続け、T が 1 に収束するまでの実姿勢を拾う。
                // T は毎 poll 0.18 ずつ target へ寄るので 35 回ほどで到達する。
                // T を直接置く。ただし描画中に述語が
                //   cur = w + (target - w) * LERP   (target = 1: 展開)
                // と 1 度だけ寄せるので、望む T になるよう逆算した値を書く。
                // 書いた結果は毎フレーム読み戻して検証する (定数が変わったら赤)。
                var pantograph = (com.trainsystemutilities.electrification.blockentity
                        .PantographBlockEntity) be;
                Field tField = pantograph.getClass().getDeclaredField("currentRenderT");
                tField.setAccessible(true);
                final double lerp = 0.18; // PantographBlockEntity.T_LERP_RATE
                for (int i = 1; i <= FRAMES; i++) {
                    // 静止 T から終端まで — 「置いてある状態から展開しきるまで」
                    double want = restT + (1.0 - restT) * i / FRAMES;
                    // PantographGeoModel は T が動かないと「凍結値」を再適用する
                    // (setCustomAnimations の stable 分岐)。その凍結値は 1 レンダー
                    // 前のものなので、同じ T で描き続けても**前の T の姿勢に
                    // 収束する** — 実測で frame1 が常に T=0.18 になり、rms が
                    // 非単調になっていた (2026-08-29 レビュー F1 の残り)。
                    // わずかに違う T で 1 度描いて凍結を外し、目的の T で描いた
                    // 2 枚目を採る (差 0.0005 は姿勢では無視できる)。
                    // eps は述語の吸着窓 (0.001) をわずかに超える最小値。
                    //  - 0.0005 では最終コマで吸着され凍結が外れず、12 コマ目が
                    //    11/12 の姿勢のまま出ていた (2026-08-29 レビュー F1)。
                    //  - 大きくすると GeckoLib 側が eps ぶん先の姿勢で残る
                    //    (0.01 で 0.15 単位の残差を実測)。小さいほど良い。
                    double eps = 0.002;
                    tField.setFloat(pantograph,
                            (float) ((want + eps - lerp) / (1.0 - lerp)));
                    WikiItemMeshExport.recordFrame(mc, stack);
                    tField.setFloat(pantograph, (float) ((want - lerp) / (1.0 - lerp)));
                    var frame = grab(mc, stack, baseVertexCount, i);
                    double got = pantograph.getCurrentRenderT();
                    if (Math.abs(got - want) > 0.01) {
                        throw new IllegalStateException(String.format(Locale.ROOT,
                                "render-T compensation is wrong at frame %d: wanted %.3f, got %.3f"
                                + " (did T_LERP_RATE change?)", i, want, got));
                    }
                    positions.add(frame.positions());
                    normals.add(frame.normals());
                    progress.add(want);
                }
            }

            // 動く頂点だけを持つ (ホームドアは筐体が大半で、全頂点を持つと無駄)
            boolean[] moving = new boolean[baseVertexCount];
            int movingCount = 0;
            float maxDelta = 0;
            for (int v = 0; v < baseVertexCount; v++) {
                float d = 0;
                for (float[] pos : positions) {
                    for (int k = 0; k < 3; k++) {
                        d = Math.max(d, Math.abs(pos[v * 3 + k] - positions.get(0)[v * 3 + k]));
                    }
                }
                maxDelta = Math.max(maxDelta, d);
                if (d > MOVE_EPSILON) { moving[v] = true; movingCount++; }
            }
            if (movingCount == 0) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "animation did not move (max delta %.5f) — seek or state failed", maxDelta));
            }
            // 端点だけ見ると、途中が凍った列を通してしまう (レビュー F4)
            for (int i = 1; i < positions.size(); i++) {
                if (frameDelta(positions.get(i - 1), positions.get(i)) <= MOVE_EPSILON) {
                    throw new IllegalStateException("frames " + (i - 1) + " and " + i
                            + " are identical — the seek is not advancing");
                }
            }
            boolean normalsMove = false;
            for (float[] nrm : normals) {
                if (frameDelta(normals.get(0), nrm) > MOVE_EPSILON) { normalsMove = true; break; }
            }

            // 終端の検査: 目標状態のまま数回描いて収束させた姿勢と、最終フレームが
            // 一致すること。1 フレーム遅れや途中打ち切りをここで捕まえる
            // (実測で最終フレームが T=11/12 で止まっていた。2026-08-29 レビュー F1)。
            for (int i = 0; i < 6; i++) WikiItemMeshExport.recordFrame(mc, stack);
            // 比較相手は「ゲーム自身が収束した姿勢」。ただし RENDER_T では
            // **一度 T を離してから**戻さないと円環になる: 最終 grab で
            // frozenT=1.0 が書かれるため、無書込レンダーは freeze の stable 分岐で
            // 最後のコマをそのまま再生し、endGap が常に 0 になる
            // (2026-08-29 レビュー指摘 1 — このゲートは発火しようがなかった)。
            // 0.97 へ離し、以後は**何も書かずに** lerp が 1 へ戻るのを待つ。
            if (target.driver() == Driver.RENDER_T) {
                var pantograph3 = (com.trainsystemutilities.electrification.blockentity
                        .PantographBlockEntity) be;
                java.lang.reflect.Field tf3 =
                        pantograph3.getClass().getDeclaredField("currentRenderT");
                tf3.setAccessible(true);
                tf3.setFloat(pantograph3, 0.97f);
                for (int i = 0; i < 30; i++) WikiItemMeshExport.recordFrame(mc, stack);
                if (pantograph3.getCurrentRenderT() < 0.999f) {
                    throw new IllegalStateException("reference pose did not converge (T="
                            + pantograph3.getCurrentRenderT() + ")");
                }
            } else {
                for (int i = 0; i < 10; i++) WikiItemMeshExport.recordFrame(mc, stack);
            }
            var converged = grab(mc, stack, baseVertexCount, FRAMES);
            float endGap = 0;
            for (int v = 0; v < baseVertexCount; v++) {
                if (!moving[v]) continue;
                for (int k = 0; k < 3; k++) {
                    endGap = Math.max(endGap, Math.abs(
                            converged.positions()[v * 3 + k]
                            - positions.get(positions.size() - 1)[v * 3 + k]));
                }
            }
            // 0.05 に戻す。0.15 は ticket_gate の「1 コマ欠落」信号 (0.111) を
            // 覆っており、その対象では終端検査が無効だった (レビュー指摘 2)。
            // eps 残差は終端では飽和して現れない (実測 -0.00012)。
            if (endGap > 0.05) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "last frame is %.3f away from the settled end pose — the capture stops"
                        + " short of the animation's end", endGap));
            }

            // フレームから測った進み具合 (frame0 からの RMS)。書いた値の写しではなく
            // 撮れた頂点から出すので、動きが単調に伸びていることを本当に検査できる。
            StringBuilder rms = new StringBuilder();
            for (int i = 0; i < positions.size(); i++) {
                double acc = 0;
                int n = 0;
                for (int v = 0; v < baseVertexCount; v++) {
                    if (!moving[v]) continue;
                    for (int k = 0; k < 3; k++) {
                        double d = positions.get(i)[v * 3 + k] - positions.get(0)[v * 3 + k];
                        acc += d * d;
                        n++;
                    }
                }
                if (i > 0) rms.append(',');
                rms.append(f(Math.sqrt(acc / Math.max(1, n))));
            }

            StringBuilder json = new StringBuilder(1 << 18);
            json.append("{\"v\":1,\"id\":\"").append(target.id())
                .append("\",\"name\":\"").append(target.animation())
                .append("\",\"label\":\"").append(target.label())
                .append("\",\"seconds\":").append(f(target.seconds()))
                .append(",\"frameCount\":").append(positions.size())
                .append(",\"baseVertexCount\":").append(baseVertexCount)
                .append(",\"movingCount\":").append(movingCount)
                .append(",\"maxDelta\":").append(f(maxDelta))
                .append(",\"normalsMove\":").append(normalsMove)
                .append(",\"driver\":\"").append(target.driver()).append('"')
                .append(",\"rms\":[").append(rms).append(']')
                .append(",\"progress\":[");
            for (int i = 0; i < progress.size(); i++) {
                if (i > 0) json.append(',');
                json.append(f(progress.get(i)));
            }
            json.append("],\"index\":[");
            boolean first = true;
            for (int v = 0; v < baseVertexCount; v++) {
                if (!moving[v]) continue;
                if (!first) json.append(',');
                first = false;
                json.append(v);
            }
            json.append("],\"frames\":[");
            for (int i = 0; i < positions.size(); i++) {
                if (i > 0) json.append(',');
                json.append("{\"pos\":[").append(packed(positions.get(i), moving));
                if (normalsMove) json.append("],\"nrm\":[").append(packed(normals.get(i), moving));
                json.append("]}");
            }
            json.append("]}");
            // 静止メッシュと frame 0 が一致することを、書き出す前に検査する。
            // これが崩れると web 側は「閉じても元の姿勢に戻らない」になる —
            // 実際に 2 回踏んだ (ホームドアの待機姿勢・パンタの終端送り)。
            double restGap = maxGapToMesh(dir.resolve("mesh.json"), moving, positions.get(0));
            if (restGap > 0.05) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "frame 0 is %.3f away from the static mesh — closing would not return"
                        + " to the resting pose", restGap));
            }
            Files.writeString(dir.resolve("anim.json"), json.toString());
            LOGGER.info("[WikiItemAnim] {}: {} frames, {}/{} verts move, maxDelta {}, "
                    + "progress {}..{}", target.id(), positions.size(), movingCount,
                    baseVertexCount, maxDelta, progress.get(0),
                    progress.get(progress.size() - 1));
        } finally {
            // 状態を戻す — この BE はアイコン / base mesh / インベントリ描画と同一
            // インスタンスなので、開いたままだと次回の capture が開状態で撮れる
            try {
                target.restore().accept(be);
                WikiItemMeshExport.recordFrame(mc, stack); // 復帰を controller にも反映
            } catch (Throwable t) {
                LOGGER.warn("[WikiItemAnim] state restore failed for {}: {}", target.id(), t.toString());
            }
        }
    }

    /** GeckoLib のアニメを終端へ送る (HOLD_ON_LAST_FRAME で待機姿勢に落ちる)。
     *  アニメを持たない / controller が無いアイテムでは何もしない。 */
    static void seekToEnd(ItemStack stack) {
        if (!reflectionOk) return;
        try {
            // RENDER_T のモデル (パンタ) は GeckoLib の出力を一部の骨しか上書き
            // しないので、終端へ送ると「上書きされない骨だけ展開姿勢」という
            // 混ざった姿勢になる (実測: 静止メッシュが T=0 の姿勢と 3.1 ずれた)。
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            for (Target t : TARGETS) {
                if (t.id().equals(id) && t.driver() == Driver.RENDER_T) return;
            }
            var be = com.trainsystemutilities.client.GeoBlockItemRenderer.blockEntityFor(stack);
            if (!(be instanceof GeoBlockEntity geo)) return;
            var manager = geo.getAnimatableInstanceCache()
                    .getManagerForId(be.getBlockPos().hashCode());
            for (AnimationController<?> ctrl : manager.getAnimationControllers().values()) {
                double pollTime = fieldLastPollTime.getDouble(ctrl);
                fieldTickOffset.setDouble(ctrl, pollTime - 1000.0); // 終端より十分先
                fieldAnimState.set(ctrl, stateRunning);
            }
        } catch (Throwable ignored) {
            // 待機姿勢に落とせなくても静止画は撮れる (以前と同じ挙動)
        }
    }

    /** 1 フレーム記録して、空・頂点数不一致を弾く。 */
    private static WikiItemMeshExport.Frame grab(Minecraft mc, ItemStack stack,
            int baseVertexCount, int i) {
        var frame = WikiItemMeshExport.recordFrame(mc, stack);
        if (frame.positions().length == 0) {
            throw new IllegalStateException("render produced no vertices at frame " + i
                    + " (the item renderer swallowed an exception — see the log above)");
        }
        if (frame.positions().length / 3 != baseVertexCount) {
            throw new IllegalStateException("vertex count " + frame.positions().length / 3
                    + " != base mesh " + baseVertexCount + " at frame " + i);
        }
        return frame;
    }

    /** 静止メッシュと、動く頂点だけを持つフレームとの最大差。 */
    private static double maxGapToMesh(Path meshJson, boolean[] moving, float[] frame)
            throws Exception {
        String text = Files.readString(meshJson);
        List<Float> base = new ArrayList<>();
        int at = 0;
        while ((at = text.indexOf("\"pos\":[", at)) >= 0) {
            int end = text.indexOf(']', at);
            for (String v : text.substring(at + 7, end).split(",")) {
                if (!v.isBlank()) base.add(Float.parseFloat(v.trim()));
            }
            at = end;
        }
        double max = 0;
        for (int v = 0; v < moving.length; v++) {
            if (!moving[v]) continue;
            for (int k = 0; k < 3; k++) {
                max = Math.max(max, Math.abs(base.get(v * 3 + k) - frame[v * 3 + k]));
            }
        }
        return max;
    }

    /** base mesh の頂点総数 (ビューワが 1:1 で重ねる先)。 */
    private static int meshVertexCount(Path meshJson) throws Exception {
        String text = Files.readString(meshJson);
        int total = 0;
        int at = 0;
        while ((at = text.indexOf("\"pos\":[", at)) >= 0) {
            int end = text.indexOf(']', at);
            String body = text.substring(at + 7, end);
            if (!body.isBlank()) total += (body.split(",").length) / 3;
            at = end;
        }
        if (total == 0) throw new IllegalStateException("no vertices in " + meshJson);
        return total;
    }

    /** 描画に使われているのと同じ AnimationController。インスタンス ID は
     *  {@code BlockPos.hashCode()} — GeoBlockRenderer がそう呼ぶ (bytecode 実読)。
     *  別の値だと空のマネージャが作られ、seek しても描画側は動かない。 */
    private static AnimationController<?> controllerFor(GeoBlockEntity geo, BlockEntity be) {
        var manager = geo.getAnimatableInstanceCache()
                .getManagerForId(be.getBlockPos().hashCode());
        var controllers = manager.getAnimationControllers();
        if (controllers.isEmpty()) {
            throw new IllegalStateException("no animation controller for the rendered instance"
                    + " (instance id " + be.getBlockPos().hashCode() + ")");
        }
        return controllers.values().iterator().next();
    }

    private static float frameDelta(float[] a, float[] b) {
        float max = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    /** 動く頂点だけを 3 成分ずつ並べる。 */
    private static String packed(float[] values, boolean[] moving) {
        StringBuilder sb = new StringBuilder(moving.length * 6);
        boolean first = true;
        for (int v = 0; v < moving.length; v++) {
            if (!moving[v]) continue;
            for (int k = 0; k < 3; k++) {
                if (!first) sb.append(',');
                first = false;
                sb.append(f(values[v * 3 + k]));
            }
        }
        return sb.toString();
    }

    private static String f(double d) {
        String s = String.format(Locale.ROOT, "%.3f", d);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() || s.equals("-0") ? "0" : s;
    }
}
