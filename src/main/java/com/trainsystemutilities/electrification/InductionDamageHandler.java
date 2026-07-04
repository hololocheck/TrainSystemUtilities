package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.wire.EnergizedWireState;
import com.trainsystemutilities.electrification.wire.NodePair;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireNetworkSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * 通電中の架線から {@link ElectrificationConstants#INDUCTION_RANGE} 以内のエンティティに
 * 誘導感電ダメージを与える tick handler。
 *
 * <p>頻度: {@link ElectrificationConstants#INDUCTION_TICK_INTERVAL} tick (= 0.5s) ごと。
 * ダメージ: {@link ElectrificationConstants#INDUCTION_DAMAGE} HP / 判定。
 *
 * <p>判定は架線を直線セグメントとして近似 (= サグは無視)。サグは ~5% なので 1m 範囲では誤差 0.05m
 * 以内で実用上問題ない。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class InductionDamageHandler {

    private InductionDamageHandler() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long gameTime = event.getServer().overworld().getGameTime();
        if (gameTime % ElectrificationConstants.INDUCTION_TICK_INTERVAL != 0) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            applyForLevel(level);
        }
    }

    private static void applyForLevel(ServerLevel level) {
        WireNetworkSavedData data = WireNetworkSavedData.get(level);
        if (data.size() == 0) return;
        EnergizedWireState energized = EnergizedWireState.get(level);
        if (energized.size() == 0) return;

        DamageSource src = level.damageSources().source(ModDamageTypes.INDUCTION);
        double range = ElectrificationConstants.INDUCTION_RANGE;
        double rangeSq = range * range;

        // 通電中の接続だけを直接反復する (= 全 wire を走査して isEnergized で毎回 NodePair を
        // 確保していたのを回避。 非通電 wire が多いほど効く)。
        for (NodePair pair : energized.snapshot()) {
            WireConnection c = data.get(pair.a(), pair.b());
            if (c == null) continue; // 既に削除された接続 (= energized snapshot との競合)

            Vec3 a = c.attachA();
            Vec3 b = c.attachB();
            AABB area = new AABB(a, b).inflate(range);

            // 近傍の生物だけを取得 (= chunk 空間ハッシュで内部高速)
            List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, area);
            for (LivingEntity e : entities) {
                if (!e.isAlive()) continue;
                if (e.isInvulnerable()) continue;

                // 体の複数点 (= 足/中央/目) のうち最も近い距離で判定。
                // 1 点だけだと、長身モブで体の一部しか架線に近くない場合に当たり判定が消える。
                double minDistSq = pointSegDistSq(e.position(), a, b);
                Vec3 center = e.getBoundingBox().getCenter();
                double d2 = pointSegDistSq(center, a, b);
                if (d2 < minDistSq) minDistSq = d2;
                Vec3 eye = e.getEyePosition();
                d2 = pointSegDistSq(eye, a, b);
                if (d2 < minDistSq) minDistSq = d2;

                if (minDistSq <= rangeSq) {
                    e.hurt(src, ElectrificationConstants.INDUCTION_DAMAGE);
                }
            }
        }
    }

    /** 点 P と線分 AB の距離の二乗を返す。 */
    private static double pointSegDistSq(Vec3 p, Vec3 a, Vec3 b) {
        double abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        double apx = p.x - a.x, apy = p.y - a.y, apz = p.z - a.z;
        double abLen2 = abx * abx + aby * aby + abz * abz;
        double t;
        if (abLen2 < 1e-9) {
            t = 0;
        } else {
            t = (apx * abx + apy * aby + apz * abz) / abLen2;
            if (t < 0) t = 0;
            else if (t > 1) t = 1;
        }
        double cx = a.x + abx * t;
        double cy = a.y + aby * t;
        double cz = a.z + abz * t;
        double dx = p.x - cx, dy = p.y - cy, dz = p.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }
}
