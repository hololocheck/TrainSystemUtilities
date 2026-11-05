package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeName;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 架線接続ツールのカスタムプリセット操作 — the tools host's {@code wire-preset} action ({@link ToolsData};
 * WireConnectorPresetPayload until MANTA_7_CONCEPT C4). 範囲 (action 0..16, index 0..4096, name 384 bytes) は
 * tools-state.json の宣言。
 *
 * <p>action:
 * <ul>
 *   <li>{@link #ACTION_SAVE} — 現在のカスタムパラメータを {@code name} でプリセット追加</li>
 *   <li>{@link #ACTION_DELETE} — {@code presetIndex} のプリセットを削除</li>
 *   <li>{@link #ACTION_APPLY} — {@code presetIndex} のプリセットを現在のカスタム値に適用</li>
 * </ul>
 */
public final class WireConnectorPresetAction {

    public static final int ACTION_SAVE = 0;
    public static final int ACTION_DELETE = 1;
    public static final int ACTION_APPLY = 2;

    /**
     * The save dialog's name input holds this many code points. tools-state.json declares the name 12 bytes per
     * code point: the codec counts UTF-8 bytes after NFC, which grows a code point to at most three four-byte ones.
     */
    public static final int MAX_NAME_CODE_POINTS = 32;

    /** tools-state.json's bound of the preset index (the payload's decoder's). */
    public static final int MAX_INDEX = 4096;

    /** SECURITY (TSU-18): item NBT に保存される preset 数の上限 (無制限 append による NBT 肥大化防止)。 */
    private static final int MAX_PRESETS = 64;

    private WireConnectorPresetAction() {}

    public static void apply(ServerPlayer sp, int action, int presetIndex, String name) {
        ItemStack stack = findTool(sp);
        if (stack.isEmpty()) return;

        List<WireConnectorItem.Preset> presets = WireConnectorItem.readPresets(stack);

        switch (action) {
            case ACTION_SAVE -> {
                if (presets.size() >= MAX_PRESETS) {
                    sp.displayClientMessage(Component.translatable("tsu.wire_connector.preset_limit_fmt", MAX_PRESETS)
                            .withStyle(ChatFormatting.RED), true);
                    return;
                }
                String n = name == null || name.trim().isEmpty()
                        ? Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString()
                        : name.trim();
                // P0-4 #7: preset 名は NBT に書き込まれる → SafeName 検証、不正ならデフォルト名にフォールバック
                try {
                    SafeName.validate(n);
                } catch (IllegalArgumentException ex) {
                    n = Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString();
                }
                presets.add(new WireConnectorItem.Preset(
                        n,
                        WireConnectorItem.readCustomThickness(stack),
                        WireConnectorItem.readCustomTrolleyOffset(stack),
                        WireConnectorItem.readCustomDropperInterval(stack),
                        WireConnectorItem.readCustomRowCount(stack)));
                WireConnectorItem.writePresets(stack, presets);
                sp.displayClientMessage(Component.translatable("tsu.wire_connector.preset_saved_fmt", n)
                        .withStyle(ChatFormatting.AQUA), true);
            }
            case ACTION_DELETE -> {
                if (presetIndex < 0 || presetIndex >= presets.size()) return;
                WireConnectorItem.Preset removed = presets.remove(presetIndex);
                WireConnectorItem.writePresets(stack, presets);
                sp.displayClientMessage(Component.translatable("tsu.wire_connector.preset_removed_fmt", removed.name())
                        .withStyle(ChatFormatting.GRAY), true);
            }
            case ACTION_APPLY -> {
                if (presetIndex < 0 || presetIndex >= presets.size()) return;
                WireConnectorItem.Preset p = presets.get(presetIndex);
                WireConnectorItem.writeCustomThickness(stack, p.thickness());
                WireConnectorItem.writeCustomTrolleyOffset(stack, p.trolleyOffset());
                WireConnectorItem.writeCustomDropperInterval(stack, p.dropperInterval());
                WireConnectorItem.writeCustomRowCount(stack, p.rowCount());
                // CUSTOM 選択も同時に行う
                WireConnectorItem.writeWireType(stack, com.trainsystemutilities.electrification.wire.WireType.CUSTOM);
                sp.displayClientMessage(Component.translatable("tsu.wire_connector.preset_applied_fmt", p.name())
                        .withStyle(ChatFormatting.AQUA), true);
            }
            default -> { /* ignore */ }
        }
    }

    private static ItemStack findTool(ServerPlayer sp) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = sp.getItemInHand(hand);
            if (s.is(ModItems.WIRE_CONNECTOR.get())) return s;
        }
        return ItemStack.EMPTY;
    }
}
