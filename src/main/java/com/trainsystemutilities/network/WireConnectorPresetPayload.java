package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.item.WireConnectorItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 架線接続ツールのカスタムプリセット操作。
 *
 * <p>action:
 * <ul>
 *   <li>{@link #ACTION_SAVE} — 現在のカスタムパラメータを {@code name} でプリセット追加</li>
 *   <li>{@link #ACTION_DELETE} — {@code presetIndex} のプリセットを削除</li>
 *   <li>{@link #ACTION_APPLY} — {@code presetIndex} のプリセットを現在のカスタム値に適用</li>
 * </ul>
 */
public record WireConnectorPresetPayload(int action, int presetIndex, String name) implements CustomPacketPayload {

    public static final int ACTION_SAVE = 0;
    public static final int ACTION_DELETE = 1;
    public static final int ACTION_APPLY = 2;

    /** SECURITY (TSU-18): item NBT に保存される preset 数の上限 (無制限 append による NBT 肥大化防止)。 */
    private static final int MAX_PRESETS = 64;

    public static final Type<WireConnectorPresetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "wire_preset"));

    public static final StreamCodec<FriendlyByteBuf, WireConnectorPresetPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.action);
                        buf.writeVarInt(p.presetIndex);
                        buf.writeUtf(p.name == null ? "" : p.name, 32);
                    },
                    buf -> new WireConnectorPresetPayload(
                            // P0-4 #7: action は ACTION_SAVE/DELETE/APPLY の 0..2 のみ (writeVarInt 経由)
                            BoundedStreamCodec.readBoundedVarInt(buf, 16),
                            // P0-4 #7: preset index は最大数千個程度 (writeVarInt 経由)
                            BoundedStreamCodec.readBoundedVarInt(buf, 4096),
                            // P0-4 #7: preset name は generic label → 128 bytes
                            BoundedStreamCodec.readBoundedUtf(buf, 128)));

    public static void handle(WireConnectorPresetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            ItemStack stack = findTool(sp);
            if (stack.isEmpty()) return;

            List<WireConnectorItem.Preset> presets = WireConnectorItem.readPresets(stack);

            switch (payload.action) {
                case ACTION_SAVE -> {
                    if (presets.size() >= MAX_PRESETS) {
                        sp.displayClientMessage(Component.literal(
                                "プリセット数が上限 (" + MAX_PRESETS + " 件) に達しています")
                                .withStyle(ChatFormatting.RED), true);
                        return;
                    }
                    String n = payload.name == null || payload.name.trim().isEmpty()
                            ? Component.translatable("tsu.wire_connector.preset_default_name_fmt", presets.size() + 1).getString()
                            : payload.name.trim();
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
                    if (payload.presetIndex < 0 || payload.presetIndex >= presets.size()) return;
                    WireConnectorItem.Preset removed = presets.remove(payload.presetIndex);
                    WireConnectorItem.writePresets(stack, presets);
                    sp.displayClientMessage(Component.translatable("tsu.wire_connector.preset_removed_fmt", removed.name())
                            .withStyle(ChatFormatting.GRAY), true);
                }
                case ACTION_APPLY -> {
                    if (payload.presetIndex < 0 || payload.presetIndex >= presets.size()) return;
                    WireConnectorItem.Preset p = presets.get(payload.presetIndex);
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
