package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;

import java.util.UUID;

/**
 * 管理コンピュータからの列車運行 停止 / 再開 制御 — the management computer host's {@code control} action
 * ({@link ManagementComputerData}; ManagementComputerControlPayload until MANTA_7_CONCEPT C4).
 *
 * <p>B2 (MP desync 修正): 以前は {@code ManagementComputerScreenV2} が client 側で
 * {@code train.runtime.paused} を直接 mutate していたため SP でしか動作せず MP では desync した。
 * server 権威に統一し、{@link ManagementComputerBlockEntity} の既存 server ロジック
 * ({@code startAllTrainsStop} / {@code resumeAllTrains} / {@code emergencyStop} / {@code resumeTrain})
 * を呼ぶ。 操作者の距離と権限は host の canView が action ごとに確かめる。
 *
 * <p>action:
 * <ul>
 *   <li>{@link #ACTION_STOP_ALL} — 全列車を順次停止 (server の 1 台ずつ方式)</li>
 *   <li>{@link #ACTION_RESUME_ALL} — 全列車を再開</li>
 *   <li>{@link #ACTION_TOGGLE_ONE} — {@code trainId} の列車の停止 / 再開をトグル</li>
 * </ul>
 */
public final class ManagementComputerControlAction {

    public static final int ACTION_STOP_ALL = 0;
    public static final int ACTION_RESUME_ALL = 1;
    public static final int ACTION_TOGGLE_ONE = 2;

    private ManagementComputerControlAction() {}

    public static void apply(ManagementComputerBlockEntity mc, int action, UUID trainId) {
        switch (action) {
            case ACTION_STOP_ALL -> mc.startAllTrainsStop();
            case ACTION_RESUME_ALL -> mc.resumeAllTrains();
            case ACTION_TOGGLE_ONE -> {
                // SECURITY (TSU-NET-002): 対象列車がこの computer の linked network に属すことを検証。
                if (!mc.containsLinkedTrain(trainId)) {
                    TrainSystemUtilities.LOGGER.debug(
                            "ManagementComputerControl: train {} not in linked network of computer {} (rejected)",
                            trainId, mc.getBlockPos());
                    return;
                }
                Train train = Create.RAILWAYS.trains.get(trainId);
                if (train != null && train.runtime != null) {
                    if (train.runtime.paused) {
                        mc.resumeTrain(trainId);
                    } else {
                        mc.emergencyStop(trainId);
                    }
                }
            }
            default -> { /* action は宣言で 0..2 */ }
        }
    }
}
