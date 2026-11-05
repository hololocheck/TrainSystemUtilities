package com.trainsystemutilities.network;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationState;
import com.trainsystemutilities.electrification.contraption.ContraptionElectrificationTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 電化列車のパンタグラフ展開状態切替 — the player's train host's {@code pantograph} action ({@link TrainData};
 * PantographTogglePayload until MANTA_7_CONCEPT C4). The body is the payload's handler; where it broadcast the
 * electrification snapshot it publishes the server-wide host ({@link ElectrificationData}).
 *
 * <p>action:
 * <ul>
 *   <li>{@link #ACTION_DEPLOY_ALL} — 列車内の全パンタを展開</li>
 *   <li>{@link #ACTION_FOLD_ALL} — 列車内の全パンタを折りたたみ</li>
 *   <li>{@link #ACTION_TOGGLE_ONE} — 指定 carriage の指定 localPos のパンタをトグル</li>
 * </ul>
 *
 * <p>carriageIndex / pos は TOGGLE_ONE 専用 (他では無視)。
 */
public final class PantographAction {

    public static final int ACTION_DEPLOY_ALL = 0;
    public static final int ACTION_FOLD_ALL = 1;
    public static final int ACTION_TOGGLE_ONE = 2;

    private PantographAction() {}

    public static void apply(ServerPlayer sp, UUID trainId, int action, int carriageIndex, BlockPos pos) {
        TrainSystemUtilities.LOGGER.debug(
                "[PantoToggle-DEBUG] SERVER handle received action={} trainId={} car={} pos={} player={}",
                action, trainId, carriageIndex, pos, sp.getName().getString());

        Train train = Create.RAILWAYS.trains.get(trainId);
        if (train == null) {
            TrainSystemUtilities.LOGGER.warn(
                    "[PantoToggle-DEBUG] SERVER: train {} not found in Create.RAILWAYS.trains (size={})",
                    trainId, Create.RAILWAYS.trains.size());
            sp.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("tsu.pantograph.train_not_found")
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    true);
            return;
        }
        TrainSystemUtilities.LOGGER.debug(
                "[PantoToggle-DEBUG] SERVER: train={} carriages={}",
                train.name.getString(), train.carriages.size());

        // B2: proximity gate — 操作者は列車のいずれかの車両の近くにいること
        // (= GUI を開かず任意 trainId へ packet を投げる remote spoof を防ぐ)。
        if (!isNearTrain(sp, train)) {
            TrainSystemUtilities.LOGGER.debug(
                    "[PantoToggle] {} not near train {}", sp.getName().getString(), trainId);
            return;
        }

        int totalToggled = 0;
        int cIdx = 0;
        // トグル対象になった contraption を記録 → broadcast 直前に接触判定を再計算
        // (これがないと client が一瞬 entry.inContact=false を見て panto が全開してから調整に入る)
        java.util.List<Contraption> touchedContraptions = new java.util.ArrayList<>();
        for (Carriage carriage : train.carriages) {
            if (carriage == null) { cIdx++; continue; }
            Contraption c1 = extractContraption(carriage);
            if (c1 == null) {
                TrainSystemUtilities.LOGGER.debug(
                        "[PantoToggle-DEBUG] SERVER: car {} has no Contraption (carriage entity not loaded?)", cIdx);
                cIdx++;
                continue;
            }
            ContraptionElectrificationState.Info info = ContraptionElectrificationState.getInfo(c1);
            // パンタ展開は機能版 / 装飾版いずれかのインバータがあれば許可
            // (装飾モード列車でも UI からパンタ操作できるようにする)
            if (info == null || info.pantographs.isEmpty() || !info.hasAnyInverterType()) {
                TrainSystemUtilities.LOGGER.debug(
                        "[PantoToggle-DEBUG] SERVER: car {} info={} pantos={} anyInverter={}",
                        cIdx, info,
                        info == null ? "-" : info.pantographs.size(),
                        info != null && info.hasAnyInverterType());
                cIdx++;
                continue;
            }
            TrainSystemUtilities.LOGGER.debug(
                    "[PantoToggle-DEBUG] SERVER: car {} BEFORE: pantographs={} deployed={} inverters={}",
                    cIdx, info.pantographs, info.deployedPantographs, info.inverters);

            boolean changedThisCar = false;
            switch (action) {
                case ACTION_DEPLOY_ALL -> {
                    int before = info.deployedPantographs.size();
                    info.setAllPantographsDeployed(true);
                    int delta = info.deployedPantographs.size() - before;
                    totalToggled += delta;
                    if (delta > 0) changedThisCar = true;
                    // BE 側 NBT + 実体の deployed フィールドも同期
                    for (BlockPos pp : info.pantographs) {
                        syncDeployToContraptionBE(c1, pp, true);
                    }
                }
                case ACTION_FOLD_ALL -> {
                    int before = info.deployedPantographs.size();
                    info.setAllPantographsDeployed(false);
                    totalToggled += before;
                    if (before > 0) changedThisCar = true;
                    for (BlockPos pp : info.pantographs) {
                        syncDeployToContraptionBE(c1, pp, false);
                    }
                }
                case ACTION_TOGGLE_ONE -> {
                    if (cIdx == carriageIndex && pos != null) {
                        if (info.pantographs.contains(pos)) {
                            boolean newState = info.togglePantograph(pos);
                            totalToggled++;
                            changedThisCar = true;
                            syncDeployToContraptionBE(c1, pos, newState);
                        } else {
                            TrainSystemUtilities.LOGGER.warn(
                                    "[PantoToggle-DEBUG] SERVER: pos {} not in pantographs set {}",
                                    pos, info.pantographs);
                        }
                    }
                }
                default -> { /* ignore */ }
            }
            if (changedThisCar) touchedContraptions.add(c1);
            TrainSystemUtilities.LOGGER.debug(
                    "[PantoToggle-DEBUG] SERVER: car {} AFTER: deployed={}",
                    cIdx, info.deployedPantographs);
            cIdx++;
        }

        net.minecraft.network.chat.Component label = switch (action) {
            case ACTION_DEPLOY_ALL -> net.minecraft.network.chat.Component.translatable("tsu.pantograph.label_deploy_all");
            case ACTION_FOLD_ALL -> net.minecraft.network.chat.Component.translatable("tsu.pantograph.label_fold_all");
            case ACTION_TOGGLE_ONE -> net.minecraft.network.chat.Component.translatable("tsu.pantograph.label_toggle_one");
            default -> net.minecraft.network.chat.Component.literal("?");
        };
        sp.displayClientMessage(
                net.minecraft.network.chat.Component.translatable(
                        "tsu.pantograph.result_fmt", label, totalToggled)
                        .withStyle(net.minecraft.ChatFormatting.AQUA),
                true);

        // broadcast 直前に、トグルされた contraption の接触判定 (inContact/barOffsetY) を
        // 即座に再計算しておく。次の contraption tick を待つと、クライアントは一瞬
        // entry.inContact=false (= 旧値) を見て panto を T=1 (全開) に飛ばしてしまうため。
        for (Contraption tc : touchedContraptions) {
            ContraptionElectrificationTicker.recomputeContactsImmediate(tc);
        }

        // トグル直後に電化の全体 host へ公開 → UI が即座に反映される
        if (sp.getServer() != null) {
            ElectrificationData.publish(sp.getServer());
        }
    }

    /** B2: 操作者が列車のいずれかの車両の近く (= 64 ブロック以内・同次元) にいるか。
     *  GUI を開かず任意 trainId の packet を投げる remote spoof を防ぐ proximity gate。 */
    private static boolean isNearTrain(ServerPlayer sp, Train train) {
        final double maxSqr = 64.0 * 64.0;
        for (Carriage carriage : train.carriages) {
            if (carriage == null) continue;
            var e = carriage.anyAvailableEntity();
            if (e != null && e.level() == sp.level() && e.distanceToSqr(sp) <= maxSqr) {
                return true;
            }
        }
        return false;
    }

    /** Carriage から実走行中の Contraption を取り出す (前後どちらかの entity を見る)。 */
    private static Contraption extractContraption(Carriage carriage) {
        if (carriage.anyAvailableEntity() instanceof CarriageContraptionEntity cce) {
            return cce.getContraption();
        }
        return null;
    }

    /**
     * 指定 localPos のパンタ BE 状態を contraption の blockEntityData NBT + actor
     * MovementContext.blockEntityData の両方に書き込んで、視覚アニメ駆動側の
     * {@link com.trainsystemutilities.electrification.blockentity.PantographBlockEntity#isDeployed()}
     * が次フレームから新しい状態を返すようにする。
     *
     * <p>これにより Info.deployedPantographs (= サーバ側の論理状態) と
     * BE.deployed (= モデル/アニメ駆動の状態) が同期される。
     */
    private static void syncDeployToContraptionBE(Contraption c, BlockPos localPos, boolean deployed) {
        try {
            // (1) StructureBlockInfo の nbt を直接更新 (= 次回 BE 復元/シリアライズ時の値)
            var sbi = c.getBlocks().get(localPos);
            boolean sbiOk = false;
            if (sbi != null) {
                var nbt = sbi.nbt();
                if (nbt != null) {
                    nbt.putBoolean("Deployed", deployed);
                    sbiOk = true;
                }
            }
            // (2) Actor (= MovementBehaviour) の MovementContext.blockEntityData も更新
            //     → 次の tick で MovementBehaviour 側が新しい値を見れる
            var actor = c.getActorAt(localPos);
            boolean actorOk = false;
            if (actor != null && actor.right != null && actor.right.blockEntityData != null) {
                actor.right.blockEntityData.putBoolean("Deployed", deployed);
                actorOk = true;
            }
            TrainSystemUtilities.LOGGER.debug(
                    "[PantoToggle-DEBUG] SERVER: syncBE pos={} deployed={} sbiNbtUpdated={} actorNbtUpdated={}",
                    localPos, deployed, sbiOk, actorOk);
        } catch (Throwable ex) {
            TrainSystemUtilities.LOGGER.warn(
                    "[PantoToggle-DEBUG] SERVER: syncDeployToContraptionBE failed at {}: {}",
                    localPos, ex.toString());
        }
    }
}
