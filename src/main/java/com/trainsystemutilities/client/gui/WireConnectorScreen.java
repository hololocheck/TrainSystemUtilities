package com.trainsystemutilities.client.gui;
import belugalab.mcss3.anim.Animation;

import belugalab.mcss3.screen.JsonLayoutPlainScreen;
import belugalab.experience.controller.DropdownController;
import belugalab.experience.controller.NumberWheelInput;
import belugalab.experience.controller.ScrollViewport;
import belugalab.experience.controller.TextInputController;
import belugalab.experience.controller.ToggleSwitchController;
import belugalab.experience.render.TextCaretRenderer;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireType;
import com.trainsystemutilities.network.WireConnectorPresetPayload;
import com.trainsystemutilities.network.WireConnectorRefillOpenPayload;
import com.trainsystemutilities.network.WireConnectorTypePayload;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
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
    private boolean showApplyConfirm = false;
    private boolean showSaveDialog = false;
    /** プリセット保存ダイアログの名前入力。Enter で保存、Esc でダイアログ閉じ。 */
    private final TextInputController saveNameInput =
            new TextInputController(32, "")
                    .onSubmit(() -> onElementClick(new String[]{"preset-save-ok"}, 0, 0, 0))
                    .onEscape(this::closeSaveDialog);
    private boolean showDeleteDialog = false;
    private int deletePresetIndex = -1;

    public WireConnectorScreen() {
        super(Component.translatable("tsu.wire_connector.title"));
        ItemStack tool = findTool();
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
                .onChange(v -> sendCustomFloatPayload(WireConnectorTypePayload.ACTION_CUSTOM_THICKNESS, v))
                .enabledWhen(this::isCustomEditing);
        this.customTrolleyOffset = new NumberWheelInput(initOffset, 0f, 2.0f, 0.05f, "%.2fm")
                .onChange(v -> sendCustomFloatPayload(WireConnectorTypePayload.ACTION_CUSTOM_TROLLEY_OFFSET, v))
                .enabledWhen(this::isCustomEditing);
        this.customDropperInterval = new NumberWheelInput(initDropper, 0.5f, 10.0f, 0.25f, "%.2fm")
                .onChange(v -> sendCustomFloatPayload(WireConnectorTypePayload.ACTION_CUSTOM_DROPPER_INTERVAL, v))
                .enabledWhen(this::isCustomEditing);
        this.presets = tool != null
                ? new ArrayList<>(WireConnectorItem.readPresets(tool)) : new ArrayList<>();
    }

    /** CUSTOM パラメータが現在編集可能か (= CUSTOM 選択中かつプリセット未適用)。 */
    private boolean isCustomEditing() {
        return selected == WireType.CUSTOM && currentPresetName == null;
    }

    /** CUSTOM float パラメータをサーバへ送る (= NumberWheelInput.onChange から)。 */
    private void sendCustomFloatPayload(int action, float value) {
        PacketDistributor.sendToServer(new WireConnectorTypePayload(action, Math.round(value * 1000)));
    }

    @Override
    protected String layoutJson() {
        return TsuLayouts.load("layouts/wire-connector.json");
    }

    @Override
    protected String overlayJson() {
        if (showApplyConfirm) return TsuLayouts.load("layouts/wire-connector-confirm.json");
        if (showSaveDialog) return TsuLayouts.load("layouts/wire-connector-preset-save.json");
        if (showDeleteDialog) return TsuLayouts.load("layouts/wire-connector-preset-delete.json");
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

    @Override
    public belugalab.mcss3.anim.Animation getDynamicAnimation(String[] classes, String key) {
        if ("filter-menu-open".equals(key)) {
            // dropdown 展開アニメ (= train-preset-browse の mode-menu と同じバウンス)
            return belugalab.mcss3.anim.Animation.dropdownDown(220, 46);
        }
        return super.getDynamicAnimation(classes, key);
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

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        // BelugaExperience 標準ヘッダ部品 (R4.17): hint / wiki本 を先に処理 (× は下の switch)
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        for (String c : classes) {
            if ("wiki-btn".equals(c)) {
                String pid = wikiPageId();
                if (pid != null && !pid.isEmpty()) belugalab.mcss3.wiki.Wiki.open(pid);
                return;
            }
        }
        // Apply 確認ダイアログ
        if (showApplyConfirm) {
            for (String c : classes) {
                if ("confirm-ok".equals(c)) { apply(); showApplyConfirm = false; onClose(); return; }
                if ("confirm-cancel".equals(c)) { showApplyConfirm = false; return; }
            }
            return;
        }
        // Save ダイアログ
        if (showSaveDialog) {
            for (String c : classes) {
                if ("preset-save-ok".equals(c)) {
                    String name = saveNameInput.value().trim();
                    PacketDistributor.sendToServer(new WireConnectorPresetPayload(
                            WireConnectorPresetPayload.ACTION_SAVE, -1, name));
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
        // Delete ダイアログ
        if (showDeleteDialog) {
            for (String c : classes) {
                if ("preset-delete-ok".equals(c)) {
                    if (deletePresetIndex >= 0 && deletePresetIndex < presets.size()) {
                        PacketDistributor.sendToServer(new WireConnectorPresetPayload(
                                WireConnectorPresetPayload.ACTION_DELETE, deletePresetIndex, ""));
                        presets.remove(deletePresetIndex);
                        // スクロールが端を超えないように補正
                        tileScroll.clamp();
                    }
                    deletePresetIndex = -1;
                    showDeleteDialog = false;
                    return;
                }
                if ("preset-delete-cancel".equals(c)) {
                    deletePresetIndex = -1;
                    showDeleteDialog = false;
                    return;
                }
            }
            return;
        }

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
                    if (selected == WireType.CUSTOM) {
                        saveNameInput.setValue(Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString());
                        showSaveDialog = true;
                    }
                    return;
                }
                case "confirm-btn" -> { showApplyConfirm = true; return; }
                case "wire-refill-btn" -> {
                    PacketDistributor.sendToServer(new WireConnectorRefillOpenPayload());
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
            showDeleteDialog = true;
        } else {
            // 左クリック → CUSTOM 選択 + パラメータ + プリセット名
            WireConnectorItem.Preset p = presets.get(presetIdx);
            selected = WireType.CUSTOM;
            currentPresetName = p.name();
            customThickness.setValue(p.thickness());
            customTrolleyOffset.setValue(p.trolleyOffset());
            customDropperInterval.setValue(p.dropperInterval());
            customRowCount = p.rowCount();
            PacketDistributor.sendToServer(new WireConnectorPresetPayload(
                    WireConnectorPresetPayload.ACTION_APPLY, presetIdx, ""));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dxScroll, double dyScroll) {
        if (showApplyConfirm || showSaveDialog || showDeleteDialog) {
            return super.mouseScrolled(mx, my, dxScroll, dyScroll);
        }
        // CUSTOM パラメータ調整 (= NumberWheelInput.enabledWhen で gate、プリセット選択中は値固定)
        if (hoveredElement("custom-thickness-value", mx, my) && customThickness.handleWheel(dyScroll)) return true;
        if (hoveredElement("custom-offset-value",    mx, my) && customTrolleyOffset.handleWheel(dyScroll)) return true;
        if (hoveredElement("custom-dropper-value",   mx, my) && customDropperInterval.handleWheel(dyScroll)) return true;
        // タイル一覧スクロール (= デザインパネルにホバー時)
        if (tileScroll.needsScrollbar()) {
            int[] panel = findElementByClass("scrollbar-track");
            if (panel != null) {
                int px = dialogX(), py = dialogY();
                // パネル左上 14..220, 縦 52..288 をホバー範囲とする
                if (mx >= px + 14 && mx < px + 220 && my >= py + 52 && my < py + 288) {
                    tileScroll.scroll(dyScroll > 0 ? -1 : 1);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mx, my, dxScroll, dyScroll);
    }

    // ===== text input (save dialog) =====

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        // 保存ダイアログ表示中はそちらの入力に流す。それ以外は検索ボックスに流す (= 常時アクティブ)。
        if (showSaveDialog) {
            if (saveNameInput.charTyped(codePoint)) return true;
            return super.charTyped(codePoint, modifiers);
        }
        if (!showApplyConfirm && !showDeleteDialog && searchQuery.charTyped(codePoint)) {
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
        if (!showApplyConfirm && !showDeleteDialog) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && filterDropdown.onEscape()) return true;
            if (searchQuery.keyPressed(keyCode)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 保存ダイアログを閉じて入力バッファをクリア。 */
    private void closeSaveDialog() {
        showSaveDialog = false;
        saveNameInput.clear();
    }

    private boolean hoveredElement(String className, double mx, double my) {
        int[] r = findElementByClass(className);
        if (r == null) return false;
        int x = dialogX() + r[0];
        int y = dialogY() + r[1];
        return mx >= x && mx < x + r[2] && my >= y && my < y + r[3];
    }

    /** sag トグルの setter (= state + server sync を一括化、ToggleSwitchController から呼ぶ)。 */
    private void setSagEnabled(boolean v) {
        sagEnabled = v;
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_SAG_TOGGLE, v ? 1 : 0));
    }

    /** customRowCount 1/2 トグルの setter。 */
    private void setCustomRow2(boolean v) {
        customRowCount = v ? 2 : 1;
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_CUSTOM_ROW_COUNT, customRowCount));
    }

    private void apply() {
        PacketDistributor.sendToServer(
                new WireConnectorTypePayload(WireConnectorTypePayload.ACTION_TYPE_SELECT, selected.id));
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_SAG_TOGGLE, sagEnabled ? 1 : 0));
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_CUSTOM_THICKNESS, Math.round(customThickness.value() * 1000)));
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_CUSTOM_TROLLEY_OFFSET, Math.round(customTrolleyOffset.value() * 1000)));
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_CUSTOM_DROPPER_INTERVAL, Math.round(customDropperInterval.value() * 1000)));
        PacketDistributor.sendToServer(new WireConnectorTypePayload(
                WireConnectorTypePayload.ACTION_CUSTOM_ROW_COUNT, customRowCount));
    }

    // ===== Dynamic text =====

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        for (String c : classes) {
            switch (c) {
                case "wire-tank-label" -> {
                    ItemStack tool = findTool();
                    int amount = tool == null ? 0 : WireConnectorItem.readWireTank(tool);
                    return amount + " / " + WireConnectorItem.WIRE_TANK_MAX + " m";
                }
                case "selected-name" -> {
                    // プリセット選択中なら "プリセット名 (カスタム)" 形式
                    if (currentPresetName != null && selected == WireType.CUSTOM) {
                        return currentPresetName + " (" + WireType.CUSTOM.displayName().getString() + ")";
                    }
                    return selected.displayName().getString();
                }
                case "selected-desc" -> {
                    if (currentPresetName != null && selected == WireType.CUSTOM) {
                        return Component.translatable("tsu.wire_connector.preset_loaded").getString();
                    }
                    return descFor(selected);
                }
                case "info-tier-count" -> { return tierCountFor(selected); }
                case "info-row-count" -> { return rowCountFor(selected); }
                case "info-height" -> { return heightFor(selected); }
                case "confirm-design-name" -> { return selected.displayName().getString(); }
                case "custom-thickness-value" -> { return customThickness.formatted(); }
                case "custom-offset-value" -> { return customTrolleyOffset.formatted(); }
                case "custom-dropper-value" -> { return customDropperInterval.formatted(); }
                case "custom-row-value" -> { return Component.translatable("tsu.wire_connector.row_count_fmt", customRowCount).getString(); }
                case "preset-delete-name" -> {
                    if (deletePresetIndex >= 0 && deletePresetIndex < presets.size())
                        return presets.get(deletePresetIndex).name();
                    return "";
                }
                // プリセット名入力の TEXT (= 内側 span のみが描画)。outer div には返さないことが重要 (二重描画回避)
                case "preset-name-input-value" -> { return saveNameInput.value(); }
                // 検索ボックスの TEXT (= 内側 span。display() で空時はプレースホルダ "検索…" を返す)
                case "search-box-value" -> { return searchQuery.display(); }
                case "filter-dropdown-label" -> { return filterLabel(filterDropdown.selectedIndex()); }
                case "filter-option-0-label" -> { return filterLabel(FILTER_ALL); }
                case "filter-option-1-label" -> { return filterLabel(FILTER_BASIC); }
                case "filter-option-2-label" -> { return filterLabel(FILTER_CUSTOM); }
            }
            // スロットタイル名
            if (c.startsWith("slot-") && c.endsWith("-name")) {
                int slotIdx = parseSlotIndex(c, "-name");
                if (slotIdx < 0) continue;
                TileEntry entry = entryOfSlot(slotIdx);
                if (entry == null) return "";
                return entry.name();
            }
        }
        return null;
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

    // ===== Dynamic color / number / bool =====

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        switch (key) {
            case "sag-toggle-bg":          return sagToggle.trackBg();
            case "sag-knob-bg":            return sagToggle.knobBg();
            case "sag-toggle-label-color":
                return selected == WireType.SIMPLE ? 0xFF80DEEA : 0xFF666666;
            case "custom-row-toggle-bg":   return customRowToggle.trackBg();
            case "custom-row-knob-bg":     return customRowToggle.knobBg();
            default: /* fallthrough */
        }
        // slot-N-bg / slot-N-border (= 選択中スロットを強調)
        if (key.startsWith("slot-")) {
            int slotIdx = parseSlotIndex(key, "-bg");
            if (slotIdx >= 0) {
                TileEntry entry = entryOfSlot(slotIdx);
                if (entry == null) return 0x80303030;
                return isSlotSelected(entry) ? 0xFF3B5274 : null;
            }
            slotIdx = parseSlotIndex(key, "-border");
            if (slotIdx >= 0) {
                TileEntry entry = entryOfSlot(slotIdx);
                if (entry == null) return 0x40555555;
                return isSlotSelected(entry) ? 0xFF80DEEA : null;
            }
        }
        return null;
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

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        if ("sag-knob-x".equals(key)) return sagToggle.knobX(defaultValue);
        if ("custom-row-knob-x".equals(key)) return customRowToggle.knobX(defaultValue);
        if ("scrollbar-thumb-y".equals(key)) return tileScroll.thumbY(defaultValue, 236 - 2, 20);
        if ("scrollbar-thumb-h".equals(key)) return 20;
        if ("wire-tank-fill".equals(key)) {
            ItemStack tool = findTool();
            int amount = tool == null ? 0 : WireConnectorItem.readWireTank(tool);
            int max = WireConnectorItem.WIRE_TANK_MAX;
            int pct = max == 0 ? 0 : amount * 100 / max;
            return Math.max(0, Math.min(TANK_BAR_INNER_W, TANK_BAR_INNER_W * pct / 100));
        }
        return null;
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        if ("sag-toggle-visible".equals(key)) return selected == WireType.SIMPLE;
        if ("custom-params-visible".equals(key)) return selected == WireType.CUSTOM;
        if ("scrollbar-visible".equals(key)) return tileScroll.needsScrollbar();
        if ("dropdown-open".equals(key)) return filterDropdown.isOpen();
        if ("custom-save-enabled".equals(key)) return selected == WireType.CUSTOM && currentPresetName == null;
        return null;
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
