package com.trainsystemutilities.electrification;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationTicker;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 電化列車の駆動ゲート (Phase D4)。
 *
 * <p>毎 tick、登録済みの全電化 contraption をスキャンし、FE バッファが空のものを発見したら
 * 該当する Train の {@code speed} と {@code targetSpeed} を 0 にして停止させる。
 *
 * <p>非電化列車 (= インバータまたはパンタが無い contraption) は無視 — Create 純正のままで動く。
 *
 * <p>速度制御は ON/OFF の二値のみ (= 残量に応じた段階的減速はしない)。設計時の合意通り。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class ElectrificationTrainGate {

    private ElectrificationTrainGate() {}

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        // 列車単位で「電化列車」かつ「列車全体の FE = 0」を判定 → 強制停止。
        // 旧仕様は contraption 単位で判定していたため、車両ごとに panto+inverter 両方ある
        // 構成しか考慮できなかった。列車プール方式に変更し、
        //   - 1 車両にパンタ・別車両にインバータ
        //   - 複数のインバータ車両がバッファとして連動
        // という構成にも対応する。
        Set<UUID> gatedTrains = new HashSet<>();
        // P0-1 #9: snapshot iteration で server tick 中の Create CME を排除
        for (Train train : com.trainsystemutilities.compat.CreateBridge.snapshotTrains()) {
            if (train == null || train.carriages == null) continue;
            // 電化判定 + FE 合計を 1 回の carriage 走査で取得 (= 旧 2 回走査を統合)
            var status = ContraptionElectrificationTicker.trainElectricStatus(train);
            if (!status.electrified()) continue;
            if (status.totalStoredEnergy() > 0) continue;
            if (!gatedTrains.add(train.id)) continue;
            // FE 不足 → 強制停止
            train.speed = 0;
            train.targetSpeed = 0;
        }
    }
}
