package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.transit.TransitTerminalState;
import com.trainsystemutilities.station.routing.ComposedRouteFinder;
import com.trainsystemutilities.station.routing.TrainRouter;
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
 * Server → Client: 経路検索結果 push (上位 K 候補対応)。
 *
 * <p>{@link RouteData} のリストを送信。最初の要素 (index 0) が primary。
 * 全候補空 + reason のみのケースもありうる (= no route found)。
 */
public record TransitResultPayload(
        List<RouteData> routes,
        String reason,
        long serverDayTime
) implements CustomPacketPayload {

    public record RouteData(
            int totalTicks,
            UUID fromGroupId,
            String fromGroupName,
            UUID toGroupId,
            String toGroupName,
            int walkToFromTicks,
            String legsSerialized
    ) {}

    public static final Type<TransitResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "transit_result"));

    public static final StreamCodec<FriendlyByteBuf, TransitResultPayload> STREAM_CODEC =
            StreamCodec.of(TransitResultPayload::write, TransitResultPayload::read);

    private static void write(FriendlyByteBuf buf, TransitResultPayload p) {
        buf.writeUtf(p.reason == null ? "" : p.reason, 256);
        buf.writeLong(p.serverDayTime);
        buf.writeVarInt(p.routes.size());
        for (RouteData r : p.routes) {
            buf.writeVarInt(r.totalTicks);
            buf.writeBoolean(r.fromGroupId != null);
            if (r.fromGroupId != null) buf.writeUUID(r.fromGroupId);
            buf.writeUtf(r.fromGroupName == null ? "" : r.fromGroupName, 64);
            buf.writeBoolean(r.toGroupId != null);
            if (r.toGroupId != null) buf.writeUUID(r.toGroupId);
            buf.writeUtf(r.toGroupName == null ? "" : r.toGroupName, 64);
            buf.writeVarInt(r.walkToFromTicks);
            buf.writeUtf(r.legsSerialized == null ? "" : r.legsSerialized, 4096);
        }
    }

    private static TransitResultPayload read(FriendlyByteBuf buf) {
        // P0-4 #7: reason is a short failure label; 256 bytes matches write side cap.
        String reason = BoundedStreamCodec.readBoundedUtf(buf, 256);
        long dt = buf.readLong();
        // P0-4 #7: top-K route candidates capped at 256 (UI shows few; 256 is generous safety margin).
        int n = BoundedStreamCodec.readBoundedListLength(buf, 256);
        List<RouteData> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // P0-4 #7 hotfix3: write は writeVarInt → read も readVarInt 必須 (encoding 整合)。
            int total = buf.readVarInt();
            UUID from = buf.readBoolean() ? buf.readUUID() : null;
            // P0-4 #7: group display name; 128 bytes generic-name budget (write caps at 64 but SafeName default is 128).
            String fromName = BoundedStreamCodec.readBoundedUtf(buf, 128);
            UUID to = buf.readBoolean() ? buf.readUUID() : null;
            // P0-4 #7: group display name; 128 bytes generic-name budget.
            String toName = BoundedStreamCodec.readBoundedUtf(buf, 128);
            // P0-4 #7 hotfix3: walk も同様に writeVarInt → readVarInt 整合。
            int walk = buf.readVarInt();
            // P0-4 #7: legsSerialized is a packed string of leg tokens; 4096 bytes = serialized-config budget.
            String legs = BoundedStreamCodec.readBoundedUtf(buf, 4096);
            list.add(new RouteData(total, from, fromName, to, toName, walk, legs));
        }
        return new TransitResultPayload(list, reason, dt);
    }

    public static void handle(TransitResultPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> {
            List<ComposedRouteFinder.ComposedRoute> routes = new ArrayList<>();
            for (RouteData rd : payload.routes) {
                List<TrainRouter.Leg> legs = parseLegs(rd.legsSerialized);
                ComposedRouteFinder.WalkLeg walk = rd.walkToFromTicks > 0
                        ? new ComposedRouteFinder.WalkLeg(rd.walkToFromTicks, rd.walkToFromTicks, null, null)
                        : null;
                routes.add(new ComposedRouteFinder.ComposedRoute(
                        true, rd.totalTicks, walk,
                        rd.fromGroupId, rd.fromGroupName,
                        rd.toGroupId, rd.toGroupName,
                        legs, "ok"));
            }
            if (routes.isEmpty()) {
                routes.add(new ComposedRouteFinder.ComposedRoute(
                        false, Integer.MAX_VALUE, null, null, "", null, "",
                        List.of(), payload.reason));
            }
            TransitTerminalState.setLastResults(routes, payload.serverDayTime);
        });
    }

    public static List<TrainRouter.Leg> parseLegs(String s) {
        List<TrainRouter.Leg> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] tok = part.split("\\|", -1);
            if (tok.length < 4) continue;
            try {
                UUID from = UUID.fromString(tok[0]);
                UUID to = UUID.fromString(tok[1]);
                UUID train = tok[2].isEmpty() ? null : UUID.fromString(tok[2]);
                int travel = Integer.parseInt(tok[3]);
                int dep = tok.length > 4 ? Integer.parseInt(tok[4]) : 0;
                int board = tok.length > 5 ? Integer.parseInt(tok[5]) : 0;
                int alight = tok.length > 6 ? Integer.parseInt(tok[6]) : 0;
                String symLetters = tok.length > 7 ? tok[7] : "";
                int symNumber = tok.length > 8 ? Integer.parseInt(tok[8]) : -1;
                String symColor = tok.length > 9 ? tok[9] : "";
                int samples = tok.length > 10 ? Integer.parseInt(tok[10]) : 0;
                int stdDev = tok.length > 11 ? Integer.parseInt(tok[11]) : 0;
                int delay = tok.length > 12 ? Integer.parseInt(tok[12]) : 0;
                out.add(new TrainRouter.Leg(from, to, train, travel, dep, board, alight,
                        symLetters, symNumber, symColor, samples, stdDev, delay));
            } catch (Exception ignored) { TrainSystemUtilities.LOGGER.debug("[Transit] leg token parse failed", ignored); }
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
