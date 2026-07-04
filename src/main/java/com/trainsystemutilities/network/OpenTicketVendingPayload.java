package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 券売機 UI を開く trigger payload。
 *
 * <p>{@link com.trainsystemutilities.structure.block.TicketVendingMachineBlock#useWithoutItem}
 * から発行する。 サーバ側で「発駅名 + 販売可な行き先一覧」を計算してクライアントへ渡し、
 * クライアントで {@link com.trainsystemutilities.client.gui.TicketVendingMachineScreen} を開く。
 */
public record OpenTicketVendingPayload(BlockPos machinePos, String originName, List<Dest> destinations)
        implements CustomPacketPayload {

    /** 行き先 1 件 (= 着駅グループ)。 id はクリック発券時に server へ送る。 */
    public record Dest(UUID id, String name) {}

    public static final Type<OpenTicketVendingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "open_ticket_vending"));

    public static final StreamCodec<FriendlyByteBuf, OpenTicketVendingPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.machinePos);
                        buf.writeUtf(p.originName);
                        buf.writeVarInt(p.destinations.size());
                        for (Dest d : p.destinations) {
                            buf.writeUUID(d.id());
                            buf.writeUtf(d.name());
                        }
                    },
                    buf -> {
                        BlockPos pos = buf.readBlockPos();
                        String origin = buf.readUtf();
                        int n = buf.readVarInt();
                        List<Dest> dests = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            dests.add(new Dest(buf.readUUID(), buf.readUtf()));
                        }
                        return new OpenTicketVendingPayload(pos, origin, dests);
                    });

    public static void handle(OpenTicketVendingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().execute(() ->
                com.trainsystemutilities.client.gui.TicketVendingMachineScreen.open(
                        payload.machinePos, payload.originName, payload.destinations)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
