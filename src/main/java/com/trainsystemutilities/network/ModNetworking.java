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
        final PayloadRegistrar registrar = event.registrar(TrainSystemUtilities.MOD_ID).versioned("1.0");

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

        // Client → Server: 列車プリセットツールのモード切替
        registrar.playToServer(
                TrainPresetToolModePayload.TYPE,
                TrainPresetToolModePayload.STREAM_CODEC,
                TrainPresetToolModePayload::handle
        );

        // Client → Server: 列車プリセット保存
        registrar.playToServer(
                TrainPresetSavePayload.TYPE,
                TrainPresetSavePayload.STREAM_CODEC,
                TrainPresetSavePayload::handle
        );

        // Client → Server: プリセットプレイス DL したバイナリをワールドにインポート
        registrar.playToServer(
                TrainPresetImportPayload.TYPE,
                TrainPresetImportPayload.STREAM_CODEC,
                TrainPresetImportPayload::handle
        );

        // Client → Server: プリセット一覧要求
        registrar.playToServer(
                TrainPresetListRequestPayload.TYPE,
                TrainPresetListRequestPayload.STREAM_CODEC,
                TrainPresetListRequestPayload::handle
        );

        // Server → Client: プリセット一覧
        registrar.playToClient(
                TrainPresetListResponsePayload.TYPE,
                TrainPresetListResponsePayload.STREAM_CODEC,
                TrainPresetListResponsePayload::handle
        );

        // Client → Server: プリセット選択 (設置モードに切替)
        registrar.playToServer(
                TrainPresetSelectPayload.TYPE,
                TrainPresetSelectPayload.STREAM_CODEC,
                TrainPresetSelectPayload::handle
        );

        // Client → Server: プリセット削除
        registrar.playToServer(
                TrainPresetDeletePayload.TYPE,
                TrainPresetDeletePayload.STREAM_CODEC,
                TrainPresetDeletePayload::handle
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

        // Client → Server: PLACE モードキャンセル
        registrar.playToServer(
                TrainPresetCancelPlacePayload.TYPE,
                TrainPresetCancelPlacePayload.STREAM_CODEC,
                TrainPresetCancelPlacePayload::handle
        );

        // Client → Server: 粘着剤タンク補充
        registrar.playToServer(
                TrainPresetGlueRefillPayload.TYPE,
                TrainPresetGlueRefillPayload.STREAM_CODEC,
                TrainPresetGlueRefillPayload::handle
        );

        // Client → Server: 粘着剤タンクダンプ (0 リセット)
        registrar.playToServer(
                TrainPresetGlueDumpPayload.TYPE,
                TrainPresetGlueDumpPayload.STREAM_CODEC,
                TrainPresetGlueDumpPayload::handle
        );

        registrar.playToServer(
                TrainPresetMaterialConfigPayload.TYPE,
                TrainPresetMaterialConfigPayload.STREAM_CODEC,
                TrainPresetMaterialConfigPayload::handle
        );

        // Client → Server: shift+ホイール押し込みでチェストをリンク
        registrar.playToServer(
                TrainPresetLinkChestPayload.TYPE,
                TrainPresetLinkChestPayload.STREAM_CODEC,
                TrainPresetLinkChestPayload::handle
        );

        // Client → Server: 架線柱配置ツールで shift+中クリックでチェストをリンク
        registrar.playToServer(
                OverheadPoleLinkChestPayload.TYPE,
                OverheadPoleLinkChestPayload.STREAM_CODEC,
                OverheadPoleLinkChestPayload::handle
        );

        // Client → Server: 架線設定の「架線を補充」ボタン → 架線スプール装填メニューを開く
        registrar.playToServer(
                WireConnectorRefillOpenPayload.TYPE,
                WireConnectorRefillOpenPayload.STREAM_CODEC,
                WireConnectorRefillOpenPayload::handle
        );

        // Client → Server: 資材待ちからの再開 (ホイール押し込み)
        registrar.playToServer(
                TrainPresetRetryPayload.TYPE,
                TrainPresetRetryPayload.STREAM_CODEC,
                TrainPresetRetryPayload::handle
        );

        // Client → Server: 「このプリセットの不足材料」を問い合わせ
        registrar.playToServer(
                TrainPresetMaterialCheckRequestPayload.TYPE,
                TrainPresetMaterialCheckRequestPayload.STREAM_CODEC,
                TrainPresetMaterialCheckRequestPayload::handle
        );

        // Server → Client: 不足材料の応答
        registrar.playToClient(
                TrainPresetMaterialCheckResponsePayload.TYPE,
                TrainPresetMaterialCheckResponsePayload.STREAM_CODEC,
                TrainPresetMaterialCheckResponsePayload::handle
        );

        // Server → Client: プリセット保存結果 HUD 通知
        registrar.playToClient(
                TrainPresetSaveResultPayload.TYPE,
                TrainPresetSaveResultPayload.STREAM_CODEC,
                TrainPresetSaveResultPayload::handle
        );
        registrar.playToClient(
                TrainPresetPlaceResultPayload.TYPE,
                TrainPresetPlaceResultPayload.STREAM_CODEC,
                TrainPresetPlaceResultPayload::handle
        );

        // Phase 14.1: Client → Server: 駅グループ作成
        registrar.playToServer(
                StationGroupCreatePayload.TYPE,
                StationGroupCreatePayload.STREAM_CODEC,
                StationGroupCreatePayload::handle
        );

        // Phase 14.1: Client → Server: 駅範囲指定ツールのモード切替 (alt/ctrl+wheel)
        registrar.playToServer(
                StationRangeToolModePayload.TYPE,
                StationRangeToolModePayload.STREAM_CODEC,
                StationRangeToolModePayload::handle
        );
        // Phase 14.1: 駅グループ list / rename / delete
        registrar.playToServer(
                StationGroupListRequestPayload.TYPE,
                StationGroupListRequestPayload.STREAM_CODEC,
                StationGroupListRequestPayload::handle
        );
        registrar.playToServer(
                StationGroupRenamePayload.TYPE,
                StationGroupRenamePayload.STREAM_CODEC,
                StationGroupRenamePayload::handle
        );
        registrar.playToServer(
                StationGroupDeletePayload.TYPE,
                StationGroupDeletePayload.STREAM_CODEC,
                StationGroupDeletePayload::handle
        );
        registrar.playToClient(
                StationGroupListResponsePayload.TYPE,
                StationGroupListResponsePayload.STREAM_CODEC,
                StationGroupListResponsePayload::handle
        );
        // Phase 14.4: 乗り換え案内 search / result
        registrar.playToServer(
                TransitSearchPayload.TYPE,
                TransitSearchPayload.STREAM_CODEC,
                TransitSearchPayload::handle
        );
        registrar.playToClient(
                TransitResultPayload.TYPE,
                TransitResultPayload.STREAM_CODEC,
                TransitResultPayload::handle
        );

        // Phase 19: 乗り換え案内端末 SCHEDULE タブ用、全列車スケジュール sync
        registrar.playToServer(
                TransitScheduleRequestPayload.TYPE,
                TransitScheduleRequestPayload.STREAM_CODEC,
                TransitScheduleRequestPayload::handle
        );
        registrar.playToClient(
                TransitSchedulePayload.TYPE,
                TransitSchedulePayload.STREAM_CODEC,
                TransitSchedulePayload::handle
        );

        // Phase 19.5: 乗り換え案内端末 MAP タブ用、線路ネットワーク polyline sync
        registrar.playToServer(
                TransitMapRequestPayload.TYPE,
                TransitMapRequestPayload.STREAM_CODEC,
                TransitMapRequestPayload::handle
        );
        registrar.playToClient(
                TransitMapPayload.TYPE,
                TransitMapPayload.STREAM_CODEC,
                TransitMapPayload::handle
        );

        // Phase 22: 列車のリアルタイム位置 (server tick で全 player へ broadcast)
        registrar.playToClient(
                TrainPositionPayload.TYPE,
                TrainPositionPayload.STREAM_CODEC,
                TrainPositionPayload::handle
        );

        // #7 からくりモニター MP fix: 走行中モニターの表示データ S2C (entity tracking 配信)
        registrar.playToClient(
                MonitorDisplayInfoPayload.TYPE,
                MonitorDisplayInfoPayload.STREAM_CODEC,
                MonitorDisplayInfoPayload::handle
        );

        // #8 連結信号 MP fix: 連結/切離し時の信号オーバーライド (赤青/赤白点滅) S2C (chunk tracking 配信)
        registrar.playToClient(
                CouplingSignalOverridePayload.TYPE,
                CouplingSignalOverridePayload.STREAM_CODEC,
                CouplingSignalOverridePayload::handle
        );

        // Phase 24: 3D 経路案内 (3DBDS) — 駅までの徒歩ナビ
        registrar.playToServer(
                NavPathRequestPayload.TYPE,
                NavPathRequestPayload.STREAM_CODEC,
                NavPathRequestPayload::handle
        );
        registrar.playToClient(
                NavPathPayload.TYPE,
                NavPathPayload.STREAM_CODEC,
                NavPathPayload::handle
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

        // Phase 18: SAS 統合 アナウンス設定
        registrar.playToServer(
                AnnouncementCommandPayload.TYPE,
                AnnouncementCommandPayload.STREAM_CODEC,
                AnnouncementCommandPayload::handle
        );
        registrar.playToClient(
                AnnouncementSyncPayload.TYPE,
                AnnouncementSyncPayload.STREAM_CODEC,
                AnnouncementSyncPayload::handle
        );
        registrar.playToClient(
                AnnouncementPlaybackStatePayload.TYPE,
                AnnouncementPlaybackStatePayload.STREAM_CODEC,
                AnnouncementPlaybackStatePayload::handle
        );
        registrar.playToServer(
                AnnouncementShareTogglePayload.TYPE,
                AnnouncementShareTogglePayload.STREAM_CODEC,
                AnnouncementShareTogglePayload::handle
        );

        // Phase 21: FE 電化システム — 架線接続の S2C 同期
        registrar.playToClient(
                WireSyncPayload.TYPE,
                WireSyncPayload.STREAM_CODEC,
                WireSyncPayload::handle
        );

        // Phase 21+: 架線接続ツールのデザインタイプ切替 (Alt+ホイール)
        registrar.playToServer(
                WireConnectorTypePayload.TYPE,
                WireConnectorTypePayload.STREAM_CODEC,
                WireConnectorTypePayload::handle
        );

        // Phase 24: 架線接続ツールのカスタムプリセット保存/削除/適用
        registrar.playToServer(
                WireConnectorPresetPayload.TYPE,
                WireConnectorPresetPayload.STREAM_CODEC,
                WireConnectorPresetPayload::handle
        );

        // Phase 24: 電化列車のパンタグラフ展開トグル (管理 GUI / コマンドから)
        registrar.playToServer(
                PantographTogglePayload.TYPE,
                PantographTogglePayload.STREAM_CODEC,
                PantographTogglePayload::handle
        );

        // B2: 管理コンピュータからの列車停止 / 再開制御 (MP desync 修正)
        registrar.playToServer(
                ManagementComputerControlPayload.TYPE,
                ManagementComputerControlPayload.STREAM_CODEC,
                ManagementComputerControlPayload::handle
        );
        // 管理コンピュータの路線記号 作成/編集/削除/駅割当 (MP desync 修正: server 権威)
        registrar.playToServer(
                ManagementSymbolPayload.TYPE,
                ManagementSymbolPayload.STREAM_CODEC,
                ManagementSymbolPayload::handle
        );
        // 管理コンピュータのモニター設定 レイアウト/色/有効化 (MP desync 修正: server 権威)
        registrar.playToServer(
                MonitorLayoutPayload.TYPE,
                MonitorLayoutPayload.STREAM_CODEC,
                MonitorLayoutPayload::handle
        );
        // 電子式時刻表の編集適用 (server 権威ゲート: 運転士 + 電子式/なし)
        registrar.playToServer(
                ApplyScheduleEditPayload.TYPE,
                ApplyScheduleEditPayload.STREAM_CODEC,
                ApplyScheduleEditPayload::handle
        );
        // 時刻表を物理スケジュールアイテムへ書き出し開始
        registrar.playToServer(
                ExportTimetablePayload.TYPE,
                ExportTimetablePayload.STREAM_CODEC,
                ExportTimetablePayload::handle
        );
        // 電子式時刻表を他列車へ共有 ON/OFF
        registrar.playToServer(
                ShareTimetablePayload.TYPE,
                ShareTimetablePayload.STREAM_CODEC,
                ShareTimetablePayload::handle
        );

        // Phase 24: 電化列車スナップショット S2C
        registrar.playToClient(
                TrainElectrificationSyncPayload.TYPE,
                TrainElectrificationSyncPayload.STREAM_CODEC,
                TrainElectrificationSyncPayload::handle
        );

        // Phase 24: 電化詳細スクリーンを開く trigger S2C
        registrar.playToClient(
                OpenElectrificationScreenPayload.TYPE,
                OpenElectrificationScreenPayload.STREAM_CODEC,
                OpenElectrificationScreenPayload::handle
        );

        // 架線柱自動配置ツール: Ctrl/Alt+ホイールでモード/値変更 (C2S)
        registrar.playToServer(
                OverheadPoleAutoToolPayload.TYPE,
                OverheadPoleAutoToolPayload.STREAM_CODEC,
                OverheadPoleAutoToolPayload::handle
        );

        // 架線柱自動配置ツール: GUI screen からの詳細設定書き戻し (C2S)
        registrar.playToServer(
                OverheadPoleAutoSettingsPayload.TYPE,
                OverheadPoleAutoSettingsPayload.STREAM_CODEC,
                OverheadPoleAutoSettingsPayload::handle
        );

        // Phase 21: ホームドア帯色の更新 (C2S)
        registrar.playToServer(
                ScreenDoorBandColorPayload.TYPE,
                ScreenDoorBandColorPayload.STREAM_CODEC,
                ScreenDoorBandColorPayload::handle
        );

        // Phase 21: ホームドア発火条件 (C2S)
        registrar.playToServer(
                ScreenDoorConditionPayload.TYPE,
                ScreenDoorConditionPayload.STREAM_CODEC,
                ScreenDoorConditionPayload::handle
        );

        // Phase 21: ホームドア手動テスト開閉 (C2S)
        registrar.playToServer(
                ScreenDoorTestActionPayload.TYPE,
                ScreenDoorTestActionPayload.STREAM_CODEC,
                ScreenDoorTestActionPayload::handle
        );

        // 券売機: UI を開く (S2C)
        registrar.playToClient(
                OpenTicketVendingPayload.TYPE,
                OpenTicketVendingPayload.STREAM_CODEC,
                OpenTicketVendingPayload::handle
        );

        // 券売機: 行き先クリックで切符発券 (C2S)
        registrar.playToServer(
                BuyTicketPayload.TYPE,
                BuyTicketPayload.STREAM_CODEC,
                BuyTicketPayload::handle
        );

        // 券売機: 販売可設定の要求 (C2S) / 同期 (S2C) / 更新 (C2S)
        registrar.playToServer(
                TicketConfigRequestPayload.TYPE,
                TicketConfigRequestPayload.STREAM_CODEC,
                TicketConfigRequestPayload::handle
        );
        registrar.playToClient(
                TicketConfigSyncPayload.TYPE,
                TicketConfigSyncPayload.STREAM_CODEC,
                TicketConfigSyncPayload::handle
        );
        registrar.playToServer(
                TicketConfigUpdatePayload.TYPE,
                TicketConfigUpdatePayload.STREAM_CODEC,
                TicketConfigUpdatePayload::handle
        );
    }
}
