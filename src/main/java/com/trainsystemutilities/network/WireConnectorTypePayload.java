package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.electrification.wire.WireType;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 架線接続ツールの操作。
 *
 * <p>{@code action} で操作種別を切り替える:
 * <ul>
 *   <li>{@link #ACTION_MODE_CYCLE} — Alt+ホイールでツールモード循環</li>
 *   <li>{@link #ACTION_TYPE_SELECT} — GUI でデザインタイプを選択 ({@code value} = WireType.id)</li>
 *   <li>{@link #ACTION_SAG_TOGGLE} — SIMPLE 線のたるみモードを設定 ({@code value} = 1 で ON、0 で OFF)</li>
 * </ul>
 */
public record WireConnectorTypePayload(int action, int value) implements CustomPacketPayload {

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

    public static final Type<WireConnectorTypePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "wire_connector_type"));

    public static final StreamCodec<FriendlyByteBuf, WireConnectorTypePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeVarInt(p.action); buf.writeVarInt(p.value); },
                    // P0-4 #7: action は 0..6 の固定 enum (writeVarInt 経由)
                    //   value は太さ等 *1000 / WireType id / トロリオフセット (負数可) で
                    //   varint 経由のため manual 範囲チェック (BoundedStreamCodec は負数 varint 非対応)
                    buf -> {
                        int action = BoundedStreamCodec.readBoundedVarInt(buf, 16);
                        int value = buf.readVarInt();
                        if (value < -100_000 || value > 100_000) {
                            throw new IllegalArgumentException(
                                    "WireConnectorTypePayload value " + value + " out of [-100000, 100000]");
                        }
                        return new WireConnectorTypePayload(action, value);
                    });

    public static void handle(WireConnectorTypePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            ItemStack stack = findTool(sp);
            if (stack.isEmpty()) return;

            switch (payload.action) {
                case ACTION_MODE_CYCLE -> {
                    int current = WireConnectorItem.readToolMode(stack);
                    int next = (current + payload.value + WireConnectorItem.TOOL_MODE_COUNT)
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
                    WireType type = WireType.fromId(payload.value);
                    WireConnectorItem.writeWireType(stack, type);
                    sp.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "tsu.wire_connector.msg_design_fmt", type.displayName())
                                    .withStyle(net.minecraft.ChatFormatting.AQUA),
                            true);
                }
                case ACTION_SAG_TOGGLE -> {
                    boolean sag = payload.value != 0;
                    WireConnectorItem.writeSag(stack, sag);
                    sp.displayClientMessage(
                            net.minecraft.network.chat.Component.translatable(
                                    "tsu.wire_connector.msg_sag_fmt", sag ? "ON" : "OFF")
                                    .withStyle(sag ? net.minecraft.ChatFormatting.AQUA
                                            : net.minecraft.ChatFormatting.GRAY),
                            true);
                }
                case ACTION_CUSTOM_THICKNESS -> WireConnectorItem.writeCustomThickness(stack, payload.value / 1000f);
                case ACTION_CUSTOM_TROLLEY_OFFSET -> WireConnectorItem.writeCustomTrolleyOffset(stack, payload.value / 1000f);
                case ACTION_CUSTOM_DROPPER_INTERVAL -> WireConnectorItem.writeCustomDropperInterval(stack, payload.value / 1000f);
                case ACTION_CUSTOM_ROW_COUNT -> WireConnectorItem.writeCustomRowCount(stack, payload.value);
                default -> { /* ignore unknown action */ }
            }
        });
    }

    private static ItemStack findTool(ServerPlayer sp) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = sp.getItemInHand(hand);
            if (s.is(ModItems.WIRE_CONNECTOR.get())) return s;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
