package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's held tools on manta:data (MANTA_7_CONCEPT C4): what the station range tool, the train preset tool,
 * the overhead pole auto tool, the wire connector and the memory card sent as payloads of their own is the actions
 * of one host per player, which only they reach, opened at login (Manta closes it at logout) and declared by the
 * state-only document {@link #DOCUMENT}. Each action runs what its payload's handler ran, on the server thread,
 * against the sender's own held tool; the bounds its decoder checked are the document's declarations, which the
 * codec checks before a handler runs. The host also carries what is the player's alone: the schedule a management
 * computer's editor asked for ({@link #answerScheduleEdit}).
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class ToolsData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/tools-state.json";
    static final String DOC = "tools";

    public static final String STATION_RANGE_MODE = "station-range-mode";
    public static final String PRESET_TOOL_MODE = "preset-tool-mode";
    public static final String PRESET_SAVE = "preset-save";
    public static final String PRESET_SELECT = "preset-select";
    public static final String PRESET_CANCEL_PLACE = "preset-cancel-place";
    public static final String PRESET_RETRY = "preset-retry";
    public static final String PRESET_GLUE_REFILL = "preset-glue-refill";
    public static final String PRESET_GLUE_DUMP = "preset-glue-dump";
    public static final String PRESET_MATERIAL_CONFIG = "preset-material-config";
    public static final String PRESET_LINK_CHEST = "preset-link-chest";
    public static final String POLE_TOOL = "pole-tool";
    public static final String POLE_SETTINGS = "pole-settings";
    public static final String POLE_LINK_CHEST = "pole-link-chest";
    public static final String WIRE_TYPE = "wire-type";
    public static final String WIRE_PRESET = "wire-preset";
    public static final String WIRE_REFILL_OPEN = "wire-refill-open";
    public static final String MEMORY_CARD_RESET = "memory-card-reset";

    /** tools-state.json's sched-edit-state values, in declaration order (the test pins the declaration against these). */
    public enum ScheduleAnswer { NONE, DATA, TOO_LARGE }

    private static Schema schema;
    /** The open host of each player; server thread only (login, logout and every caller run there). */
    private static final Map<UUID, Host> HOSTS = new HashMap<>();

    private ToolsData() {
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
        // Params in tools-state.json's declaration order.
        host.on(STATION_RANGE_MODE, (args, p) -> StationRangeToolModeAction.apply(p, i(args, 0), i(args, 1)));
        host.on(PRESET_TOOL_MODE, (args, p) -> TrainPresetToolModeAction.apply(p, i(args, 0), i(args, 1)));
        host.on(PRESET_SAVE, (args, p) -> TrainPresetSaveAction.apply(p, (String) args.get(0)));
        host.on(PRESET_SELECT, (args, p) -> TrainPresetSelectAction.apply(p, (String) args.get(0),
                (String) args.get(1)));
        host.on(PRESET_CANCEL_PLACE, (args, p) -> TrainPresetCancelPlaceAction.apply(p));
        host.on(PRESET_RETRY, (args, p) -> TrainPresetRetryAction.apply(p));
        host.on(PRESET_GLUE_REFILL, (args, p) -> TrainPresetGlueRefillAction.apply(p));
        host.on(PRESET_GLUE_DUMP, (args, p) -> TrainPresetGlueDumpAction.apply(p));
        host.on(PRESET_MATERIAL_CONFIG, (args, p) -> TrainPresetMaterialConfigAction.apply(p, i(args, 0),
                i(args, 1)));
        host.on(PRESET_LINK_CHEST, (args, p) -> TrainPresetLinkChestAction.apply(p, blockPos(args, 0)));
        host.on(POLE_TOOL, (args, p) -> OverheadPoleAutoToolAction.apply(p, i(args, 0), i(args, 1)));
        host.on(POLE_SETTINGS, (args, p) -> OverheadPoleAutoSettingsAction.apply(p, i(args, 0), i(args, 1)));
        host.on(POLE_LINK_CHEST, (args, p) -> OverheadPoleLinkChestAction.apply(p, blockPos(args, 0)));
        host.on(WIRE_TYPE, (args, p) -> WireConnectorTypeAction.apply(p, i(args, 0), i(args, 1)));
        host.on(WIRE_PRESET, (args, p) -> WireConnectorPresetAction.apply(p, i(args, 0), i(args, 1),
                (String) args.get(2)));
        host.on(WIRE_REFILL_OPEN, (args, p) -> WireConnectorRefillOpenAction.apply(p));
        host.on(MEMORY_CARD_RESET, (args, p) -> MemoryCardResetAction.apply(p, i(args, 0)));
        HOSTS.put(player.getUUID(), host);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        HOSTS.remove(event.getEntity().getUUID());
    }

    /**
     * The schedule a management computer's editor asked for (ScheduleEditDataPayload until MANTA_7_CONCEPT C4): the
     * asker's alone, so on the asker's host. An event, not state - {@code sched-edit-seq} moves last, and the client
     * takes the answer when it sees the counter move (client.ToolsClient). A schedule whose SNBT is past the
     * declaration is answered TOO_LARGE: the editor must not open empty on a train whose schedule it could not show.
     */
    public static void answerScheduleEdit(ServerPlayer player, UUID trainId, boolean hasData, CompoundTag schedule) {
        Host host = HOSTS.get(player.getUUID());
        if (host == null) {
            return;
        }
        String snbt = hasData ? ManagementComputerData.snbtParam(schedule) : "";
        host.set("sched-edit-train", trainId.toString());
        host.set("sched-edit-state", (!hasData ? ScheduleAnswer.NONE
                : snbt != null ? ScheduleAnswer.DATA : ScheduleAnswer.TOO_LARGE).name());
        host.set("sched-edit-nbt", snbt != null ? snbt : "");
        host.set("sched-edit-seq", ((Integer) host.get("sched-edit-seq") + 1) & 0xFFFF);
    }

    private static int i(List<Object> args, int at) {
        return (Integer) args.get(at);
    }

    private static BlockPos blockPos(List<Object> args, int from) {
        return new BlockPos(i(args, from), i(args, from + 1), i(args, from + 2));
    }
}
