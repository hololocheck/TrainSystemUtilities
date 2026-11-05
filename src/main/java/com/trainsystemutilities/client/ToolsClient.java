package com.trainsystemutilities.client;

import com.manta.api.data.Mirror;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.network.ExactText;
import com.trainsystemutilities.network.ToolsData;
import com.trainsystemutilities.network.WireConnectorPresetAction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;

/**
 * The client end of the player's tools host ({@link ToolsData}): open while the client is in a world, and the one
 * way the held tools' input reaches the server. One method per action, its params in tools-state.json's order;
 * {@link Mirror#send} refuses a value outside the declaration by throwing, so each sender gives only values the
 * declaration holds (the bounds tests pin the widest a sender can give).
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class ToolsClient {

    private static Mirror mirror;
    private static int seenRevision = -1;
    /** The schedule-answer counter last seen; -1 until the first snapshot, whose answer is history, not news. */
    private static int seenScheduleAnswer = -1;

    private ToolsClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (mirror != null) {
            mirror.close();
        }
        mirror = Mirror.open(ToolsData.channel(event.getPlayer().getUUID()), ToolsData.schema());
        seenRevision = -1;
        seenScheduleAnswer = -1;
    }

    /**
     * Each tick: a schedule the management computer's editor asked for, when its counter moved, to the screen still
     * open on that train. No page draws this mirror, so the tick also asks for a snapshot that has not come.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Mirror m = mirror;
        if (m == null) {
            return;
        }
        m.poll();
        int revision = m.revision();
        if (revision < 0 || revision == seenRevision) {
            return;
        }
        seenRevision = revision;
        int answer = (Integer) m.get("sched-edit-seq");
        if (seenScheduleAnswer >= 0 && answer != seenScheduleAnswer) {
            scheduleAnswer((String) m.get("sched-edit-train"), (String) m.get("sched-edit-state"),
                    (String) m.get("sched-edit-nbt"));
        }
        seenScheduleAnswer = answer;
    }

    private static void scheduleAnswer(String train, String state, String snbt) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof com.trainsystemutilities.client.gui.ManagementComputerScreenV2 screen)) {
            return;
        }
        UUID trainId;
        try {
            trainId = UUID.fromString(train);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (ToolsData.ScheduleAnswer.TOO_LARGE.name().equals(state)) {
            screen.onScheduleEditTooLarge(trainId);
            return;
        }
        CompoundTag nbt = new CompoundTag();
        if (ToolsData.ScheduleAnswer.DATA.name().equals(state)) {
            nbt = com.trainsystemutilities.network.ManagementComputerData.nbtOf(snbt);
            if (nbt == null) return;
        }
        screen.onScheduleEditData(trainId, ToolsData.ScheduleAnswer.DATA.name().equals(state), nbt);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (mirror != null) {
            mirror.close();
            mirror = null;
        }
    }

    public static void stationRangeMode(int action, int delta) {
        send(ToolsData.STATION_RANGE_MODE, action, delta);
    }

    public static void presetToolMode(int action, int delta) {
        send(ToolsData.PRESET_TOOL_MODE, action, delta);
    }

    public static void presetSave(String name) {
        send(ToolsData.PRESET_SAVE, name);
    }

    /** The file name must find the file byte for byte: its param is declared exact (a string param is NFC). */
    public static void presetSelect(String authorDir, String fileName) {
        send(ToolsData.PRESET_SELECT, authorDir, fileName);
    }

    public static void presetCancelPlace() {
        send(ToolsData.PRESET_CANCEL_PLACE);
    }

    public static void presetRetry() {
        send(ToolsData.PRESET_RETRY);
    }

    public static void presetGlueRefill() {
        send(ToolsData.PRESET_GLUE_REFILL);
    }

    public static void presetGlueDump() {
        send(ToolsData.PRESET_GLUE_DUMP);
    }

    public static void presetMaterialConfig(int action, int value) {
        send(ToolsData.PRESET_MATERIAL_CONFIG, action, value);
    }

    public static void presetLinkChest(BlockPos pos) {
        send(ToolsData.PRESET_LINK_CHEST, pos.getX(), pos.getY(), pos.getZ());
    }

    public static void poleTool(int action, int delta) {
        send(ToolsData.POLE_TOOL, action, delta);
    }

    public static void poleSettings(int field, int value) {
        send(ToolsData.POLE_SETTINGS, field, value);
    }

    public static void poleLinkChest(BlockPos pos) {
        send(ToolsData.POLE_LINK_CHEST, pos.getX(), pos.getY(), pos.getZ());
    }

    public static void wireType(int action, int value) {
        send(ToolsData.WIRE_TYPE, action, value);
    }

    /**
     * A new preset of the current custom values. SAVE reads no index, and sends 0: the payload sent -1, which its
     * own decoder (index 0..4096) refused - on a dedicated server, a disconnect. The name leaves out an unpaired
     * surrogate ({@link ExactText#cut} with no cap - the declaration's 384 bytes already hold the input's 32 code
     * points): the dialog's default is a language value a resource pack can set, and manta refuses a string with no
     * UTF-8 form, which would throw out of the click.
     */
    public static void wirePresetSave(String name) {
        send(ToolsData.WIRE_PRESET, WireConnectorPresetAction.ACTION_SAVE, 0, ExactText.cut(name, Integer.MAX_VALUE));
    }

    /**
     * DELETE or APPLY of a preset the tool holds. The server keeps 64; an index past the declaration comes only
     * from an item written outside the game, and is dropped here rather than thrown out of the click.
     */
    public static void wirePreset(int action, int index) {
        if (index >= 0 && index <= WireConnectorPresetAction.MAX_INDEX) {
            send(ToolsData.WIRE_PRESET, action, index, "");
        }
    }

    public static void wireRefillOpen() {
        send(ToolsData.WIRE_REFILL_OPEN);
    }

    public static void memoryCardReset(int hand) {
        send(ToolsData.MEMORY_CARD_RESET, hand);
    }

    /** A tool action, when the client is in a world; before that there is nobody to send it to. */
    private static void send(String action, Object... args) {
        if (mirror != null) {
            mirror.send(action, args);
        }
    }
}
