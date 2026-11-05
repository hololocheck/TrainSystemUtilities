package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.station.LineSymbolStore;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The management computer on manta:data (MANTA_7_CONCEPT C4): what ManagementComputerControlPayload,
 * ManagementSymbolPayload, MonitorLayoutPayload, RequestScheduleEditPayload, ApplyScheduleEditPayload,
 * ExportTimetablePayload, ShareTimetablePayload, SetTrainTypePayload, TicketConfigRequestPayload and
 * TicketConfigUpdatePayload carried in, and what TicketConfigSyncPayload carried out, declared by the state-only document
 * {@link #DOCUMENT}. Each block entity has one host (opened when it loads, closed when it is removed) that admits
 * exactly the players the payload handlers admitted: in the block's level, within reach
 * ({@link PermissionHelper#isWithinReach}), with the owner's access - asked again on every action. The door side of a
 * station ({@link #DOOR_SIDE}) never had a payload: until 2026-09-30 the screen wrote it into the integrated server's
 * block entity, which a dedicated server does not have ({@link ManagementDoorSideAction}). The two actions that change a
 * station's railway management block - its door side and its line symbol - also ask that block
 * ({@link #railwayManagerAdmits}); a symbol for a block this computer cannot find asks the store, where a private block
 * registers its owner ({@link #stationAdmits}).
 *
 * <p>The codec has no UUID, long or NBT type: a UUID is its 36-character string, a BlockPos its x / y / z, and a
 * schedule or a monitor layout its SNBT in an {@code exact} param ({@link ExactText}) - a {@code string} is NFC, and a
 * schedule's filters name the world's stations byte for byte. The schedule the editor asks for is the asker's alone, so it answers on the
 * asker's tools host ({@link ToolsData#answerScheduleEdit}); the ticket lists are this computer's, for every screen
 * open on it (the sync went to the asker alone).</p>
 */
public final class ManagementComputerData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/management-computer-state.json";

    public static final String CONTROL = "control";
    public static final String SYMBOL = "symbol";
    public static final String MONITOR_LAYOUT = "monitor-layout";
    public static final String SCHEDULE_REQUEST = "schedule-request";
    public static final String SCHEDULE_APPLY = "schedule-apply";
    public static final String EXPORT_TIMETABLE = "export-timetable";
    public static final String SHARE_TIMETABLE = "share-timetable";
    public static final String SET_TRAIN_TYPE = "set-train-type";
    public static final String TICKET_REQUEST = "ticket-request";
    public static final String TICKET_UPDATE = "ticket-update";
    public static final String DOOR_SIDE = "door-side";

    /**
     * management-computer-state.json's maxLen of an NBT param or field (a schedule, a monitor layout): its SNBT's UTF-8
     * bytes, declared {@code exact} - the names inside a schedule's filters are matched against the world's station names.
     */
    public static final int SNBT_BYTES = 60_000;
    /** ...of the symbol's own strings: the payload writer's char caps (8 / 16 / 64) x 9 bytes per char. */
    public static final int LETTERS_BYTES = 72;
    public static final int BORDER_COLOR_BYTES = 144;
    public static final int NAME_BYTES = 576;
    /** ...and of the station it assigns to, a key into the world's stations: exact, the writer's 256 chars x 3 bytes. */
    public static final int STATION_BYTES = 768;
    /** The ticket lists' maxLength (TicketConfigSyncPayload's decoder allowed 256 of each). */
    static final int TICKET_ROWS = 256;

    private static Schema schema;

    private ManagementComputerData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel(Level level, BlockPos pos) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, "management-computer", level.dimension(), pos);
    }

    /** The computer's host, on the server thread. Closed by the block entity with itself. */
    public static Host open(ManagementComputerBlockEntity mc, ServerLevel level) {
        BlockPos pos = mc.getBlockPos();
        Host host = MantaData.host(level.getServer(), channel(level, pos), schema(),
                player -> player.level() == level && level.getBlockEntity(pos) == mc
                        && PermissionHelper.isWithinReach(player, pos) && mc.canAccess(player));
        // Params in management-computer-state.json's declaration order.
        host.on(CONTROL, (args, p) -> ManagementComputerControlAction.apply(mc, i(args, 0), uuid(args, 1)));
        host.on(SYMBOL, (args, p) -> ManagementSymbolAction.apply(mc, p, i(args, 0), i(args, 1), s(args, 2),
                i(args, 3), s(args, 4), s(args, 5), i(args, 6), s(args, 7),
                new BlockPos(i(args, 8), i(args, 9), i(args, 10)), uuid(args, 11)));
        host.on(MONITOR_LAYOUT, (args, p) -> {
            CompoundTag data = nbt(args, 1);
            if (data != null) MonitorLayoutAction.apply(mc, level, i(args, 0), data);
        });
        host.on(SCHEDULE_REQUEST, (args, p) -> RequestScheduleEditAction.apply(p, mc, uuid(args, 0)));
        host.on(SCHEDULE_APPLY, (args, p) -> {
            CompoundTag schedule = nbt(args, 1);
            if (schedule != null) ApplyScheduleEditAction.apply(mc, uuid(args, 0), schedule);
        });
        host.on(EXPORT_TIMETABLE, (args, p) -> ExportTimetableAction.apply(mc, uuid(args, 0)));
        host.on(SHARE_TIMETABLE, (args, p) -> ShareTimetableAction.apply(mc, uuid(args, 0), uuid(args, 1)));
        host.on(SET_TRAIN_TYPE, (args, p) -> SetTrainTypeAction.apply(mc, uuid(args, 0), i(args, 1)));
        host.on(TICKET_REQUEST, (args, p) -> TicketConfigRequestAction.apply(p, mc, host));
        host.on(TICKET_UPDATE, (args, p) -> TicketConfigUpdateAction.apply(p, uuid(args, 0), (Boolean) args.get(1)));
        host.on(DOOR_SIDE, (args, p) -> ManagementDoorSideAction.apply(mc, level, p, s(args, 0),
                new BlockPos(i(args, 1), i(args, 2), i(args, 3)), s(args, 4)));
        return host;
    }

    /**
     * Whether a player the computer admits may also change this station's railway management block. The computer's
     * access let anyone in reach of a public computer change a private block's door side and line symbol; since the
     * user's decision of 2026-10-01 the block's own private mode and owner decide
     * ({@link RailwayManagementBlockEntity#canAccess}), as opening the block itself would - and a refused player is told
     * so in the action bar, as there.
     */
    static boolean railwayManagerAdmits(RailwayManagementBlockEntity rm, ServerPlayer player) {
        if (rm.canAccess(player)) return true;
        String owner = rm.getOwnerName();
        player.displayClientMessage(Component.translatable("message.trainsystemutilities.railway_manager_locked",
                owner == null || owner.isEmpty() ? String.valueOf(rm.getOwnerUUID()) : owner), true);
        return false;
    }

    /**
     * The same question asked of {@link LineSymbolStore}, where a private block registers its owner while it is loaded
     * (user decision (19), 2026-10-01): it covers a block this computer cannot find (its chunk unloaded, never cached)
     * and a station with several blocks, where the one found need not be the one guarding. A block not loaded since
     * this version has registered nothing yet - the store then admits, and drops a stranger's symbol when the block
     * registers. The refusal is told as above, the owner by name when the server knows it.
     */
    static boolean stationAdmits(String stationName, BlockPos stationPos, ServerPlayer player) {
        LineSymbolStore store = LineSymbolStore.get(player.server);
        String key = ManagementComputerBlockEntity.stationKey(stationName, stationPos);
        if (store.admits(key, player.getUUID())) return true;
        UUID owner = store.guardOf(key);
        var profiles = player.server.getProfileCache();
        String name = profiles == null || owner == null ? null
                : profiles.get(owner).map(com.mojang.authlib.GameProfile::getName).orElse(null);
        player.displayClientMessage(Component.translatable("message.trainsystemutilities.railway_manager_locked",
                name != null ? name : String.valueOf(owner)), true);
        return false;
    }

    /**
     * The {@link #DOOR_SIDE} params in management-computer-state.json's order: the station byte for byte (a key into
     * the world's stations, as the symbol action's), its position, and the side as its enum name - the declaration's
     * values are the enum's names.
     */
    public static Object[] doorSideParams(String stationName, BlockPos stationPos,
                                          RailwayManagementBlockEntity.DoorSide side) {
        return new Object[]{ExactText.cut(stationName, STATION_BYTES),
                stationPos.getX(), stationPos.getY(), stationPos.getZ(), side.name()};
    }

    /** The ticket lists (sellable station groups, this network's groups): at most {@link #TICKET_ROWS} each. */
    static void publishTicketConfig(Host host, List<UUID> sellable, List<UUID> networkGroups) {
        host.setList("ticket-sellable", uuidRows(sellable));
        host.setList("ticket-network-groups", uuidRows(networkGroups));
    }

    static List<List<Object>> uuidRows(List<UUID> ids) {
        List<List<Object>> rows = new ArrayList<>(Math.min(ids.size(), TICKET_ROWS));
        for (UUID id : ids) {
            if (rows.size() == TICKET_ROWS) break;
            rows.add(List.<Object>of(id.toString()));
        }
        return rows;
    }

    /** The UUIDs of a list field's rows as a screen reads them back; a row that does not parse is left out. */
    public static List<UUID> uuidsOf(List<?> rows) {
        List<UUID> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            UUID id = parseUuid((String) ((List<?>) o).get(0));
            if (id != null) out.add(id);
        }
        return out;
    }

    /** A string param cut to its declaration as the codec measures it (NFC, UTF-8), at a code point; null is "". */
    public static String fitParam(@Nullable String s, int maxBytes) {
        return s == null ? "" : RailwayManagementData.fit(s, maxBytes);
    }

    /** A UUID as a param: its canonical 36-character string; null (no train, no symbol) is the zero UUID. */
    public static String uuidParam(@Nullable UUID id) {
        return (id == null ? new UUID(0, 0) : id).toString();
    }

    /**
     * An NBT param or field: its SNBT, or null when that is past {@link #SNBT_BYTES} or has no UTF-8 form (a string tag
     * holding an unpaired surrogate) - an exact declaration refuses both, and the sender tells its user instead.
     */
    @Nullable
    public static String snbtParam(CompoundTag tag) {
        String snbt = tag.toString();
        int bytes = ExactText.utf8Length(snbt);
        return bytes >= 0 && bytes <= SNBT_BYTES ? snbt : null;
    }

    /**
     * The deepest nesting an SNBT param may have: readNbt's own bound (NbtAccounter), which the payloads' decoders
     * applied. TagParser has none - its reader recurses once per level - so it is counted before the parse.
     */
    static final int MAX_NBT_DEPTH = 512;

    /** The NBT {@link #snbtParam} made; null for one that is not SNBT, or nested past readNbt's bound. */
    @Nullable
    public static CompoundTag nbtOf(String snbt) {
        if (depthOf(snbt) > MAX_NBT_DEPTH) return null;
        try {
            return TagParser.parseTag(snbt);
        } catch (Exception e) {
            return null;
        }
    }

    /** The deepest { / [ nesting of an SNBT, not counting brackets inside quoted strings. */
    static int depthOf(String snbt) {
        int depth = 0;
        int max = 0;
        char quote = 0;
        for (int i = 0; i < snbt.length(); i++) {
            char c = snbt.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                max = Math.max(max, ++depth);
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return max;
    }

    @Nullable
    static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int i(List<Object> args, int at) {
        return (Integer) args.get(at);
    }

    private static String s(List<Object> args, int at) {
        return (String) args.get(at);
    }

    /** A UUID param; one that does not parse is the zero UUID, which no train or symbol has. */
    private static UUID uuid(List<Object> args, int at) {
        UUID id = parseUuid(s(args, at));
        return id != null ? id : new UUID(0, 0);
    }

    /**
     * An NBT param as the payload's CompoundTag; null when it does not parse, and the action then does nothing - an
     * empty tag would clear a layout or apply an empty schedule. Only a client other than this mod's sends one.
     */
    @Nullable
    private static CompoundTag nbt(List<Object> args, int at) {
        return nbtOf(s(args, at));
    }
}
