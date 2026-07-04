package com.trainsystemutilities.client.gui;

import belugalab.tsu.api.HintRegistry;

/**
 * V2 screen の class 名 → ヒント i18n key + wiki page 登録を中央集約。
 *
 * <p>{@code ClientSetup#register} で startup 時 1 回だけ {@link #registerAll()} を呼ぶ。
 * テキストは lang ファイルから {@link net.minecraft.network.chat.Component#translatable}
 * 経由で解決される (Phase 10 i18n)。
 */
public final class TsuScreenHints {

    private TsuScreenHints() {}

    /** P0-12: idempotency guard (= /reload や client soft-reset の重複登録抑止)。 */
    private static volatile boolean registered = false;

    public static void registerAll() {
        if (registered) return;
        registered = true;
        registerCommon();
        registerBrowse();
        registerSaveRefill();
        registerProfileDetailUpload();
        registerCreatorCenter();
        registerManagementComputer();
        registerRailwayPoster();
        registerAnnouncement();
        registerStationGroup();
        registerToolsExtra();
        registerTicketVending();
    }

    private static void registerCommon() {
        HintRegistry.register("mc-popup-close", "tsu.hint.close");
        HintRegistry.register("hint-toggle-track", "tsu.hint.toggle");
        HintRegistry.register("hint-toggle-knob", "tsu.hint.toggle");
        HintRegistry.register("hint-toggle-label", "tsu.hint.toggle");
        HintRegistry.register("wiki-btn", "tsu.hint.wiki");
    }

    private static void registerBrowse() {
        String wiki = "train-preset-tool/browse";
        HintRegistry.register("mode-trigger", "tsu.hint.mode_trigger", wiki);
        HintRegistry.register("account-btn", "tsu.hint.account", "preset-place/profile");
        HintRegistry.register("settings-btn", "tsu.hint.settings", wiki);
        HintRegistry.register("refresh-btn", "tsu.hint.refresh", wiki);
        HintRegistry.register("mine-tile", "tsu.hint.mine_tile", wiki);
        HintRegistry.register("public-tile", "tsu.hint.public_tile", "preset-place/detail");
        HintRegistry.register("glue-tank-row", "tsu.hint.glue_tank_row", "train-preset-tool/refill");
        HintRegistry.register("glue-tank-refill", "tsu.hint.glue_refill", "train-preset-tool/refill");
        HintRegistry.register("glue-tank-dump", "tsu.hint.glue_dump", "train-preset-tool/refill");
        HintRegistry.register("material-source-pill", "tsu.hint.material_pill", wiki);
        HintRegistry.register("pending-proceed-btn", "tsu.hint.proceed", wiki);
        HintRegistry.register("pending-cancel-btn", "tsu.hint.cancel_place", wiki);
        HintRegistry.register("materials-btn", "tsu.hint.materials", wiki);
    }

    private static void registerSaveRefill() {
        HintRegistry.register("save-name-input", "tsu.hint.save_input", "train-preset-tool/save");
        HintRegistry.register("save-btn", "tsu.hint.save_btn", "train-preset-tool/save");
        HintRegistry.register("cancel-btn", "tsu.common.cancel");
        HintRegistry.register("refill-tank-bar", "tsu.hint.refill_bar", "train-preset-tool/refill");
        HintRegistry.register("refill-dump-btn", "tsu.hint.refill_dump", "train-preset-tool/refill");
        HintRegistry.register("refill-inv-slot", "tsu.hint.refill_slot", "train-preset-tool/refill");
    }

    private static void registerProfileDetailUpload() {
        String pProfile = "preset-place/profile";
        HintRegistry.register("creator-center-btn", "tsu.hint.creator_center", "preset-place/creator-center");
        HintRegistry.register("follow-btn", "tsu.hint.follow_btn", pProfile);
        HintRegistry.register("notif-clear-btn", "tsu.hint.notif_clear", pProfile);
        HintRegistry.register("back-btn", "tsu.hint.back");
        String pDetail = "preset-place/detail";
        HintRegistry.register("sb-like-btn", "tsu.hint.like", pDetail);
        HintRegistry.register("sb-dl-btn", "tsu.hint.dl", pDetail);
        HintRegistry.register("sb-3d-btn", "tsu.hint.threed", pDetail);
        HintRegistry.register("sb-report-btn", "tsu.hint.report", pDetail);
        HintRegistry.register("sb-uploader-anchor", "tsu.hint.uploader", pProfile);
        HintRegistry.register("sb-uploader-canvas", "tsu.hint.uploader", pProfile);
        String pUpload = "preset-place/upload";
        HintRegistry.register("upload-plus-btn", "tsu.hint.upload_plus", pUpload);
        HintRegistry.register("upload-img-remove", "tsu.hint.upload_remove", pUpload);
        HintRegistry.register("upload-preview-toggle-track", "tsu.hint.preview_toggle", pUpload);
        HintRegistry.register("upload-preview-toggle-knob", "tsu.hint.preview_toggle", pUpload);
        HintRegistry.register("upload-publish-btn", "tsu.hint.publish", pUpload);
        HintRegistry.register("upload-cancel-btn", "tsu.hint.cancel_upload");
    }

    private static void registerCreatorCenter() {
        String wiki = "preset-place/creator-center";
        HintRegistry.register("cc-back-btn", "tsu.hint.cc_back");
        HintRegistry.register("cc-stat-box", "tsu.hint.cc_stat_box", wiki);
        HintRegistry.register("cc-chart-panel", "tsu.hint.cc_chart", wiki);
    }

    private static void registerManagementComputer() {
        String wiki = "management-computer/overview";
        HintRegistry.register("monitor-toggle-track", "tsu.hint.monitor_toggle", "management-computer/monitor");
        HintRegistry.register("monitor-toggle-knob", "tsu.hint.monitor_toggle", "management-computer/monitor");
        // テキストラベルにホバーしても出るよう label class も登録 (poster/railway 共通)
        HintRegistry.register("monitor-status-label", "tsu.hint.monitor_toggle", "management-computer/monitor");
        HintRegistry.register("layout-edit-btn", "tsu.hint.layout_edit", "management-computer/layout-editor");
        HintRegistry.register("monitor-color-btn", "tsu.hint.monitor_color", "management-computer/color-settings");
        HintRegistry.register("tab-trigger", "tsu.hint.tab_trigger", wiki);
        HintRegistry.register("sym-edit-btn", "tsu.hint.sym_edit", "management-computer/symbol-editor");
        // モード dropdown 各項目 (= 選択中にモード別の簡単な説明)
        HintRegistry.register("tab-item-map", "tsu.hint.mode_map", wiki);
        HintRegistry.register("tab-item-trains", "tsu.hint.mode_trains", wiki);
        HintRegistry.register("tab-item-schedule", "tsu.hint.mode_schedule", wiki);
        HintRegistry.register("tab-item-stations", "tsu.hint.mode_stations", wiki);
        HintRegistry.register("tab-item-symbol", "tsu.hint.mode_symbol", wiki);
        // 券売機タブ (販売駅の取捨選択) — wiki は専用ページ
        String ticketsWiki = "management-computer/tickets";
        HintRegistry.register("tab-item-tickets", "tsu.hint.mode_tickets", ticketsWiki);
        HintRegistry.register("tickets-tab-title", "tsu.hint.tickets_overview", ticketsWiki);
        HintRegistry.register("ticket-row-name", "tsu.hint.tickets_overview", ticketsWiki);
        HintRegistry.register("ticket-toggle-track", "tsu.hint.ticket_toggle", ticketsWiki);
        HintRegistry.register("ticket-toggle-knob", "tsu.hint.ticket_toggle", ticketsWiki);
        // モニターレイアウト編集 UI (overlay) — UI 内ホバーで説明
        String leWiki = "management-computer/layout-editor";
        HintRegistry.register("layout-edit-popup", "tsu.hint.layout_edit", leWiki);
        HintRegistry.register("layout-preview", "tsu.hint.layout_edit", leWiki);
        HintRegistry.register("layout-clear-btn", "tsu.hint.layout_clear", leWiki);
        HintRegistry.register("layout-recommend-btn", "tsu.hint.layout_recommend", leWiki);
        HintRegistry.register("layout-save-btn", "tsu.hint.layout_save", leWiki);
    }

    private static void registerRailwayPoster() {
        // 鉄道管理: 設定/色の実 class は settings-btn/color-btn だが browse と衝突するため、
        // JSON 側で rm-* を first class に付与 (resolveFirst が rm-* を優先) して鉄道用文言を出す。
        HintRegistry.register("rm-color-btn", "tsu.hint.rm_color", "railway-management/color");
        HintRegistry.register("rm-settings-btn", "tsu.hint.rm_settings", "railway-management/settings");
        HintRegistry.register("function-dd-trigger", "tsu.hint.rm_function", "railway-management");
        HintRegistry.register("screen-door-btn", "tsu.hint.rm_screen_door", "railway-management/screen-door");
        HintRegistry.register("announcement-btn", "tsu.hint.announcement_btn", "railway-management/announcement");
        // ポスター管理
        String pAnim = "poster-management/animation";
        HintRegistry.register("anim-row", "tsu.hint.anim_row", pAnim);
        HintRegistry.register("anim-settings-btn", "tsu.hint.anim_row", pAnim);
        HintRegistry.register("file-pick-btn", "tsu.hint.poster_file_pick", "poster-management");
        HintRegistry.register("fit-toggle-track", "tsu.hint.poster_fit", "poster-management");
        HintRegistry.register("fit-toggle-knob", "tsu.hint.poster_fit", "poster-management");
        HintRegistry.register("fit-label", "tsu.hint.poster_fit", "poster-management");
        HintRegistry.register("anim-single-toggle-track", "tsu.hint.poster_anim_single", pAnim);
        HintRegistry.register("anim-single-toggle-knob", "tsu.hint.poster_anim_single", pAnim);
        HintRegistry.register("anim-single-label", "tsu.hint.poster_anim_single", pAnim);
    }

    private static void registerAnnouncement() {
        String wiki = "railway-management/announcement";
        HintRegistry.register("ann-master-toggle-track", "tsu.hint.ann_master", wiki);
        HintRegistry.register("ann-master-toggle-knob", "tsu.hint.ann_master", wiki);
        HintRegistry.register("ann-rangeframe-toggle-track", "tsu.hint.ann_rangeframe", wiki);
        HintRegistry.register("ann-rangeframe-toggle-knob", "tsu.hint.ann_rangeframe", wiki);
        HintRegistry.register("ann-attenuation-toggle-track", "tsu.hint.ann_attenuation", wiki);
        HintRegistry.register("ann-attenuation-toggle-knob", "tsu.hint.ann_attenuation", wiki);
        HintRegistry.register("ann-add-entry-btn", "tsu.hint.ann_add_entry", wiki);
        HintRegistry.register("ann-test-play-btn", "tsu.hint.ann_test_play", wiki);
        HintRegistry.register("ann-share-btn", "tsu.hint.ann_share", wiki);
        HintRegistry.register("ann-cond-display", "tsu.hint.ann_cond", wiki);
        HintRegistry.register("ann-delay-display", "tsu.hint.ann_delay", wiki);
        HintRegistry.register("ann-count-display", "tsu.hint.ann_count", wiki);
        HintRegistry.register("ann-entry-test-btn", "tsu.hint.ann_entry_test", wiki);
        HintRegistry.register("ann-entry-pause-btn", "tsu.hint.ann_entry_pause", wiki);
        HintRegistry.register("ann-entry-del-btn", "tsu.hint.ann_entry_del", wiki);
        HintRegistry.register("ann-entry-up-btn", "tsu.hint.ann_entry_move", wiki);
        HintRegistry.register("ann-entry-down-btn", "tsu.hint.ann_entry_move", wiki);
        HintRegistry.register("ann-media-slot-frame", "tsu.hint.ann_media_slot", wiki);
        HintRegistry.register("ann-detection-slot-frame", "tsu.hint.ann_detection_slot", wiki);
        HintRegistry.register("ann-range-slot-frame", "tsu.hint.ann_range_slot", wiki);
    }

    private static void registerStationGroup() {
        String wiki = "tools/station-range-tool";
        HintRegistry.register("sgm-rename-btn", "tsu.hint.sgm_rename", wiki);
        HintRegistry.register("sgm-delete-btn", "tsu.hint.sgm_delete", wiki);
        HintRegistry.register("sgm-name-input", "tsu.hint.sgm_name_input", wiki);
        HintRegistry.register("sg-name-input", "tsu.hint.sg_save_input", wiki);
        HintRegistry.register("sg-save-btn", "tsu.hint.sg_save_btn", wiki);
        // 駅グループ管理: 一覧/詳細領域にホバーで概要 (= UI 内ホバーで簡単な説明)
        HintRegistry.register("sgm-list-bg", "tsu.hint.sgm_overview", wiki);
        HintRegistry.register("sgm-detail-bg", "tsu.hint.sgm_overview", wiki);
        HintRegistry.register("sgm-platforms", "tsu.hint.sgm_platforms", wiki);
    }

    /** 架線柱自動配置ツール / 架線接続ツール — UI 内ホバーで機能説明。 */
    private static void registerToolsExtra() {
        String opasWiki = "tools/overhead-pole-auto-tool";
        HintRegistry.register("opas-dialog", "tsu.hint.opas_overview", opasWiki);
        HintRegistry.register("opas-h-value", "tsu.hint.opas_height", opasWiki);
        HintRegistry.register("opas-c-value", "tsu.hint.opas_clearance", opasWiki);
        HintRegistry.register("opas-m-value", "tsu.hint.opas_multi", opasWiki);
        HintRegistry.register("opas-cant-track", "tsu.hint.opas_cant", opasWiki);
        HintRegistry.register("opas-cant-knob", "tsu.hint.opas_cant", opasWiki);
        HintRegistry.register("opas-truss-track", "tsu.hint.opas_truss", opasWiki);
        HintRegistry.register("opas-truss-knob", "tsu.hint.opas_truss", opasWiki);
        HintRegistry.register("opas-ins-track", "tsu.hint.opas_ins", opasWiki);
        HintRegistry.register("opas-ins-knob", "tsu.hint.opas_ins", opasWiki);
        HintRegistry.register("opas-preview", "tsu.hint.opas_preview", opasWiki);
        HintRegistry.register("opas-apply-btn", "tsu.hint.opas_apply", opasWiki);

        String wireWiki = "wire-connector";
        HintRegistry.register("wire-tile-slot", "tsu.hint.wire_tile", wireWiki);
        HintRegistry.register("sag-toggle-track", "tsu.hint.wire_sag", wireWiki);
        HintRegistry.register("sag-toggle-knob", "tsu.hint.wire_sag", wireWiki);
        HintRegistry.register("filter-dropdown-btn", "tsu.hint.wire_filter", wireWiki);
        HintRegistry.register("search-box", "tsu.hint.wire_search", wireWiki);
        HintRegistry.register("selected-preview-frame", "tsu.hint.wire_selected", wireWiki);
    }

    /** 券売機 UI — 発駅表示 / 行き先ボタン格子にホバーで機能説明。 */
    private static void registerTicketVending() {
        String wiki = "structure/ticket-vending-machine";
        HintRegistry.register("tvm-title", "tsu.hint.tvm_title", wiki);
        HintRegistry.register("tvm-grid", "tsu.hint.tvm_grid", wiki);
        HintRegistry.register("tvm-board-bg", "tsu.hint.tvm_grid", wiki);
        HintRegistry.register("tvm-instruction", "tsu.hint.tvm_grid", wiki);
    }
}
