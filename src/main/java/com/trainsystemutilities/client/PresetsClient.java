package com.trainsystemutilities.client;

import com.manta.api.data.Mirror;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.client.gui.TrainPresetClientCache;
import com.trainsystemutilities.network.PresetsData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * The client end of the preset store (MANTA_7_CONCEPT C4, TSU slice T7), open while the client is in a world: the
 * player's presets host ({@link PresetsData}). The list arrives in pages - each an event carrying the scan's generation,
 * its offset and the total - and is assembled here: page 0 starts a list, a page of the same generation at the next
 * offset extends it and asks for the page after, and the finished list goes to {@link TrainPresetClientCache}, which
 * the screens read as they read the payload's list. A delete or an import answers page 0 of a new scan, which restarts
 * the list; when that page 0 was overwritten by the answer to the page in flight, the later page of the new scan that
 * arrives instead asks for page 0 again ({@link PresetsData.Assembly}). A page of the same scan at another offset is
 * stale and dropped. The material check's answer is keyed as the payload's handler keyed it.
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class PresetsClient {

    private static Mirror mirror;
    private static int revision = -1;
    /** The event counters last seen; -1 until the first snapshot, whose events are history, not news. */
    private static int seenPage = -1;
    private static int seenMaterial = -1;
    /** The list being assembled from its pages. */
    private static PresetsData.Assembly assembly = new PresetsData.Assembly();

    private PresetsClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        close();
        mirror = Mirror.open(PresetsData.channel(event.getPlayer().getUUID()), PresetsData.schema());
        revision = -1;
        seenPage = -1;
        seenMaterial = -1;
        assembly = new PresetsData.Assembly();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        close();
    }

    private static void close() {
        if (mirror != null) {
            mirror.close();
            mirror = null;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Mirror m = mirror;
        if (m == null) {
            return;
        }
        m.poll();
        int r = m.revision();
        if (r < 0 || r == revision) {
            return;
        }
        revision = r;
        int page = (Integer) m.get("page-seq");
        if (seenPage >= 0 && page != seenPage) {
            PresetsData.Assembly.Step step = assembly.accept((Integer) m.get("page-gen"),
                    (Integer) m.get("page-offset"), (Integer) m.get("page-total"),
                    PresetsData.itemsOf((List<?>) m.get("page-rows")));
            if (step.nextOffset() >= 0) {
                m.send(PresetsData.LIST_PAGE, step.nextOffset());
            }
            if (step.done() != null) {
                TrainPresetClientCache.setList(step.done());
            }
        }
        seenPage = page;
        int material = (Integer) m.get("mat-seq");
        if (seenMaterial >= 0 && material != seenMaterial) {
            TrainPresetClientCache.setMissingForPreset(PresetsData.materialKey((String) m.get("mat-author-dir"),
                    (String) m.get("mat-file-name")), (String) m.get("mat-missing"));
        }
        seenMaterial = material;
    }

    /** The preset list, from a new scan (TrainPresetListRequestPayload until C4). */
    public static void requestList() {
        if (mirror != null) {
            mirror.send(PresetsData.LIST_PAGE, 0);
        }
    }

    /** Delete a preset (TrainPresetDeletePayload until C4); the list follows as a new scan. */
    public static void delete(String authorDir, String fileName) {
        if (mirror != null) {
            mirror.send(PresetsData.DELETE, authorDir, fileName);
        }
    }

    /** What a preset needs that the linked sources lack (TrainPresetMaterialCheckRequestPayload until C4). */
    public static void material(String authorDir, String fileName) {
        if (mirror != null) {
            mirror.send(PresetsData.MATERIAL, authorDir, fileName);
        }
    }
}
