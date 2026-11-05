package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.electrification.ClientTrainElectrificationCache;
import com.trainsystemutilities.network.PantographAction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 電化詳細 popup (列車詳細 popup の上に重なる overlay2) の state + dynamic text +
 * chrome ボタン click を保持する controller。{@code ManagementComputerScreenV2} から切り出し
 * (god-class deep #2)。
 *
 * <p>パンタの canvas hit-test (edPantoHits) は drawCanvas が populate する描画責務ゆえ
 * screen 側に残す。本 controller は表示/タイトル/要約と「全展開・全格納・閉じる」の chrome を扱う。
 */
public final class ElectrificationDetailController {

    private boolean open = false;
    /** 列車の電化状態の読み先: 同期済みの client cache (wiki の見本の画面は自分のサンプルを貸す)。 */
    private java.util.function.Function<UUID, com.trainsystemutilities.electrification.contraption.TrainElectrificationView> views =
            ClientTrainElectrificationCache::get;

    /** wiki の見本の画面 (ManagementComputerScreenV2.wikiCreate) のサンプル。global な cache には書かない。 */
    public void viewsFrom(java.util.function.Function<UUID,
            com.trainsystemutilities.electrification.contraption.TrainElectrificationView> views) {
        this.views = views;
    }

    public boolean isOpen() { return open; }

    public void open() { this.open = true; }

    public void close() { this.open = false; }

    /** "ed-title" / "ed-summary" の表示テキスト。該当 class でなければ null。 */
    public String resolveText(String[] classes, UUID trainId) {
        for (String c : classes) {
            if ("ed-title".equals(c)) {
                var v = views.apply(trainId);
                String name = v == null
                        ? Component.translatable("tsu.mc.not_synced").getString()
                        : v.trainName;
                return Component.translatable("tsu.mc.ed_title", name).getString();
            }
            if ("ed-summary".equals(c)) {
                var v = views.apply(trainId);
                if (v == null) return Component.translatable("tsu.mc.not_synced").getString();
                return Component.translatable("tsu.mc.ed_summary",
                        v.cars.size(), v.totalPantographs(), v.totalDeployedPantographs()).getString();
            }
        }
        return null;
    }

    /**
     * chrome ボタン click。ed-close-btn → close、ed-deploy-all-btn / ed-fold-all-btn →
     * 全パンタ展開/格納 payload。処理したら true。
     * パンタ個別 toggle (ed-car-list-canvas の hit-test) は edPantoHits を持つ screen 側で処理。
     */
    public boolean handleClick(String[] classes, UUID trainId) {
        for (String c : classes) {
            if ("ed-close-btn".equals(c)) {
                close();
                return true;
            }
            if ("ed-deploy-all-btn".equals(c)) {
                TrainSystemUtilities.LOGGER.debug("[PantoToggle-DEBUG] CLIENT click deploy-all train={}", trainId);
                com.trainsystemutilities.client.TrainClient.pantograph(
                        trainId, PantographAction.ACTION_DEPLOY_ALL, 0, BlockPos.ZERO);
                return true;
            }
            if ("ed-fold-all-btn".equals(c)) {
                TrainSystemUtilities.LOGGER.debug("[PantoToggle-DEBUG] CLIENT click fold-all train={}", trainId);
                com.trainsystemutilities.client.TrainClient.pantograph(
                        trainId, PantographAction.ACTION_FOLD_ALL, 0, BlockPos.ZERO);
                return true;
            }
        }
        return false;
    }
}
