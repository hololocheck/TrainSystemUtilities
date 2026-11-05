package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The trains' live positions on manta:data (MANTA_7_CONCEPT C4, TSU slice T5): what TrainPositionPayload broadcast to
 * every player is one server-wide host's list, declared by the state-only document {@link #DOCUMENT}, set by
 * {@link TrainPositionBroadcaster}; every client subscribes at login (client.TransitClient), as the broadcast reached
 * everyone. The document has no float: x and z ride as sixteenths of a block, the speed as thousandths of a block per
 * tick, and the day time (a long) as two ints.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class TrainPositionsData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/train-positions-state.json";
    static final String CHANNEL = TrainSystemUtilities.MOD_ID + ":train-positions@server";

    /** train-positions-state.json's maxLength (the broadcast stopped at 256 as its packet guard). */
    public static final int ROWS = 256;
    /** Sixteenths of a block; the declared x / z bound is 2^29 of them (the world border is 30 million blocks). */
    static final int FIXED = 16;
    static final int FIXED_MIN = -536870912;
    static final int FIXED_MAX = 536870911;
    /** Thousandths of a block per tick. */
    static final int SPEED = 1000;

    /** A train's position as the renderers read it (TrainPositionPayload.Position until C4). */
    public record TrainPosition(UUID trainId, float x, float z, float speed, int etaToNextTicks) {
    }

    private static Schema schema;
    /** The open host; server thread only (start, stop and the broadcaster run there). */
    @Nullable
    private static Host host;

    private TrainPositionsData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel() {
        return CHANNEL;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        host = MantaData.host(event.getServer(), CHANNEL, schema(), player -> true);
    }

    /** Manta closes every host with the server; this one is forgotten so the next server opens its own. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        host = null;
    }

    /** The positions as they are now, at most {@link #ROWS} (the caller's order). */
    static void publish(List<TrainPosition> positions, long dayTime) {
        Host h = host;
        if (h == null) {
            return;
        }
        h.set("pos-day-hi", (int) (dayTime >>> 32));
        h.set("pos-day-lo", (int) dayTime);
        h.setList("positions", rows(positions));
    }

    /** The positions as list rows, each value within its declaration. */
    static List<List<Object>> rows(List<TrainPosition> positions) {
        List<List<Object>> out = new ArrayList<>(Math.min(positions.size(), ROWS));
        for (TrainPosition p : positions) {
            if (out.size() == ROWS) {
                break;
            }
            out.add(List.<Object>of(p.trainId().toString(), fixed(p.x()), fixed(p.z()), toInt(Math.round(
                    (double) p.speed() * SPEED)), p.etaToNextTicks()));
        }
        return out;
    }

    /** A positions list read back; a row whose id does not parse is left out. */
    public static List<TrainPosition> positionsOf(List<?> rows) {
        List<TrainPosition> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            UUID id = ManagementComputerData.parseUuid((String) r.get(0));
            if (id == null) {
                continue;
            }
            out.add(new TrainPosition(id, (Integer) r.get(1) / (float) FIXED, (Integer) r.get(2) / (float) FIXED,
                    (Integer) r.get(3) / (float) SPEED, (Integer) r.get(4)));
        }
        return out;
    }

    static int fixed(float v) {
        return (int) Math.max(FIXED_MIN, Math.min(FIXED_MAX, Math.round((double) v * FIXED)));
    }

    static int toInt(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }
}
