package com.trainsystemutilities.client.gui;

import com.manta.api.screen.JsonLayoutPlainScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.MantaState;
import com.manta.api.controller.DropdownController;
import com.manta.api.controller.NumberWheelInput;
import com.manta.api.controller.ScrollViewport;
import com.manta.api.controller.TextInputController;
import com.manta.api.controller.ToggleSwitchController;
import com.manta.api.render.TextCaretRenderer;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireType;
import com.trainsystemutilities.network.WireConnectorPresetAction;
import com.trainsystemutilities.network.WireConnectorTypeAction;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.client.ToolsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 架線接続ツールのデザイン選択 GUI。
 *
 * <p>左側にデザインタイル (= 5 ビルトイン + ユーザープリセット、スクロール可能)、
 * 右側に選択中タイプのプレビュー + 諸元 + パラメータ編集。
 *
 * <p>カスタムタイプ:
 * <ul>
 *   <li>太さ / 上下間隔 / ドロッパ間隔をマウスホイールで調整 (= 値ボックスにカーソル合わせて scroll)</li>
 *   <li>2 列配置トグル</li>
 *   <li>「プリセット保存」ボタンで名前付きプリセットとして保存</li>
 *   <li>プリセットタイルを右クリックで削除確認ダイアログ</li>
 * </ul>
 */
public class WireConnectorScreen extends JsonLayoutPlainScreen {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    @Override
    protected String wikiPageId() { return "wire-connector"; }

    /** ビルトインタイプ 5 種 (順序は表示順 = 仮想リストの先頭)。
     *  CUSTOM を一番上に配置 (= プリセット作成の出発点なので頻繁にアクセスされる)。 */
    private static final WireType[] BUILTIN_TYPES = {
            WireType.CUSTOM, WireType.SIMPLE, WireType.TWO_TIER, WireType.TWIN_2ROW,
            WireType.HIGH_OFFSET };

    /** デザインフィルタモード。 */
    private static final int FILTER_ALL = 0;
    private static final int FILTER_BASIC = 1;
    private static final int FILTER_CUSTOM = 2;
    private static final int VISIBLE_SLOTS = 5;
    private static final int WIRE_PREVIEW_COLOR = 0xFF80DEEA;
    /** 架線タンクゲージの内側幅 (= bar 幅 200 - border 1*2 = 198)。fill width 計算用。 */
    private static final int TANK_BAR_INNER_W = 198;

    /** 現在選択中のタイプ。 */
    private WireType selected;
    /** SIMPLE 線専用「たるみモード」。 */
    private boolean sagEnabled;
    /** CUSTOM 線パラメータ。マウスホイールで調整、プリセット選択中は固定。
     *  3 つすべて同じ enabledWhen 条件 (= CUSTOM 編集中) を持ち、onChange でサーバ同期。 */
    private final NumberWheelInput customThickness;
    private final NumberWheelInput customTrolleyOffset;
    private final NumberWheelInput customDropperInterval;
    private int customRowCount;
    /** ローカルキャッシュのプリセット一覧。サーバから再 sync は無いので、save/delete 操作後は
     *  ツールを開き直すまでローカル更新だけで運用。 */
    private List<WireConnectorItem.Preset> presets;
    /** タイル一覧のスクロール状態 (= 5 枚ウィンドウ、検索/フィルタ後の総数に追従)。 */
    private final ScrollViewport tileScroll = new ScrollViewport(this::totalTiles, VISIBLE_SLOTS);
    /** プリセット由来の選択中なら、その名前。null なら直接 CUSTOM 編集中。
     *  プリセット選択中はカスタムパラメータの変更を禁止 (= ホイール操作不可)。 */
    private String currentPresetName = null;

    /** 検索ボックス入力 (= 変更時にスクロールを先頭に戻す)。 */
    private final TextInputController searchQuery =
            new TextInputController(32, Component.translatable("tsu.common.search").getString())
                    .onChange(() -> tileScroll.setOffset(0));
    /** デザインフィルタ用ドロップダウン (= 開閉状態 + 選択中インデックス を一元化)。
     *  選択変更時にスクロールを先頭に戻す。 */
    private final DropdownController filterDropdown =
            new DropdownController("filter-dropdown-btn", "filter-option-", FILTER_ALL)
                    .onSelected(idx -> tileScroll.setOffset(0));
    /** SIMPLE 線専用たるみモードのトグル。selected==SIMPLE のときのみ操作可。 */
    private final ToggleSwitchController sagToggle =
            new ToggleSwitchController("sag-toggle-track", "sag-toggle-knob",
                    () -> sagEnabled, this::setSagEnabled)
                    .enabledWhen(() -> selected == WireType.SIMPLE);
    /** CUSTOM 線の 2 列配置トグル (= customRowCount を 1/2 に切替)。
     *  CUSTOM 直接編集中のみ操作可 (プリセット選択中は固定)。 */
    private final ToggleSwitchController customRowToggle =
            new ToggleSwitchController("custom-row-toggle-track", "custom-row-toggle-knob",
                    () -> customRowCount == 2, this::setCustomRow2)
                    .enabledWhen(() -> selected == WireType.CUSTOM && currentPresetName == null);

    /** ダイアログ状態。 */
    /** 共通の確認ダイアログ (R4.27.1)。 */
    private final com.manta.api.screen.ConfirmDialog<Void> applyConfirm = com.manta.api.screen.ConfirmDialog.<Void>confirm()
            .title("tsu.wire_connector.confirm_title")
            .yes("tsu.wire_connector.confirm_ok")
            .body(ignored -> selectedDesignName());
    private boolean showSaveDialog = false;
    /** プリセット保存ダイアログの名前入力。Enter で保存、Esc でダイアログ閉じ。 */
    private final TextInputController saveNameInput =
            new TextInputController(WireConnectorPresetAction.MAX_NAME_CODE_POINTS, "")
                    .onSubmit(() -> onElementClick(new String[]{"preset-save-ok"}, 0, 0, 0))
                    .onEscape(this::closeSaveDialog);
    private final com.manta.api.screen.ConfirmDialog<Integer> deleteConfirm = com.manta.api.screen.ConfirmDialog.<Integer>danger()
            .title("tsu.wire_connector.preset_delete_title")
            .yes("tsu.wire_connector.preset_delete_ok")
            .body(this::presetNameAt);
    private int deletePresetIndex = -1;

    public WireConnectorScreen() {
        this(findTool());
    }

    /**
     * The wiki's stand-in's tool: a demo one holding two spools of wire, not the one the viewer holds - the refill section
     * fills it (改善1, 2026-10-07). Null: the held one.
     */
    private ItemStack wikiTool = null;

    /** The wiki's stand-in (WikiLiveCapture): the screen on its demo tool. */
    public static WireConnectorScreen wikiCreate() {
        ItemStack tool = new ItemStack(ModItems.WIRE_CONNECTOR.get());
        WireConnectorItem.addWireTank(tool, 2 * com.trainsystemutilities.electrification.item.WireSpoolItem.METERS_PER_SPOOL);
        WireConnectorScreen s = new WireConnectorScreen(tool);
        s.wikiTool = tool;
        return s;
    }

    private ItemStack tool() {
        return wikiTool != null ? wikiTool : findTool();
    }

    /** The screen on {@code tool}, the wire connector in hand (null: none). */
    private WireConnectorScreen(ItemStack tool) {
        super(Component.translatable("tsu.wire_connector.title"));
        this.selected = tool != null ? WireConnectorItem.readWireType(tool) : WireType.TWO_TIER;
        this.sagEnabled = tool != null && WireConnectorItem.readSag(tool);
        float initThickness = tool != null
                ? WireConnectorItem.readCustomThickness(tool) : WireConnection.DEFAULT_CUSTOM_THICKNESS;
        float initOffset = tool != null
                ? WireConnectorItem.readCustomTrolleyOffset(tool) : WireConnection.DEFAULT_CUSTOM_TROLLEY_OFFSET;
        float initDropper = tool != null
                ? WireConnectorItem.readCustomDropperInterval(tool) : WireConnection.DEFAULT_CUSTOM_DROPPER_INTERVAL;
        this.customRowCount = tool != null
                ? WireConnectorItem.readCustomRowCount(tool) : WireConnection.DEFAULT_CUSTOM_ROW_COUNT;
        this.customThickness = new NumberWheelInput(initThickness, 0.01f, 0.30f, 0.01f, "%.2f")
                .onChange(v -> sendCustomFloatPayload(WireConnectorTypeAction.ACTION_CUSTOM_THICKNESS, v))
                .enabledWhen(this::isCustomEditing);
        this.customTrolleyOffset = new NumberWheelInput(initOffset, 0f, 2.0f, 0.05f, "%.2fm")
                .onChange(v -> sendCustomFloatPayload(WireConnectorTypeAction.ACTION_CUSTOM_TROLLEY_OFFSET, v))
                .enabledWhen(this::isCustomEditing);
        this.customDropperInterval = new NumberWheelInput(initDropper, 0.5f, 10.0f, 0.25f, "%.2fm")
                .onChange(v -> sendCustomFloatPayload(WireConnectorTypeAction.ACTION_CUSTOM_DROPPER_INTERVAL, v))
                .enabledWhen(this::isCustomEditing);
        this.presets = tool != null
                ? new ArrayList<>(WireConnectorItem.readPresets(tool)) : new ArrayList<>();
    }

    /**
     * wiki キャプチャ用に state を当てる (WikiLiveCapture)。"confirm" = 適用の確認 (本文は選択中のデザイン名)。
     * "preset-delete" = プリセット削除の確認: 本文はプリセット名なので、プリセットの無いツールでは空になる →
     * 保存ボタンと同じ既定名・同じ値のサンプルを画面ローカルの一覧に 1 つ足し、そのタイルが見える位置まで
     * スクロールして開く。"preset-save" = プリセット保存のダイアログ (CUSTOM を選んだ状態で、保存ボタンと同じ既定名)。
     * 撮るだけの画面なので何も送らない。
     */
    public void wikiApplyState(String state) {
        switch (state) {
            case "confirm" -> applyConfirm.ask();
            case "preset-delete" -> {
                addWikiPreset();
                tileScroll.setOffset(totalTiles() - VISIBLE_SLOTS);
                deleteConfirm.ask(presets.size() - 1);
            }
            // BelugaAOS's UI sweep alone (WikiLiveCapture's sweep-only states, 2026-10-21): presets in the screen's own
            // list until the tiles are twice what the strip shows, so its scrollbar thumb is up to be dragged.
            case "main-long" -> {
                for (int i = totalTiles(); i < 2 * VISIBLE_SLOTS; i++) addWikiPreset();
            }
            // プリセット保存: 保存ボタンは CUSTOM 選択中にだけ効く → CUSTOM を選び、押したときと同じ既定名で開く
            case "preset-save" -> {
                selected = WireType.CUSTOM;
                currentPresetName = null;
                openSaveDialog();
            }
            default -> { /* main: overlay なし */ }
        }
    }

    /** A sample preset into the screen-local list: the save button's default name and the current values. */
    private void addWikiPreset() {
        presets.add(new WireConnectorItem.Preset(
                Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString(),
                customThickness.value(), customTrolleyOffset.value(),
                customDropperInterval.value(), customRowCount));
    }

    /** CUSTOM パラメータが現在編集可能か (= CUSTOM 選択中かつプリセット未適用)。 */
    private boolean isCustomEditing() {
        return selected == WireType.CUSTOM && currentPresetName == null;
    }

    /** CUSTOM float パラメータをサーバへ送る (= NumberWheelInput.onChange から)。 */
    private void sendCustomFloatPayload(int action, float value) {
        ToolsClient.wireType(action, Math.round(value * 1000));
    }

    @Override
    protected String layoutJson() {
        return TsuLayouts.load("layouts/wire-connector.json");
    }

    @Override
    protected String overlayJson() {
        if (applyConfirm.isOpen()) return com.manta.api.screen.MantaLayouts.load(com.manta.api.screen.ConfirmDialog.LAYOUT);
        if (showSaveDialog) return TsuLayouts.load("layouts/wire-connector-preset-save.json");
        if (deleteConfirm.isOpen()) return com.manta.api.screen.MantaLayouts.load(com.manta.api.screen.ConfirmDialog.LAYOUT);
        if (filterDropdown.isOpen()) return TsuLayouts.load("layouts/wire-connector-filter-menu.json");
        return null;
    }

    @Override
    protected int[] overlayDefaultPosition(int overlayW, int overlayH) {
        if (filterDropdown.isOpen()) {
            // Phase 5d FIX: dialogScale 適用 (autoscale 対応)
            return new int[]{dialogLocalToScreenX(14), dialogLocalToScreenY(54 + 16 + 2)};
        }
        return null;
    }

    @Override
    protected boolean closeOpenOverlay() { return filterDropdown.close(); }

    @Override
    protected boolean closeTransientOverlays() { return filterDropdown.close(); }

    // ===== Manta 7 push (Phase 4, TSU batch 3, 2026-09-23) =====
    // The page's values are WRITTEN here each frame before the engine draws, and once when a page
    // opens: the screen overrides no getDynamic*. The hint toggle and dialog-open are the base's.
    // The overlay is one of four (overlayJson's precedence, overlayShown): the two confirmations
    // are ConfirmDialog's, the save dialog's name and the filter menu's entrance are this screen's.
    private static final int OVERLAY_NONE = 0, OVERLAY_APPLY = 1, OVERLAY_SAVE = 2, OVERLAY_DELETE = 3, OVERLAY_FILTER = 4;

    private MantaState pushed, overlayPage;
    private com.manta.api.state.TextSlot tPresetName;
    private com.manta.api.state.TextSlot tTank, tSelectedName, tSelectedDesc, tTier, tRows, tHeight;
    private com.manta.api.state.TextSlot tThickness, tOffset, tDropper, tSearch, tFilter;
    private final com.manta.api.state.TextSlot[] tSlotName = new com.manta.api.state.TextSlot[VISIBLE_SLOTS];
    private final com.manta.api.state.ColorSlot[] cSlotBg = new com.manta.api.state.ColorSlot[VISIBLE_SLOTS];
    private final com.manta.api.state.ColorSlot[] cSlotBorder = new com.manta.api.state.ColorSlot[VISIBLE_SLOTS];
    private com.manta.api.state.ColorSlot cSagTrack, cSagKnob, cSagLabel, cRowTrack, cRowKnob;
    private com.manta.api.state.NumberSlot nSagKnobX, nRowKnobX, nThumbY, nThumbH, nTankFill;
    private com.manta.api.state.BoolSlot bSag, bCustom, bScrollbar;

    /** The overlay overlayJson shows, by its precedence. */
    private int overlayShown() {
        if (applyConfirm.isOpen()) return OVERLAY_APPLY;
        if (showSaveDialog) return OVERLAY_SAVE;
        if (deleteConfirm.isOpen()) return OVERLAY_DELETE;
        if (filterDropdown.isOpen()) return OVERLAY_FILTER;
        return OVERLAY_NONE;
    }

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer == PageLayer.OVERLAY) {
            overlayPage = MantaState.of(page);
            tPresetName = null;
            int shown = overlayShown();
            if (shown == OVERLAY_SAVE) {
                tPresetName = overlayPage.text("preset-name-input-value");
                overlayPage.textByKeyOnly();
            } else if (shown == OVERLAY_FILTER) {
                // The menu's root declares "animationKey": "dropdown": the base unfolds and folds it (Manta
                // DropdownMotion, 2026-10-07).
                overlayPage.textByKeyOnly();
            }
            pushOverlay();
            return;
        }
        if (layer != PageLayer.PRIMARY) return;
        pushed = MantaState.of(page);
        tTank = pushed.text("wire-tank-label");
        tSelectedName = pushed.text("selected-name-text");
        tSelectedDesc = pushed.text("selected-desc-text");
        tTier = pushed.text("info-tier-count");
        tRows = pushed.text("info-row-count");
        tHeight = pushed.text("info-height");
        tThickness = pushed.text("custom-thickness-value");
        tOffset = pushed.text("custom-offset-value");
        tDropper = pushed.text("custom-dropper-value");
        tSearch = pushed.text("search-box-value");
        tFilter = pushed.text("filter-dropdown-label");
        // The name keys are spelt out, one per visible slot: the vocabulary gate reads the TEXT keys a
        // screen writes from its source, and a key built by concatenation is invisible to it. A
        // VISIBLE_SLOTS that no longer matches fails loudly - an index out of bounds here, or a null
        // handle at the first set.
        tSlotName[0] = pushed.text("slot-0-name");
        tSlotName[1] = pushed.text("slot-1-name");
        tSlotName[2] = pushed.text("slot-2-name");
        tSlotName[3] = pushed.text("slot-3-name");
        tSlotName[4] = pushed.text("slot-4-name");
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            cSlotBg[i] = pushed.color("slot-" + i + "-bg");
            cSlotBorder[i] = pushed.color("slot-" + i + "-border");
        }
        cSagTrack = pushed.color("sag-toggle-bg");
        cSagKnob = pushed.color("sag-knob-bg");
        cSagLabel = pushed.color("sag-toggle-label-color");
        cRowTrack = pushed.color("custom-row-toggle-bg");
        cRowKnob = pushed.color("custom-row-knob-bg");
        nSagKnobX = pushed.number("sag-knob-x");
        nRowKnobX = pushed.number("custom-row-knob-x");
        nThumbY = pushed.number("scrollbar-thumb-y");
        nThumbH = pushed.number("scrollbar-thumb-h");
        nTankFill = pushed.number("wire-tank-fill");
        bSag = pushed.bool("sag-toggle-visible");
        bCustom = pushed.bool("custom-params-visible");
        bScrollbar = pushed.bool("scrollbar-visible");
        pushed.textByKeyOnly();
        pushAll();
    }

    private void pushOverlay() {
        if (overlayPage == null || !overlayPage.isOpen()) return;
        switch (overlayShown()) {
            case OVERLAY_APPLY -> applyConfirm.push(overlayPage);
            case OVERLAY_DELETE -> deleteConfirm.push(overlayPage);
            case OVERLAY_SAVE -> {
                if (tPresetName != null) overlayPage.set(tPresetName, saveNameInput.value());
            }
            default -> { }
        }
    }

    private void pushAll() {
        pushOverlay();
        if (pushed == null || !pushed.isOpen()) return;
        ItemStack tool = tool();
        int amount = tool == null ? 0 : WireConnectorItem.readWireTank(tool);
        pushed.set(tTank, amount + " / " + WireConnectorItem.WIRE_TANK_MAX + " m");
        pushed.set(nTankFill, com.manta.api.render.Gauge.fillWidth(TANK_BAR_INNER_W, amount, WireConnectorItem.WIRE_TANK_MAX));
        // A preset in use reads "<preset name> (custom)".
        boolean presetInUse = currentPresetName != null && selected == WireType.CUSTOM;
        pushed.set(tSelectedName, presetInUse
                ? currentPresetName + " (" + WireType.CUSTOM.displayName().getString() + ")"
                : selected.displayName().getString());
        pushed.set(tSelectedDesc, presetInUse
                ? Component.translatable("tsu.wire_connector.preset_loaded").getString()
                : descFor(selected));
        pushed.set(tTier, tierCountFor(selected));
        pushed.set(tRows, rowCountFor(selected));
        pushed.set(tHeight, heightFor(selected));
        pushed.set(tThickness, customThickness.formatted());
        pushed.set(tOffset, customTrolleyOffset.formatted());
        pushed.set(tDropper, customDropperInterval.formatted());
        // The search box's inner span: display() is the placeholder while empty.
        pushed.set(tSearch, searchQuery.display());
        pushed.set(tFilter, filterLabel(filterDropdown.selectedIndex()));
        for (int i = 0; i < VISIBLE_SLOTS; i++) {
            TileEntry entry = entryOfSlot(i);
            pushed.set(tSlotName[i], entry == null ? "" : entry.name());
            // The selected tile is lit; an empty slot is dimmed; any other keeps the layout's colours.
            if (entry == null) {
                pushed.set(cSlotBg[i], 0x80303030);
                pushed.set(cSlotBorder[i], 0x40555555);
            } else if (isSlotSelected(entry)) {
                pushed.set(cSlotBg[i], 0xFF3B5274);
                pushed.set(cSlotBorder[i], 0xFF80DEEA);
            } else {
                pushed.clear(cSlotBg[i]);
                pushed.clear(cSlotBorder[i]);
            }
        }
        pushed.set(cSagTrack, sagToggle.trackBg());
        pushed.set(cSagKnob, sagToggle.knobBg());
        pushed.set(cSagLabel, selected == WireType.SIMPLE ? 0xFF80DEEA : 0xFF666666);
        pushed.set(cRowTrack, customRowToggle.trackBg());
        pushed.set(cRowKnob, customRowToggle.knobBg());
        // Offsets: knobX / thumbY are default + delta, so each delta is its answer for 0.
        pushed.setOffset(nSagKnobX, sagToggle.knobX(0));
        pushed.setOffset(nRowKnobX, customRowToggle.knobX(0));
        pushed.setOffset(nThumbY, tileScroll.thumbY(0, 236 - 2, 20));
        pushed.set(nThumbH, 20);
        pushed.set(bSag, selected == WireType.SIMPLE);
        pushed.set(bCustom, selected == WireType.CUSTOM);
        pushed.set(bScrollbar, tileScroll.needsScrollbar());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
    }

    private static ItemStack findTool() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = mc.player.getItemInHand(hand);
            if (s.is(ModItems.WIRE_CONNECTOR.get())) return s;
        }
        return null;
    }

    // ===== 仮想タイルリスト (= search + filter で絞り込み済み) =====

    /** タイルエントリ。ビルトインか、プリセットか、どちらか。 */
    private record TileEntry(WireType builtin, int presetIdx, String name) {
        boolean isBuiltin() { return builtin != null; }
    }

    /** filter + search 適用済みのタイル一覧を構築。 */
    private List<TileEntry> buildVirtualList() {
        List<TileEntry> result = new ArrayList<>();
        String q = searchQuery.value().toLowerCase();
        int mode = filterDropdown.selectedIndex();
        if (mode == FILTER_ALL || mode == FILTER_BASIC) {
            for (WireType wt : BUILTIN_TYPES) {
                String name = wt.displayName().getString();
                if (q.isEmpty() || name.toLowerCase().contains(q)) {
                    result.add(new TileEntry(wt, -1, name));
                }
            }
        }
        if (mode == FILTER_ALL || mode == FILTER_CUSTOM) {
            for (int i = 0; i < presets.size(); i++) {
                String name = presets.get(i).name();
                if (q.isEmpty() || name.toLowerCase().contains(q)) {
                    result.add(new TileEntry(null, i, name));
                }
            }
        }
        return result;
    }

    /** 仮想リスト全長 (= フィルタ後)。 */
    private int totalTiles() { return buildVirtualList().size(); }

    /** スロット {@code slotIdx} に対応する TileEntry、表示外なら null。 */
    private TileEntry entryOfSlot(int slotIdx) {
        List<TileEntry> list = buildVirtualList();
        int idx = tileScroll.offset() + slotIdx;
        return idx >= 0 && idx < list.size() ? list.get(idx) : null;
    }

    // ===== Click routing =====

    /** R4.20.2: field 初期子の lambda からは method 経由で読む。 */
    private String selectedDesignName() {
        return selected == null ? "" : selected.displayName().getString();
    }

    private String presetNameAt(Integer idx) {
        return (idx != null && idx >= 0 && idx < presets.size()) ? presets.get(idx).name() : "";
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // BelugaExperience 標準ヘッダ部品 (R4.17): hint / wiki本 を先に処理 (× は下の switch)
        if (com.manta.api.hud.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) com.manta.api.wiki.Wiki.open(pid);
                return;
            }
        }
        // Apply 確認ダイアログ (共通)
        if (applyConfirm.handleClick(classes, ignored -> { apply(); onClose(); })) return;
        // Save ダイアログ
        if (showSaveDialog) {
            for (String c : classes) {
                if ("preset-save-ok".equals(c)) {
                    String name = saveNameInput.value().trim();
                    ToolsClient.wirePresetSave(name);
                    // ローカルキャッシュにも反映 (= サーバ async 後の即時 UI 更新用)
                    presets.add(new WireConnectorItem.Preset(
                            name.isEmpty() ? Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString() : name,
                            customThickness.value(), customTrolleyOffset.value(),
                            customDropperInterval.value(), customRowCount));
                    closeSaveDialog();
                    return;
                }
                if ("preset-save-cancel".equals(c)) {
                    closeSaveDialog();
                    return;
                }
            }
            return;
        }
        // Delete ダイアログ (共通)
        if (deleteConfirm.handleClick(classes, idx -> {
            if (idx != null && idx >= 0 && idx < presets.size()) {
                ToolsClient.wirePreset(WireConnectorPresetAction.ACTION_DELETE, idx);
                presets.remove((int) idx);
                // スクロールが端を超えないように補正
                tileScroll.clamp();
            }
            deletePresetIndex = -1;
        })) return;

        // メインレイアウト
        for (String c : classes) {
            // スロットクリック (左クリック = 選択 / 右クリック = プリセット削除)
            if (c.startsWith("slot-")) {
                int slotIdx;
                try { slotIdx = Integer.parseInt(c.substring("slot-".length())); }
                catch (NumberFormatException e) { continue; }
                if (slotIdx < 0 || slotIdx >= VISIBLE_SLOTS) continue;
                TileEntry entry = entryOfSlot(slotIdx);
                if (entry == null) return;
                handleTileClick(entry, button);
                return;
            }
            // 検索ボックス クリック (= フォーカス)
            if ("search-box".equals(c)) { return; }
        }
        // ドロップダウン (= filter-dropdown-btn / filter-option-N を一括処理)
        if (filterDropdown.handleClick(classes)) return;
        // トグル (= sag / custom-row。enabled gate 違反でも click は consume)
        if (sagToggle.handleClick(classes)) return;
        if (customRowToggle.handleClick(classes)) return;
        for (String c : classes) {
            switch (c) {
                case "custom-save-btn" -> {
                    if (selected == WireType.CUSTOM) openSaveDialog();
                    return;
                }
                case "confirm-btn" -> { applyConfirm.ask(); return; }
                case "wire-refill-btn" -> {
                    ToolsClient.wireRefillOpen();
                    return;
                }
                case "mc-popup-close", "header-close" -> { onClose(); return; }
            }
        }
    }

    /** 3-arg 旧呼び出し対策。 */
    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY) {
        onElementClick(classes, mouseX, mouseY, 0);
    }

    private void handleTileClick(TileEntry entry, int button) {
        if (entry == null) return;
        if (entry.isBuiltin()) {
            selected = entry.builtin();
            currentPresetName = null;
            return;
        }
        int presetIdx = entry.presetIdx();
        if (presetIdx < 0 || presetIdx >= presets.size()) return;
        if (button == 1) {
            // 右クリック → 削除確認
            deletePresetIndex = presetIdx;
            deleteConfirm.ask(presetIdx);
        } else {
            // 左クリック → CUSTOM 選択 + パラメータ + プリセット名
            WireConnectorItem.Preset p = presets.get(presetIdx);
            selected = WireType.CUSTOM;
            currentPresetName = p.name();
            customThickness.setValue(p.thickness());
            customTrolleyOffset.setValue(p.trolleyOffset());
            customDropperInterval.setValue(p.dropperInterval());
            customRowCount = p.rowCount();
            ToolsClient.wirePreset(WireConnectorPresetAction.ACTION_APPLY, presetIdx);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dxScroll, double dyScroll) {
        if (applyConfirm.isOpen() || showSaveDialog || deleteConfirm.isOpen()) {
            return super.mouseScrolled(mx, my, dxScroll, dyScroll);
        }
        // CUSTOM パラメータ調整: the values' own wheel is the engine's (their wheelKey custom-*-wheel, onElementWheel -
        // 2026-10-07, so a wiki playback's wheel is aimed at its value as a press is).
        if (super.mouseScrolled(mx, my, dxScroll, dyScroll)) return true;
        // タイル一覧スクロール (= デザインパネルにホバー時)
        if (tileScroll.needsScrollbar()) {
            int[] panel = findElementByClass("scrollbar-track");
            if (panel != null) {
                // パネル左上 14..220, 縦 52..288 (dialog-local) をホバー範囲とする。
                // local → screen は manta の変換を通す (つまみが 1.0 でないとずれる)。
                if (mx >= dialogLocalToScreenX(14) && mx < dialogLocalToScreenX(220)
                        && my >= dialogLocalToScreenY(52) && my < dialogLocalToScreenY(288)) {
                    tileScroll.scroll(dyScroll > 0 ? -1 : 1);
                    return true;
                }
            }
        }
        return false;
    }

    /** The preset tiles' thumb, grabbed and dragged (Manta 2.87.0's dragThumb, R4.19.5). */
    @Override
    public boolean onElementDrag(String[] classes, String key, int mouseX, int mouseY,
                                 int elementX, int elementY, int elementW, int elementH, boolean pressed) {
        if ("wire-tile-thumb-drag".equals(key)) {
            return tileScroll.dragThumb(mouseY, pressed);
        }
        return super.onElementDrag(classes, key, mouseX, mouseY, elementX, elementY, elementW, elementH, pressed);
    }

    /** CUSTOM パラメータ調整 (= NumberWheelInput.enabledWhen で gate、プリセット選択中は値固定)。 */
    @Override
    public boolean onElementWheel(String[] classes, String key, int mouseX, int mouseY, double scrollY) {
        if (applyConfirm.isOpen() || showSaveDialog || deleteConfirm.isOpen()) return false;
        if ("custom-thickness-wheel".equals(key)) return customThickness.handleWheel(scrollY);
        if ("custom-offset-wheel".equals(key)) return customTrolleyOffset.handleWheel(scrollY);
        if ("custom-dropper-wheel".equals(key)) return customDropperInterval.handleWheel(scrollY);
        return false;
    }

    // ===== text input (save dialog) =====

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 保存ダイアログ表示中はそちらの入力に流す。それ以外は検索ボックスに流す (= 常時アクティブ)。
        if (showSaveDialog) {
            if (saveNameInput.charTyped(codePoint)) return true;
            return super.charTyped(codePoint, modifiers);
        }
        if (!applyConfirm.isOpen() && !deleteConfirm.isOpen() && searchQuery.charTyped(codePoint)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (showSaveDialog) {
            if (saveNameInput.keyPressed(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // 通常時: ドロップダウン ESC を先に拾ってから検索ボックスへ流す
        if (!applyConfirm.isOpen() && !deleteConfirm.isOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && filterDropdown.onEscape()) return true;
            if (searchQuery.keyPressed(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 保存ダイアログを次の既定名で開く。 */
    private void openSaveDialog() {
        // setValue takes no cap of its own: the default name is cut to what the input holds, which
        // is what the wire-preset declaration is sized from (a language's name could be longer).
        String def = Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString();
        int max = WireConnectorPresetAction.MAX_NAME_CODE_POINTS;
        saveNameInput.setValue(def.codePointCount(0, def.length()) <= max
                ? def : def.substring(0, def.offsetByCodePoints(0, max)));
        showSaveDialog = true;
    }

    /** 保存ダイアログを閉じて入力バッファをクリア。 */
    private void closeSaveDialog() {
        showSaveDialog = false;
        saveNameInput.clear();
    }

    /** sag トグルの setter (= state + server sync を一括化、ToggleSwitchController から呼ぶ)。 */
    private void setSagEnabled(boolean v) {
        sagEnabled = v;
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_SAG_TOGGLE, v ? 1 : 0);
    }

    /** customRowCount 1/2 トグルの setter。 */
    private void setCustomRow2(boolean v) {
        customRowCount = v ? 2 : 1;
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_CUSTOM_ROW_COUNT, customRowCount);
    }

    private void apply() {
        ToolsClient.wireType(WireConnectorTypeAction.ACTION_TYPE_SELECT, selected.id);
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_SAG_TOGGLE, sagEnabled ? 1 : 0);
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_CUSTOM_THICKNESS, Math.round(customThickness.value() * 1000));
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_CUSTOM_TROLLEY_OFFSET, Math.round(customTrolleyOffset.value() * 1000));
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_CUSTOM_DROPPER_INTERVAL, Math.round(customDropperInterval.value() * 1000));
        ToolsClient.wireType(
                WireConnectorTypeAction.ACTION_CUSTOM_ROW_COUNT, customRowCount);
    }

    private static String filterLabel(int mode) {
        return switch (mode) {
            case FILTER_BASIC -> Component.translatable("tsu.wire_connector.filter_basic").getString();
            case FILTER_CUSTOM -> Component.translatable("tsu.wire_connector.filter_custom").getString();
            default -> Component.translatable("tsu.wire_connector.filter_all").getString();
        };
    }

    private static int parseSlotIndex(String className, String suffix) {
        if (!className.startsWith("slot-")) return -1;
        try {
            String mid = className.substring("slot-".length(), className.length() - suffix.length());
            return Integer.parseInt(mid);
        } catch (Exception e) { return -1; }
    }

    private String descFor(WireType t) {
        return switch (t) {
            case SIMPLE -> Component.translatable("tsu.wire_connector.desc_simple").getString();
            case TWO_TIER -> Component.translatable("tsu.wire_connector.desc_two_tier").getString();
            case TWIN_2ROW -> Component.translatable("tsu.wire_connector.desc_twin").getString();
            case HIGH_OFFSET -> Component.translatable("tsu.wire_connector.desc_high_offset").getString();
            case CUSTOM -> Component.translatable("tsu.wire_connector.desc_custom").getString();
        };
    }

    private String tierCountFor(WireType t) {
        return switch (t) {
            case SIMPLE -> Component.translatable("tsu.wire_connector.tier_1").getString();
            case TWO_TIER, TWIN_2ROW, HIGH_OFFSET -> Component.translatable("tsu.wire_connector.tier_2").getString();
            case CUSTOM -> customTrolleyOffset.value() > 0.01f
                    ? Component.translatable("tsu.wire_connector.tier_2").getString()
                    : Component.translatable("tsu.wire_connector.tier_1").getString();
        };
    }

    private String rowCountFor(WireType t) {
        return switch (t) {
            case TWIN_2ROW -> Component.translatable("tsu.wire_connector.row_twin").getString();
            case CUSTOM -> Component.translatable("tsu.wire_connector.row_count_fmt", customRowCount).getString();
            default -> Component.translatable("tsu.wire_connector.row_1").getString();
        };
    }

    private String heightFor(WireType t) {
        return switch (t) {
            case SIMPLE -> "—";
            case TWO_TIER, TWIN_2ROW -> "0.5 m";
            case HIGH_OFFSET -> "0.9 m";
            case CUSTOM -> String.format("%.2f m", customTrolleyOffset.value());
        };
    }

    private boolean isSlotSelected(TileEntry entry) {
        if (entry == null) return false;
        if (entry.isBuiltin()) {
            return entry.builtin() == selected && currentPresetName == null;
        }
        // プリセットタイル: そのプリセットを適用中なら選択中扱い
        return currentPresetName != null
                && entry.presetIdx() >= 0 && entry.presetIdx() < presets.size()
                && currentPresetName.equals(presets.get(entry.presetIdx()).name());
    }

    // ===== Canvas-driven preview drawing =====

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                            int x, int y, int w, int h, int mouseX, int mouseY) {
        if ("selected-preview".equals(key)) {
            // Selected プレビューはライブパラメータ反映
            drawWirePreview(g, x, y, w, h, selected, /*useDefaults=*/false);
            return;
        }
        // slot-N-preview = タイルプレビュー (= デフォルトパラメータ静的)
        if (key != null && key.startsWith("slot-") && key.endsWith("-preview")) {
            int slotIdx;
            try { slotIdx = Integer.parseInt(key.substring("slot-".length(), key.length() - "-preview".length())); }
            catch (NumberFormatException e) { return; }
            TileEntry entry = entryOfSlot(slotIdx);
            if (entry == null) return;
            if (entry.isBuiltin()) {
                drawWirePreview(g, x, y, w, h, entry.builtin(), /*useDefaults=*/true);
            } else {
                int presetIdx = entry.presetIdx();
                if (presetIdx >= 0 && presetIdx < presets.size()) {
                    WireConnectorItem.Preset p = presets.get(presetIdx);
                    drawCustomPreviewWithParams(g, x + 6, x + w - 6, y + h / 2,
                            p.thickness(), p.trolleyOffset(), p.dropperInterval(), p.rowCount());
                }
            }
            return;
        }
        // プリセット名入力: テキスト本体は JSON 内の inner span が描画するので
        //                    canvas はカーソルのみ描く (= 二重描画回避)。
        if ("preset-name-input-caret".equals(key)) {
            TextCaretRenderer.draw(g, font, saveNameInput.value(), x, y, w, h, 0xFF4FC3F7);
            return;
        }
        // 検索ボックスのカーソル (= 同じパターン)
        if ("search-box-caret".equals(key)) {
            TextCaretRenderer.draw(g, font, searchQuery.value(), x, y, w, h, 0xFF4FC3F7);
            return;
        }
    }

    /**
     * 架線プレビュー描画。
     * @param useDefaults true なら CUSTOM タイプもデフォルト値で描画 (= タイル用、不変)。
     *                    false ならライブカスタム値で描画 (= 選択中プレビュー用、動的)。
     */
    private void drawWirePreview(GuiGraphics g, int x, int y, int w, int h, WireType t, boolean useDefaults) {
        int color = WIRE_PREVIEW_COLOR;
        int pad = 6;
        int x0 = x + pad;
        int x1 = x + w - pad;
        int midY = y + h / 2;
        switch (t) {
            case SIMPLE -> drawHLine(g, x0, x1, midY, color);
            case TWO_TIER -> drawTwoTier(g, x0, x1, midY, 5, color, 3);
            case TWIN_2ROW -> {
                drawTwoTier(g, x0, x1, midY - 7, 4, color, 3);
                drawTwoTier(g, x0, x1, midY + 7, 4, color, 3);
            }
            case HIGH_OFFSET -> drawTwoTier(g, x0, x1, midY, 8, color, 3);
            case CUSTOM -> {
                if (useDefaults) {
                    // タイル用: デフォルト的な見た目 (= TWO_TIER 風、シンプルに)
                    drawTwoTier(g, x0, x1, midY, 5, color, 3);
                } else {
                    drawCustomPreviewWithParams(g, x0, x1, midY,
                            customThickness.value(), customTrolleyOffset.value(),
                            customDropperInterval.value(), customRowCount);
                }
            }
        }
    }

    private void drawCustomPreviewWithParams(GuiGraphics g, int x0, int x1, int centerY,
                                               float thickness, float trolleyOffset,
                                               float dropperInterval, int rowCount) {
        int color = WIRE_PREVIEW_COLOR;
        int halfOffsetPx = Math.max(1, Math.round(trolleyOffset * 7f));
        int dropperCount = Math.max(1, Math.min(10, Math.round(10f / Math.max(0.5f, dropperInterval))));
        if (rowCount == 1) {
            if (trolleyOffset <= 0.01f) drawHLine(g, x0, x1, centerY, color);
            else drawTwoTier(g, x0, x1, centerY, halfOffsetPx, color, dropperCount);
        } else {
            int hh = Math.max(1, halfOffsetPx - 2);
            if (trolleyOffset <= 0.01f) {
                drawHLine(g, x0, x1, centerY - 7, color);
                drawHLine(g, x0, x1, centerY + 7, color);
            } else {
                drawTwoTier(g, x0, x1, centerY - 7, hh, color, dropperCount);
                drawTwoTier(g, x0, x1, centerY + 7, hh, color, dropperCount);
            }
        }
    }

    private static void drawTwoTier(GuiGraphics g, int x0, int x1, int centerY, int halfHeight,
                                      int color, int dropperCount) {
        int top = centerY - halfHeight;
        int bot = centerY + halfHeight;
        drawHLine(g, x0, x1, top, color);
        drawHLine(g, x0, x1, bot, color);
        int n = Math.max(1, dropperCount);
        int step = (x1 - x0) / (n + 1);
        for (int i = 1; i <= n; i++) {
            int dx = x0 + step * i;
            drawVLine(g, dx, top, bot, color);
        }
    }

    private static void drawHLine(GuiGraphics g, int x0, int x1, int y, int color) {
        g.fill(x0, y, x1, y + 1, color);
    }

    private static void drawVLine(GuiGraphics g, int x, int y0, int y1, int color) {
        g.fill(x, y0, x + 1, y1, color);
    }
}
