package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player's station groups and ticket vending on manta:data (MANTA_7_CONCEPT C4): what StationGroupCreatePayload,
 * StationGroupRenamePayload, StationGroupDeletePayload and BuyTicketPayload carried in, and what
 * OpenTicketVendingPayload carried out, on one host per player (only they reach it; Manta closes it at logout),
 * declared by the state-only document {@link #DOCUMENT}. Two answers are the player's alone, each "value + seq" (the
 * first snapshot's counter is history): one group's station positions for the manage screen ({@link #answerDetail}),
 * and a vending machine to open with its destinations ({@link #openVending}).
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class StationsData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/stations-state.json";
    static final String DOC = "stations";

    public static final String CREATE = "create";
    public static final String RENAME = "rename";
    public static final String DELETE = "delete";
    public static final String DETAIL = "detail";
    public static final String BUY = "buy";

    /**
     * The name inputs (the save and the manage screens) hold this many code points. stations-state.json declares a name
     * 12 bytes per code point: the codec counts UTF-8 bytes after NFC, which grows a code point to at most three
     * four-byte ones.
     */
    public static final int NAME_CODE_POINTS = 64;
    /** stations-state.json's list sizes and the maxLen of a shown name (UTF-8 bytes). */
    static final int POSITION_ROWS = 256;
    static final int DEST_ROWS = 256;
    static final int NAME_BYTES = 128;

    private static Schema schema;
    /** The open host of each player; server thread only (login, logout and every caller run there). */
    private static final Map<UUID, Host> HOSTS = new HashMap<>();

    private StationsData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel(UUID player) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, DOC, player);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Host host = MantaData.host(player, TrainSystemUtilities.MOD_ID, DOC, schema());
        // Params in stations-state.json's declaration order.
        host.on(CREATE, (args, p) -> StationGroupCreateAction.apply(p, (String) args.get(0)));
        host.on(RENAME, (args, p) -> {
            UUID id = ManagementComputerData.parseUuid((String) args.get(0));
            if (id != null) StationGroupRenameAction.apply(p, id, (String) args.get(1));
        });
        host.on(DELETE, (args, p) -> {
            UUID id = ManagementComputerData.parseUuid((String) args.get(0));
            if (id != null) StationGroupDeleteAction.apply(p, id);
        });
        host.on(DETAIL, (args, p) -> {
            UUID id = ManagementComputerData.parseUuid((String) args.get(0));
            if (id != null) answerDetail(p, id);
        });
        host.on(BUY, (args, p) -> {
            UUID dest = ManagementComputerData.parseUuid((String) args.get(3));
            if (dest != null) {
                BuyTicketAction.apply(p, new BlockPos((Integer) args.get(0), (Integer) args.get(1),
                        (Integer) args.get(2)), dest);
            }
        });
        HOSTS.put(player.getUUID(), host);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        HOSTS.remove(event.getEntity().getUUID());
    }

    /** One group's station positions (at most {@link #POSITION_ROWS}), for the asker's manage screen. */
    static void answerDetail(ServerPlayer player, UUID groupId) {
        Host host = HOSTS.get(player.getUUID());
        StationGroup g = StationGroupSavedData.get(player.server).get(groupId);
        if (host == null || g == null) {
            return;
        }
        host.set("detail-group", groupId.toString());
        host.setList("detail-positions", positionRows(g.stationBlockPositions()));
        host.set("detail-seq", ((Integer) host.get("detail-seq") + 1) & 0xFFFF);
    }

    static List<List<Object>> positionRows(List<BlockPos> positions) {
        List<List<Object>> rows = new ArrayList<>(Math.min(positions.size(), POSITION_ROWS));
        for (BlockPos p : positions) {
            if (rows.size() == POSITION_ROWS) break;
            rows.add(List.<Object>of(p.getX(), p.getY(), p.getZ()));
        }
        return rows;
    }

    /**
     * A ticket vending machine the player used, with what it sells (OpenTicketVendingPayload until MANTA_7_CONCEPT
     * C4): the client opens the machine's screen when the counter moves. At most {@link #DEST_ROWS} destinations,
     * sorted by the caller; the names are shown, cut to {@link #NAME_BYTES}.
     */
    public static void openVending(ServerPlayer player, BlockPos machine, String originName, List<VendingDest> dests) {
        Host host = HOSTS.get(player.getUUID());
        if (host == null) {
            return;
        }
        if (dests.size() > DEST_ROWS) {
            TrainSystemUtilities.LOGGER.warn("[Ticket] {} destinations; the machine lists the first {}", dests.size(),
                    DEST_ROWS);
        }
        host.set("vending-x", machine.getX());
        host.set("vending-y", machine.getY());
        host.set("vending-z", machine.getZ());
        host.set("vending-origin", RailwayManagementData.fit(originName, NAME_BYTES));
        host.setList("vending-dests", destRows(dests));
        host.set("vending-seq", ((Integer) host.get("vending-seq") + 1) & 0xFFFF);
    }

    static List<List<Object>> destRows(List<VendingDest> dests) {
        List<List<Object>> rows = new ArrayList<>(Math.min(dests.size(), DEST_ROWS));
        for (VendingDest d : dests) {
            if (rows.size() == DEST_ROWS) break;
            rows.add(List.<Object>of(d.id().toString(), RailwayManagementData.fit(d.name(), NAME_BYTES)));
        }
        return rows;
    }

    /** The destinations of a vending-dests field as the screen takes them; a row that does not parse is left out. */
    public static List<VendingDest> destsOf(List<?> rows) {
        List<VendingDest> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            UUID id = ManagementComputerData.parseUuid((String) r.get(0));
            if (id != null) out.add(new VendingDest(id, (String) r.get(1)));
        }
        return out;
    }

    /** The positions of a detail-positions field. */
    public static List<BlockPos> positionsOf(List<?> rows) {
        List<BlockPos> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            out.add(new BlockPos((Integer) r.get(0), (Integer) r.get(1), (Integer) r.get(2)));
        }
        return out;
    }
}
