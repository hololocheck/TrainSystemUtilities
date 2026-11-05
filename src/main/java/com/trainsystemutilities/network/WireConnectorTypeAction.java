package com.trainsystemutilities.network;

import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.electrification.wire.WireType;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 架線接続ツールの操作 — the tools host's {@code wire-type} action ({@link ToolsData}; WireConnectorTypePayload until
 * MANTA_7_CONCEPT C4). 範囲 (action 0..16, value ±100000) は tools-state.json の宣言。
 *
 * <p>{@code action} で操作種別を切り替える:
 * <ul>
 *   <li>{@link #ACTION_MODE_CYCLE} — Alt+ホイールでツールモード循環</li>
 *   <li>{@link #ACTION_TYPE_SELECT} — GUI でデザインタイプを選択 ({@code value} = WireType.id)</li>
 *   <li>{@link #ACTION_SAG_TOGGLE} — SIMPLE 線のたるみモードを設定 ({@code value} = 1 で ON、0 で OFF)</li>
 * </ul>
 */
public final class WireConnectorTypeAction {

    public static final int ACTION_MODE_CYCLE = 0;
    public static final int ACTION_TYPE_SELECT = 1;
    public static final int ACTION_SAG_TOGGLE = 2;
    /** CUSTOM: 太さ (= value は thickness*1000 を int で送信、サーバ側で /1000 して float)。 */
    public static final int ACTION_CUSTOM_THICKNESS = 3;
    /** CUSTOM: トロリオフセット (= value は offset*1000)。 */
    public static final int ACTION_CUSTOM_TROLLEY_OFFSET = 4;
    /** CUSTOM: ドロッパ間隔 (= value は interval*1000)。 */
    public static final int ACTION_CUSTOM_DROPPER_INTERVAL = 5;
    /** CUSTOM: 列数 (= value は 1 or 2)。 */
    public static final int ACTION_CUSTOM_ROW_COUNT = 6;

    private WireConnectorTypeAction() {}

    public static void apply(ServerPlayer sp, int action, int value) {
        ItemStack stack = findTool(sp);
        if (stack.isEmpty()) return;

        switch (action) {
            case ACTION_MODE_CYCLE -> {
                int current = WireConnectorItem.readToolMode(stack);
                int next = (current + value + WireConnectorItem.TOOL_MODE_COUNT)
                        % WireConnectorItem.TOOL_MODE_COUNT;
                WireConnectorItem.writeToolMode(stack, next);
                sp.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "tsu.wire_connector.msg_toolmode_fmt",
                                WireConnectorItem.toolModeLabel(next))
                                .withStyle(next == WireConnectorItem.TOOL_MODE_GUI
                                        ? net.minecraft.ChatFormatting.GOLD
                                        : net.minecraft.ChatFormatting.AQUA),
                        true);
            }
            case ACTION_TYPE_SELECT -> {
                WireType type = WireType.fromId(value);
                WireConnectorItem.writeWireType(stack, type);
                sp.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "tsu.wire_connector.msg_design_fmt", type.displayName())
                                .withStyle(net.minecraft.ChatFormatting.AQUA),
                        true);
            }
            case ACTION_SAG_TOGGLE -> {
                boolean sag = value != 0;
                WireConnectorItem.writeSag(stack, sag);
                sp.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "tsu.wire_connector.msg_sag_fmt", sag ? "ON" : "OFF")
                                .withStyle(sag ? net.minecraft.ChatFormatting.AQUA
                                        : net.minecraft.ChatFormatting.GRAY),
                        true);
            }
            case ACTION_CUSTOM_THICKNESS -> WireConnectorItem.writeCustomThickness(stack, value / 1000f);
            case ACTION_CUSTOM_TROLLEY_OFFSET -> WireConnectorItem.writeCustomTrolleyOffset(stack, value / 1000f);
            case ACTION_CUSTOM_DROPPER_INTERVAL -> WireConnectorItem.writeCustomDropperInterval(stack, value / 1000f);
            case ACTION_CUSTOM_ROW_COUNT -> WireConnectorItem.writeCustomRowCount(stack, value);
            default -> { /* ignore unknown action */ }
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
