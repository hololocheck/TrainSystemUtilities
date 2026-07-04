package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 架線柱自動配置ツールの詳細設定 (= GUI screen) からの設定値書き戻し。
 *
 * <p>field: 0=Height, 1=Clearance, 2=Span, 3=ScanRange, 4=SkipStation (boolean → 0/1)。
 * value: clamp は server 側で実施。
 */
public record OverheadPoleAutoSettingsPayload(int field, int value) implements CustomPacketPayload {

    public static final Type<OverheadPoleAutoSettingsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "overhead_pole_auto_settings"));

    public static final StreamCodec<FriendlyByteBuf, OverheadPoleAutoSettingsPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeVarInt(p.field); buf.writeVarInt(p.value); },
                    buf -> {
                        int field = buf.readVarInt();
                        int value = buf.readVarInt();
                        // APPLY (= 100) を許容するため field の上限を 127 に拡張
                        if (field < 0 || field > 127) {
                            throw new IllegalArgumentException("field " + field + " out of [0, 127]");
                        }
                        if (value < -1024 || value > 1024) {
                            throw new IllegalArgumentException("value " + value + " out of [-1024, 1024]");
                        }
                        return new OverheadPoleAutoSettingsPayload(field, value);
                    });

    public static final int FIELD_HEIGHT = 0;
    public static final int FIELD_CLEARANCE = 1;
    public static final int FIELD_SPAN = 2;
    public static final int FIELD_SCAN_RANGE = 3;     // legacy, unused
    public static final int FIELD_SKIP_STATION = 4;   // legacy, 常時 ON
    public static final int FIELD_CANTILEVER = 5;
    public static final int FIELD_PLACE_TRUSS = 6;
    public static final int FIELD_PLACE_INSULATOR = 7;
    public static final int FIELD_MULTI_TRACK_COUNT = 8;
    /** action: 「この設定にする」 ボタン → tool mode を SELECTION に切り替え、 SELECTED_TRACKS clear。 */
    public static final int FIELD_APPLY_AND_ENTER_PLACEMENT = 100;

    public static void handle(OverheadPoleAutoSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            ItemStack stack = findHeldTool(player);
            if (stack.isEmpty()) return;
            switch (payload.field) {
                case FIELD_HEIGHT             -> AutoPlaceConfig.setHeight(stack, payload.value);
                case FIELD_CLEARANCE          -> AutoPlaceConfig.setClearance(stack, payload.value);
                case FIELD_SPAN               -> AutoPlaceConfig.setSpan(stack, payload.value);
                case FIELD_CANTILEVER         -> AutoPlaceConfig.setCantilever(stack, payload.value != 0);
                case FIELD_PLACE_TRUSS        -> AutoPlaceConfig.setPlaceTruss(stack, payload.value != 0);
                case FIELD_PLACE_INSULATOR    -> AutoPlaceConfig.setPlaceInsulator(stack, payload.value != 0);
                case FIELD_MULTI_TRACK_COUNT  -> AutoPlaceConfig.setMultiTrackCount(stack, payload.value);
                case FIELD_APPLY_AND_ENTER_PLACEMENT -> {
                    // GUI 閉じ + 設置モード移行 (= TOOL_MODE_SELECTION) + 選択 track 列をリセット
                    AutoPlaceConfig.setToolMode(stack, AutoPlaceConfig.TOOL_MODE_SELECTION);
                    stack.set(com.trainsystemutilities.registry.ModDataComponents.AUTO_POLE_SELECTED_TRACKS.get(),
                            java.util.List.of());
                    stack.set(com.trainsystemutilities.registry.ModDataComponents.AUTO_POLE_SELECTED_COUNT.get(), 0);
                    if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                        sp.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "設置モードに移行 — 複線数 " + AutoPlaceConfig.getMultiTrackCount(stack)
                                                + " 本の線路を順に右クリックで選択")
                                        .withStyle(net.minecraft.ChatFormatting.AQUA), false);
                    }
                }
            }
        });
    }

    private static ItemStack findHeldTool(net.minecraft.world.entity.player.Player player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.OVERHEAD_POLE_AUTO_TOOL.get());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
