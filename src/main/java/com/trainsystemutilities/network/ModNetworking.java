package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetworking {
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(TrainSystemUtilities.MOD_ID).versioned("1.7");
        // 1.1 (MANTA_7_CONCEPT C4): the held tools' 17 payloads are the actions of ToolsData's player
        // host (manta:data) - a 1.0 client would send payloads this server no longer registers.
        // 1.2: the railway management block's 7 payloads are its host's (RailwayManagementData).
        // 1.3: the management computer's 12 are its host's (ManagementComputerData) and the player's tools host's.
        // 1.4: station groups and ticket vending: the server-wide StationGroupsData and the player's StationsData.
        // 1.5: the transit terminal and the walking navigation (the player's TransitData hosts) and the trains'
        // live positions (the server-wide TrainPositionsData).
        // 1.6: electrification (the server-wide ElectrificationData), and the pantograph action, the screen to open and
        // the in-train monitors' data on the player's TrainData host.
        // 1.7: the preset list (paged), delete and material check on the player's PresetsData host. What stays is
        // TRANSPORT: the preset data (a preset's NBT), the import (a downloaded file), images, previews, wires, signals.

        // Client → Server: 画像アップロード
        registrar.playToServer(
                ImageUploadStartPayload.TYPE,
                ImageUploadStartPayload.STREAM_CODEC,
                ImageUploadStartPayload::handle
        );
        registrar.playToServer(
                ImageUploadChunkPayload.TYPE,
                ImageUploadChunkPayload.STREAM_CODEC,
                ImageUploadChunkPayload::handle
        );

        // Client → Server: 画像ダウンロード要求
        registrar.playToServer(
                ImageDownloadRequestPayload.TYPE,
                ImageDownloadRequestPayload.STREAM_CODEC,
                ImageDownloadRequestPayload::handle
        );

        // Server → Client: 画像データ送信
        registrar.playToClient(
                ClientImageDataPayload.TYPE,
                ClientImageDataPayload.STREAM_CODEC,
                ClientImageDataPayload::handle
        );

        // Client → Server: プリセットプレイス DL したバイナリをワールドにインポート
        registrar.playToServer(
                TrainPresetImportPayload.TYPE,
                TrainPresetImportPayload.STREAM_CODEC,
                TrainPresetImportPayload::handle
        );

        // Client → Server: プリセット完全データ要求
        registrar.playToServer(
                TrainPresetDataRequestPayload.TYPE,
                TrainPresetDataRequestPayload.STREAM_CODEC,
                TrainPresetDataRequestPayload::handle
        );

        // Server → Client: プリセット完全データ応答
        registrar.playToClient(
                TrainPresetDataResponsePayload.TYPE,
                TrainPresetDataResponsePayload.STREAM_CODEC,
                TrainPresetDataResponsePayload::handle
        );

        // Server → Client: プリセットの保存 / 設置結果は manta の共通通知 (com.manta.api.net.Notify)
        // に移した (B14)。この 2 つの payload は HudToast を出すためだけに存在していた。

        // #8 連結信号 MP fix: 連結/切離し時の信号オーバーライド (赤青/赤白点滅) S2C (chunk tracking 配信)
        registrar.playToClient(
                CouplingSignalOverridePayload.TYPE,
                CouplingSignalOverridePayload.STREAM_CODEC,
                CouplingSignalOverridePayload::handle
        );

        // Phase 16.x: 列車プレビュー (3D モデル) の on-demand 取得
        // entity が client にロードされていない遠方列車も管理コンピューターからプレビュー可能に。
        registrar.playToServer(
                TrainPreviewRequestPayload.TYPE,
                TrainPreviewRequestPayload.STREAM_CODEC,
                TrainPreviewRequestPayload::handle
        );
        registrar.playToClient(
                TrainPreviewResponsePayload.TYPE,
                TrainPreviewResponsePayload.STREAM_CODEC,
                TrainPreviewResponsePayload::handle
        );

        // Phase 21: FE 電化システム — 架線接続の S2C 同期
        registrar.playToClient(
                WireSyncPayload.TYPE,
                WireSyncPayload.STREAM_CODEC,
                WireSyncPayload::handle
        );

    }
}
