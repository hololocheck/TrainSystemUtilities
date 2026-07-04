package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.TicketConfigClientCache;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 販売可な着駅 (sellable) + この管理用コンピューターが管理するネットワークの駅グループ
 * (networkGroups) を送信。 クライアントは {@link TicketConfigClientCache} に格納して券売機タブで使う
 * (= 切符タブは networkGroups の駅だけ表示し、 sellable を per-group トグルする)。
 */
public record TicketConfigSyncPayload(List<UUID> sellable, List<UUID> networkGroups) implements CustomPacketPayload {

    public static final Type<TicketConfigSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "ticket_config_sync"));

    public static final StreamCodec<FriendlyByteBuf, TicketConfigSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeVarInt(p.sellable.size());
                        for (UUID id : p.sellable) buf.writeUUID(id);
                        buf.writeVarInt(p.networkGroups.size());
                        for (UUID id : p.networkGroups) buf.writeUUID(id);
                    },
                    buf -> {
                        int n = BoundedStreamCodec.readBoundedListLength(buf, 256);
                        List<UUID> sell = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) sell.add(buf.readUUID());
                        int m = BoundedStreamCodec.readBoundedListLength(buf, 256);
                        List<UUID> net = new ArrayList<>(m);
                        for (int i = 0; i < m; i++) net.add(buf.readUUID());
                        return new TicketConfigSyncPayload(sell, net);
                    });

    public static void handle(TicketConfigSyncPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() ->
                TicketConfigClientCache.replaceAll(payload.sellable, payload.networkGroups));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
