package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.blockentity.PantographBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Geckolib モデル定義: パンタグラフ (シングルアーム) の geometry / animation / texture を結びつける。
 *
 * <p>S2: 全ボーンの展開度合いを連続パラメータ T (0.0=折畳, 1.0=完全展開) で駆動。
 * 新モデル (集電版 pivot=35, アーム上/アーム下/リンク上/リンク下) のアニメ keyframe を
 * Java 側でハードコード piecewise lerp。setCustomAnimations が T → animation_time に
 * マップして全ボーン同期で動かす。
 *
 * <p>T の決定:
 * <ul>
 *   <li>パンタ折畳: T = 0</li>
 *   <li>パンタ展開・非接触: T = 1</li>
 *   <li>パンタ展開・接触中: T = (8 + 16*barOffsetY) / 27 (= 集電版 TOP が架線 Y にちょうど触れる)</li>
 * </ul>
 */
public class PantographGeoModel extends GeoModel<PantographBlockEntity> {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "geo/pantograph_singlearm.geo.json");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "animations/pantograph_singlearm.animation.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            TrainSystemUtilities.MOD_ID, "textures/block/pantograph_singlearm.png");

    // BlockBench で命名された bone 名 (UTF-8 日本語、新モデル)。
    private static final String BONE_COLLECTOR    = "集電版";
    private static final String BONE_ARM_UPPER    = "アーム上";
    private static final String BONE_ARM_LOWER    = "アーム下";
    private static final String BONE_LINK_UPPER   = "リンク上";
    private static final String BONE_LINK_LOWER   = "リンク下";

    /** アニメ全体の長さ (fold / unfold は両方 0.375s)。 */
    private static final float ANIM_LENGTH = 0.375f;

    /** T が target に近づく速さ。 */
    private static final float T_LERP_RATE = 0.18f;

    // ===== fold アニメの keyframes (= 折畳 t=0 → 展開 t=0.375) =====
    //
    // 設計: アニメ JSON の bone.rotation / position / scale を抜き出してそのままハードコード。
    // ハードコードした方が JSON パースのオーバーヘッドなし + コードレビューで値が見える利点。

    /** (time, value) のペア型 (float 1 次元用)。 */
    private record KF1(float t, float v) {}
    /** (time, x, y, z) ペア型 (3 次元用)。 */
    private record KF3(float t, float x, float y, float z) {}

    // --- アーム上 ---
    private static final KF1[] ARM_UPPER_ROT_Z = {
            new KF1(0.000f, -62.5f),
            new KF1(0.375f,  0.0f),
    };
    // 中間 keyframe あり (fold)
    private static final KF1[] ARM_LOWER_ROT_Z = {
            new KF1(0.0000f, 52.5f),
            new KF1(0.2500f, 21.4f),
            new KF1(0.2917f, 14.17f),
            new KF1(0.3750f, 0.0f),
    };
    private static final KF3[] ARM_LOWER_POS = {
            new KF3(0.0000f, -9.0f,  -12.0f,  0f),
            new KF3(0.1667f, -6.17f, -5.9f,   0f),
            new KF3(0.2500f, -4.75f, -3.14f,  0f),
            new KF3(0.2917f, -2.88f, -1.66f,  0f),
            new KF3(0.3333f, -2.0f,  -1.33f,  0f),
            new KF3(0.3750f,  0f,     0f,     0f),
    };
    private static final float ARM_LOWER_SCALE_X = 0.9f;
    private static final float ARM_LOWER_SCALE_Y = 1.0f;

    // --- 集電版 ---
    private static final KF3[] COLLECTOR_POS = {
            new KF3(0.0000f, 0f, -29.0f, 0f),
            new KF3(0.3750f, 0f,  -2.0f, 0f),
    };

    // --- リンク下 ---
    private static final KF1[] LINK_LOWER_ROT_Z = {
            new KF1(0.0000f, -77.5f),
            new KF1(0.0833f, -63.04f),
            new KF1(0.2500f, -31.61f),
            new KF1(0.2917f, -18.54f),
            new KF1(0.3333f, -13.33f),
            new KF1(0.3750f,  0.0f),
    };
    private static final KF1[] LINK_LOWER_SCALE_Y = {
            new KF1(0.0000f, 1.2f),
            new KF1(0.0833f, 1.1f),
            new KF1(0.2500f, 1.0f),
            new KF1(0.3750f, 1.0f),
    };

    // --- リンク上 (3 軸回転あり) ---
    private static final KF3[] LINK_UPPER_ROT = {
            new KF3(0.0000f, -2.4575f, -0.6518f, 42.5282f),
            new KF3(0.1250f, -1.4148f, -0.3086f, 30.3593f),
            new KF3(0.2917f,  0f,       0f,       8.3f),
            new KF3(0.3333f,  0f,       0f,       0.56f),
            new KF3(0.3750f,  0f,       0f,      -5.0f),
    };
    private static final KF3[] LINK_UPPER_POS = {
            new KF3(0.0000f, -9.0f,  -11.0f, 0f),
            new KF3(0.2917f, -2.88f, -1.44f, 0f),
            new KF3(0.3333f, -2.0f,  -1.22f, 0f),
            new KF3(0.3750f,  0f,     0f,    0f),
    };
    private static final float LINK_UPPER_SCALE_X = 1.0f;
    private static final float LINK_UPPER_SCALE_Y = 0.8f;

    @Override
    public boolean crashIfBoneMissing() { return false; }

    @Override
    public ResourceLocation getModelResource(PantographBlockEntity e) { return MODEL; }

    @Override
    public ResourceLocation getAnimationResource(PantographBlockEntity e) { return ANIMATION; }

    @Override
    public ResourceLocation getTextureResource(PantographBlockEntity e) { return TEXTURE; }

    // ===== freeze-on-stable: T が安定したら bones を凍結して wobble を消す =====
    //
    // 原因: BE.idlePredicate が tickOffset を書き換えて Geckolib に「desiredTicks 時点の
    // pose を評価せよ」と指示するが、tickOffset の write は **次フレームの adjustedTick**
    // にしか効かないため、deltaEma 予測誤差 (= 実 delta との差) によって adjustedTick が
    // ±0.05 ticks 程度ブレる ⇒ bones の rotation/position が微振動して見える (= 「プルプル震え」)。
    //
    // 対策: T が変化していない (= |currentT - frozenT| < 0.0001) ときに、最初の 1 フレームで
    // 評価された bone 値をキャプチャし、以降そのフレーム値で上書きし続ける。
    // → Geckolib が毎フレーム微妙に違う値を書いても、我々が直後に同じ値で上書きするので
    //   render される pose は完全に固定。wobble 消失。
    // T が変化したら再キャプチャ (= 新しい T の pose を取得)。
    private static class FrozenState {
        float frozenT = Float.NaN;
        final Map<String, float[]> values = new HashMap<>();
    }
    private final Map<Long, FrozenState> frozenStates = new HashMap<>();

    @Override
    public void setCustomAnimations(PantographBlockEntity be, long instanceId,
                                     AnimationState<PantographBlockEntity> state) {
        super.setCustomAnimations(be, instanceId, state);

        FrozenState fs = frozenStates.computeIfAbsent(instanceId, k -> new FrozenState());
        float curT = be.getCurrentRenderT();
        boolean stable = !Float.isNaN(fs.frozenT) && Math.abs(curT - fs.frozenT) < 0.0001f;

        if (stable && !fs.values.isEmpty()) {
            // 凍結値を bones に再適用 (= Geckolib processCurrentAnimation の wobble を上書き)
            for (Map.Entry<String, float[]> entry : fs.values.entrySet()) {
                GeoBone bone = getAnimationProcessor().getBone(entry.getKey());
                if (bone == null) continue;
                float[] v = entry.getValue();
                bone.setRotX(v[0]);
                bone.setRotY(v[1]);
                bone.setRotZ(v[2]);
                bone.setPosX(v[3]);
                bone.setPosY(v[4]);
                bone.setPosZ(v[5]);
                bone.setScaleX(v[6]);
                bone.setScaleY(v[7]);
                bone.setScaleZ(v[8]);
            }
        } else {
            // T が変わった (or 初回) → 今 frame の bone 値をキャプチャして次 frame 以降の凍結用にする
            fs.values.clear();
            for (GeoBone bone : getAnimationProcessor().getRegisteredBones()) {
                fs.values.put(bone.getName(), new float[]{
                        bone.getRotX(), bone.getRotY(), bone.getRotZ(),
                        bone.getPosX(), bone.getPosY(), bone.getPosZ(),
                        bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()
                });
            }
            fs.frozenT = curT;
        }
    }

    /** クライアント共有キャッシュ ({@link ClientPantographContactState}) を見て展開度を決定。
     *  キャッシュにエントリがない場合 (= world 単体ブロック) は BE のローカル deployed を見る。 */
    private static float computeTargetT(PantographBlockEntity be) {
        ClientPantographContactState.Entry entry =
                ClientPantographContactState.get(be.getBlockPos());
        if (entry == ClientPantographContactState.Entry.NONE) {
            return be.isDeployed() ? 1.0f : 0.0f;
        }
        if (!entry.deployed()) return 0.0f;
        if (!entry.inContact()) return 1.0f;
        // 接触中: 集電版 TOP (= local y=37) が架線 Y にちょうど触れる T を逆算。
        //   pos.y(T) = -29 + 27*T (= folded -29 → extended -2 の線形補間)
        //   collector_top_world_y = block_y + (37 + pos.y) / 16
        //                         = block_y + (8 + 27*T) / 16
        //   = pickup_y + barOffsetY = block_y + 1.0 + barOffsetY
        //   ↔   T = (8 + 16*barOffsetY) / 27
        // T > 1 では setCustomAnimations 側で 集電版 を追加 Y シフトして外挿。
        float T = (8f + 16f * entry.barOffsetY()) / 27f;
        return Math.max(0f, T);
    }

    // ===== keyframe lerp ヘルパ =====

    /** 単一軸 keyframe 配列を time で piecewise linear 評価。
     *  time が範囲外なら端点を使う。 */
    private static float lerpKF1(float time, KF1[] kfs) {
        if (kfs.length == 0) return 0f;
        if (time <= kfs[0].t) return kfs[0].v;
        if (time >= kfs[kfs.length - 1].t) return kfs[kfs.length - 1].v;
        for (int i = 0; i < kfs.length - 1; i++) {
            if (time >= kfs[i].t && time <= kfs[i + 1].t) {
                float dt = kfs[i + 1].t - kfs[i].t;
                if (dt < 1e-6f) return kfs[i].v;
                float frac = (time - kfs[i].t) / dt;
                return kfs[i].v + (kfs[i + 1].v - kfs[i].v) * frac;
            }
        }
        return kfs[0].v;
    }

    /** 3 軸 keyframe 配列を time で評価 → float[3] (x,y,z)。 */
    private static float[] lerpKF3(float time, KF3[] kfs) {
        if (kfs.length == 0) return new float[]{0f, 0f, 0f};
        if (time <= kfs[0].t) return new float[]{kfs[0].x, kfs[0].y, kfs[0].z};
        if (time >= kfs[kfs.length - 1].t) {
            KF3 last = kfs[kfs.length - 1];
            return new float[]{last.x, last.y, last.z};
        }
        for (int i = 0; i < kfs.length - 1; i++) {
            if (time >= kfs[i].t && time <= kfs[i + 1].t) {
                float dt = kfs[i + 1].t - kfs[i].t;
                if (dt < 1e-6f) return new float[]{kfs[i].x, kfs[i].y, kfs[i].z};
                float frac = (time - kfs[i].t) / dt;
                return new float[]{
                        kfs[i].x + (kfs[i + 1].x - kfs[i].x) * frac,
                        kfs[i].y + (kfs[i + 1].y - kfs[i].y) * frac,
                        kfs[i].z + (kfs[i + 1].z - kfs[i].z) * frac,
                };
            }
        }
        return new float[]{kfs[0].x, kfs[0].y, kfs[0].z};
    }

    private static float toRad(float deg) {
        return (float) Math.toRadians(deg);
    }
}
