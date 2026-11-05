package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.announce.AnnouncementConfig;
import com.trainsystemutilities.announce.AnnouncementEntry;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.util.PermissionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The railway management block on manta:data (MANTA_7_CONCEPT C4): what AnnouncementCommandPayload,
 * AnnouncementShareTogglePayload and the three ScreenDoor*Payloads carried in, and what AnnouncementSyncPayload and
 * AnnouncementPlaybackStatePayload carried out, declared by the state-only document {@link #DOCUMENT}. Each block
 * entity has one host (opened when it loads, closed when it is removed) that admits exactly the players the payload
 * handlers admitted: in the block's level, within reach ({@link PermissionHelper#isWithinReach}), with the owner's
 * access ({@link RailwayManagementBlockEntity#canAccess}) - asked again on every action.
 *
 * <p>The announcement settings and the entry playing now are the host's fields, so every open screen sees a change
 * (the sync went to the sender alone), and the screen no longer asks for them: its subscription is the ask.</p>
 */
public final class RailwayManagementData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/railway-management-state.json";

    public static final String ANNOUNCEMENT = "announcement";
    public static final String ANNOUNCEMENT_SHARE = "announcement-share";
    public static final String DOOR_BAND_COLOR = "door-band-color";
    public static final String DOOR_CONDITION = "door-condition";
    public static final String DOOR_TEST = "door-test";

    /** railway-management-state.json's maxLength of each shared-station list. */
    static final int SHARED_ROWS = 64;
    /**
     * A station name is compared with the world's byte for byte, so it is declared {@code exact} ({@link ExactText}): at
     * most SafeName's 128 UTF-8 bytes (a toggle only adds a name SafeName admits).
     */
    static final int NAME_BYTES = com.manta.api.util.safe.SafeName.DEFAULT_MAX_BYTES;

    private static Schema schema;

    private RailwayManagementData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(TrainSystemUtilities.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel(Level level, BlockPos pos) {
        return MantaData.channel(TrainSystemUtilities.MOD_ID, "railway-management", level.dimension(), pos);
    }

    /** The block's host, on the server thread, with its values as they are now. Closed by the block entity. */
    public static Host open(RailwayManagementBlockEntity be, ServerLevel level) {
        BlockPos pos = be.getBlockPos();
        Host host = MantaData.host(level.getServer(), channel(level, pos), schema(),
                player -> player.level() == level && level.getBlockEntity(pos) == be
                        && PermissionHelper.isWithinReach(player, pos) && be.canAccess(player));
        // Params in railway-management-state.json's declaration order.
        host.on(ANNOUNCEMENT, (args, p) -> AnnouncementCommandAction.apply(p, be, i(args, 0), i(args, 1),
                i(args, 2), i(args, 3)));
        host.on(ANNOUNCEMENT_SHARE, (args, p) -> AnnouncementShareToggleAction.apply(be, (String) args.get(0),
                i(args, 1)));
        host.on(DOOR_BAND_COLOR, (args, p) -> ScreenDoorBandColorAction.apply(be, i(args, 0)));
        host.on(DOOR_CONDITION, (args, p) -> ScreenDoorConditionAction.apply(be, i(args, 0), i(args, 1),
                i(args, 2), i(args, 3), i(args, 4)));
        host.on(DOOR_TEST, (args, p) -> ScreenDoorTestAction.apply(be, i(args, 0)));
        publishConfig(host, be.getAnnouncementConfig());
        publishPlaying(host, com.trainsystemutilities.announce.AnnouncementScheduler.playingEntry(
                level.dimension(), pos));
        return host;
    }

    /** The announcement settings as the screen shows them: each value within its declaration. */
    public static void publishConfig(Host host, AnnouncementConfig cfg) {
        host.set("ann-enabled", cfg.isEnabled());
        host.set("ann-attenuation", cfg.isAttenuationMode());
        host.setList("ann-entries", entryRows(cfg));
        host.setList("ann-shared-detection", names(cfg.sharedDetectionToStations()));
        host.setList("ann-shared-range", names(cfg.sharedRangeToStations()));
    }

    /**
     * The entries as list rows (type, delay, play count). AnnouncementConfig keeps at most MAX_ENTRIES, the condition
     * clamps its delay and the entry its play count: the declaration's bounds are those.
     */
    static List<List<Object>> entryRows(AnnouncementConfig cfg) {
        List<List<Object>> rows = new ArrayList<>(cfg.size());
        for (AnnouncementEntry e : cfg.entries()) {
            rows.add(List.<Object>of(e.condition().type.name(), e.condition().delaySeconds, e.playCount()));
        }
        return rows;
    }

    /**
     * The settings a screen reads back from the host's fields (what AnnouncementSyncPayload's config NBT loaded
     * into): the client state the popup and the range-frame renderer read.
     */
    public static AnnouncementConfig configOf(boolean enabled, boolean attenuation, List<?> entries,
                                              List<?> sharedDetection, List<?> sharedRange) {
        AnnouncementConfig cfg = new AnnouncementConfig();
        cfg.setEnabled(enabled);
        cfg.setAttenuationMode(attenuation);
        for (Object o : entries) {
            List<?> row = (List<?>) o;
            if (!cfg.addEntry()) {
                break;
            }
            AnnouncementEntry e = cfg.get(cfg.size() - 1);
            e.setCondition(new com.trainsystemutilities.announce.AnnouncementCondition(
                    com.trainsystemutilities.announce.AnnouncementCondition.Type.valueOf((String) row.get(0)),
                    (Integer) row.get(1)));
            e.setPlayCount((Integer) row.get(2));
        }
        for (Object o : sharedDetection) {
            cfg.sharedDetectionToStations().add((String) ((List<?>) o).get(0));
        }
        for (Object o : sharedRange) {
            cfg.sharedRangeToStations().add((String) ((List<?>) o).get(0));
        }
        return cfg;
    }

    /** The entry playing now, -1 for none (an index past the declaration reads as none). */
    public static void publishPlaying(Host host, int entryIdx) {
        host.set("ann-playing", entryIdx >= 0 && entryIdx < AnnouncementConfig.MAX_ENTRIES ? entryIdx : -1);
    }

    /** The playing entry of the block at {@code pos}, to its host if the block is loaded. */
    public static void publishPlaying(ServerLevel level, BlockPos pos, int entryIdx) {
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof RailwayManagementBlockEntity be
                && be.dataHost() != null) {
            publishPlaying(be.dataHost(), entryIdx);
        }
    }

    /**
     * A shared-station set as list rows: at most {@link #SHARED_ROWS}, each name byte for byte ({@link ExactText#cut})
     * cut at a code point to {@link #NAME_BYTES}. A toggle only ever adds a name SafeName admitted (128 bytes); an older
     * world's set can hold anything, and a value past its declaration would make the host refuse it.
     */
    static List<List<Object>> names(Collection<String> set) {
        List<List<Object>> out = new ArrayList<>(Math.min(set.size(), SHARED_ROWS));
        for (String s : set) {
            if (out.size() == SHARED_ROWS) {
                break;
            }
            out.add(List.<Object>of(ExactText.cut(s, NAME_BYTES)));
        }
        return out;
    }

    /**
     * {@code s} normalised to NFC and cut at a code point so its UTF-8 fits {@code maxBytes}. An unpaired surrogate is
     * left out first ({@link ExactText#cut} with no cap): it has no UTF-8 form, manta refuses a string holding one (a
     * '?' travelled in its place until 2026-09-25), and these values come from NBT - a train's or a station's name -
     * where one can live. Left out before NFC, so what is measured is what the codec writes.
     */
    static String fit(String s, int maxBytes) {
        String n = Normalizer.normalize(ExactText.cut(s, Integer.MAX_VALUE), Normalizer.Form.NFC);
        if (n.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return n;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) {
                break;
            }
            out.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static int i(List<Object> args, int at) {
        return (Integer) args.get(at);
    }
}
