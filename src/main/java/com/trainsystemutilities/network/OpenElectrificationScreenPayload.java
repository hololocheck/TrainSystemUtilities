package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.ElectrificationDetailScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: 電化詳細スクリーンを開く trigger payload。
 *
 * <p>{@code /tsu-pantograph open} コマンド等から発行され、クライアント側で
 * {@link ElectrificationDetailScreen} をセットする。
 */
public record OpenElectrificationScreenPayload(UUID trainId) implements CustomPacketPayload {

    public static final Type<OpenElectrificationScreenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "open_electrification_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenElectrificationScreenPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.trainId),
                    buf -> new OpenElectrificationScreenPayload(buf.readUUID()));

    public static void handle(OpenElectrificationScreenPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().execute(
                () -> ElectrificationDetailScreen.open(payload.trainId)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
