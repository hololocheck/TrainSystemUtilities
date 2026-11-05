package com.trainsystemutilities.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetStorage;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The preset store on manta:data (MANTA_7_CONCEPT C4, TSU slice T7): what the preset list, delete and material check
 * payloads carried is one host per player, opened at login (Manta closes it at logout), declared by the state-only
 * document {@link #DOCUMENT}. The preset data and the import stay payloads - a preset's NBT and a downloaded file are
 * bytes, which the C4 ledger classifies TRANSPORT.
 * <p>The list is PAGED. A row is up to ~1 KB (a name, an author, and the directory and file name the screen sends back,
 * byte for byte as {@code exact} strings - {@link ExactText}), so a message holds {@link #PAGE_ROWS} of them where the payload's decoder took
 * {@link #MAX_TOTAL}. {@code list-page(0)} scans the disk as the list request did and answers the first page;
 * {@code list-page(n)} answers the page at n of that scan; each page is an event ("value + seq") carrying the scan's
 * generation and the total, and the client asks for the next page until it has the total (client.PresetsClient), then
 * hands the whole list to the cache the screens read. A delete or an import scans again and answers page 0 with a new
 * generation, as the payloads sent the new list. A scan asked for within a second of the last one reuses it (the
 * list request had no limit, and every page 0 read every preset file's header).
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public final class PresetsData {

    static final String DOCUMENT = "/assets/trainsystemutilities/manta/presets-state.json";
    static final String DOC = "presets";

    public static final String LIST_PAGE = "list-page";
    public static final String DELETE = "delete";
    public static final String MATERIAL = "material";

    /** presets-state.json's page size, the most presets a list holds, and the maxLen of a shown name (UTF-8 bytes). */
    public static final int PAGE_ROWS = 48;
    static final int MAX_TOTAL = 4096;
    static final int NAME_BYTES = 128;
    /** An author directory / file name, declared exact: at most 3 UTF-8 bytes per listed UTF-16 char. */
    static final int AUTHOR_DIR_BYTES = TrainPresetSelectAction.LISTED_AUTHOR_DIR_CHARS * 3;
    static final int FILE_NAME_BYTES = TrainPresetSelectAction.LISTED_FILE_NAME_CHARS * 3;
    /** The missing materials, TrainPresetMaterials.encode's "id=count;..." cut at an entry to this many bytes. */
    static final int MISSING_BYTES = 8192;
    /** A page 0 asked for within this of the last scan reuses it. */
    private static final long RESCAN_NS = 1_000_000_000L;

    /** One preset of the list (TrainPresetListResponsePayload.Item until C4). */
    public record Item(String name, String author,
                       int sizeX, int sizeY, int sizeZ,
                       int blockCount, int entityCount,
                       long createdMs,
                       String authorDir, String fileName,
                       /** プリセットプレイス由来 = アップロード抑止フラグ。 */
                       boolean imported) {
    }

    /** A player's last scan. */
    private record Scan(int generation, List<Item> items, long scannedNs) {
    }

    /**
     * The client's assembly of the paged list. Pure - no mirror, no Minecraft type - so its transitions are tested:
     * page 0 starts a list, the page of the same generation at the next offset extends it, a later page of another
     * generation asks for page 0 again, anything else is stale and dropped, and the list is done when it holds the
     * total (or a page came back empty).
     */
    public static final class Assembly {
        /** What a page asks of the client: the offset to ask for next (-1: none), and the finished list (or null). */
        public record Step(int nextOffset, @Nullable List<Item> done) {
        }

        @Nullable
        private List<Item> items;
        private int gen = -1;

        public Step accept(int generation, int offset, int total, List<Item> rows) {
            if (offset == 0) {
                items = new ArrayList<>(rows.size());
                gen = generation;
            } else if (generation != gen) {
                // A later page of another scan: that scan's page 0 never arrived. A host sends the last value a field
                // was set to in a tick and a client reads the last delta it received, so the page 0 a delete answers
                // is overwritten by the answer to the page in flight - dropping it alone would leave the old list
                // until the screen asks again. Page 0 is always answered from the scan the host holds now.
                return new Step(0, null);
            } else if (items == null || offset != items.size()) {
                return new Step(-1, null);
            }
            items.addAll(rows);
            if (items.size() < total && !rows.isEmpty()) {
                return new Step(items.size(), null);
            }
            List<Item> done = items;
            items = null;
            return new Step(-1, done);
        }
    }

    private static Schema schema;
    /** The open host of each player and their last scan; server thread only (login, logout and every caller). */
    private static final Map<UUID, Host> HOSTS = new HashMap<>();
    private static final Map<UUID, Scan> SCANS = new HashMap<>();

    private PresetsData() {
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
        // Params in presets-state.json's declaration order.
        host.on(LIST_PAGE, (args, p) -> answerPage(p, (Integer) args.get(0)));
        host.on(DELETE, (args, p) -> TrainPresetDeleteAction.apply(p, (String) args.get(0), (String) args.get(1)));
        host.on(MATERIAL, (args, p) -> TrainPresetMaterialCheckAction.apply(p, (String) args.get(0),
                (String) args.get(1)));
        HOSTS.put(player.getUUID(), host);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        HOSTS.remove(event.getEntity().getUUID());
        SCANS.remove(event.getEntity().getUUID());
    }

    /** The list changed (a delete, an import): scan again and answer page 0 with a new generation. */
    public static void refresh(ServerPlayer player) {
        Host host = HOSTS.get(player.getUUID());
        if (host != null) {
            page(host, rescan(player), 0);
        }
    }

    /** {@code list-page(offset)}: page 0 scans (at most once a second), a later page answers from the last scan. */
    static void answerPage(ServerPlayer player, int offset) {
        Host host = HOSTS.get(player.getUUID());
        if (host == null) {
            return;
        }
        Scan scan = SCANS.get(player.getUUID());
        if (scan == null || (offset == 0 && System.nanoTime() - scan.scannedNs() >= RESCAN_NS)) {
            scan = rescan(player);
            offset = 0;
        }
        page(host, scan, offset);
    }

    private static Scan rescan(ServerPlayer player) {
        Scan last = SCANS.get(player.getUUID());
        List<Item> items = player.getServer() == null ? List.of() : scan(player);
        Scan scan = new Scan(last == null ? 0 : (last.generation() + 1) & 0xFFFF, items, System.nanoTime());
        SCANS.put(player.getUUID(), scan);
        return scan;
    }

    private static void page(Host host, Scan scan, int offset) {
        List<Item> items = scan.items();
        int from = Math.min(offset, items.size());
        host.set("page-gen", scan.generation());
        host.set("page-offset", from);
        host.set("page-total", items.size());
        host.setList("page-rows", pageRows(items.subList(from, Math.min(items.size(), from + PAGE_ROWS))));
        host.set("page-seq", ((Integer) host.get("page-seq") + 1) & 0xFFFF);
    }

    /** Every preset on disk as the list request built it; one the list cannot carry is left out with a warning. */
    static List<Item> scan(ServerPlayer player) {
        var entries = TrainPresetStorage.listAll(player.getServer());
        List<Item> items = new ArrayList<>();
        for (var e : entries) {
            try {
                TrainPreset header = TrainPresetStorage.loadHeader(e.file());
                items.add(new Item(
                        header.name == null || header.name.isEmpty() ? e.name() : header.name,
                        header.author == null ? "" : header.author,
                        header.sizeX, header.sizeY, header.sizeZ,
                        header.blocks.size(),
                        header.entities.size(),
                        header.createdEpochMs,
                        e.authorDir(),
                        e.file().getFileName().toString(),
                        header.importedFromPresetId != null
                                && !header.importedFromPresetId.isEmpty()));
            } catch (IOException | RuntimeException ex) {
                // The list request caught IOException, the delete's list rebuild every Exception: one broken header
                // leaves its preset out, never the list.
                TrainSystemUtilities.LOGGER.warn("Failed to read preset header {}: {}",
                        e.file(), ex.getMessage());
            }
        }
        return listable(items);
    }

    /**
     * The presets a list can carry: a directory or file name past the listed chars (TrainPresetSelectAction) could not
     * be sent back to select, delete or check it - the payload's writer threw on one, which disconnected the asker - nor
     * can one with no UTF-8 form (an unpaired surrogate, which a file system can hold), which the exact declaration
     * refuses; and at most {@link #MAX_TOTAL}.
     */
    static List<Item> listable(List<Item> items) {
        List<Item> out = new ArrayList<>(Math.min(items.size(), MAX_TOTAL));
        for (Item it : items) {
            if (it.authorDir().length() > TrainPresetSelectAction.LISTED_AUTHOR_DIR_CHARS
                    || it.fileName().length() > TrainPresetSelectAction.LISTED_FILE_NAME_CHARS) {
                TrainSystemUtilities.LOGGER.warn("[Preset] {}/{} has a name too long to list; left out",
                        it.authorDir().length(), it.fileName().length());
                continue;
            }
            if (ExactText.utf8Length(it.authorDir()) < 0 || ExactText.utf8Length(it.fileName()) < 0) {
                TrainSystemUtilities.LOGGER.warn("[Preset] a name with no UTF-8 form cannot be listed; left out");
                continue;
            }
            if (out.size() == MAX_TOTAL) {
                TrainSystemUtilities.LOGGER.warn("[Preset] {} presets; the list carries the first {}", items.size(),
                        MAX_TOTAL);
                break;
            }
            out.add(it);
        }
        return out;
    }

    /** The missing materials of a preset the asker checked (TrainPresetMaterialCheckResponsePayload until C4). */
    static void answerMaterial(ServerPlayer player, String authorDir, String fileName, String missingEncoded) {
        Host host = HOSTS.get(player.getUUID());
        if (host == null) {
            return;
        }
        host.set("mat-author-dir", authorDir);
        host.set("mat-file-name", fileName);
        host.set("mat-missing", cutMissing(missingEncoded, MISSING_BYTES));
        host.set("mat-seq", ((Integer) host.get("mat-seq") + 1) & 0xFFFF);
    }

    /** "id=count;..." cut at an entry to at most {@code maxBytes} (the entries are ASCII registry ids and counts). */
    static String cutMissing(String encoded, int maxBytes) {
        String s = encoded == null ? "" : encoded;
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (String entry : s.split(";")) {
            int bytes = out.length() + (out.length() == 0 ? 0 : 1) + entry.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxBytes) {
                break;
            }
            if (out.length() > 0) out.append(';');
            out.append(entry);
        }
        return out.toString();
    }

    // ===== rows: every value within its declaration =====

    static List<List<Object>> pageRows(List<Item> items) {
        List<List<Object>> rows = new ArrayList<>(items.size());
        for (Item it : items) {
            rows.add(List.<Object>of(TransitData.text(it.name(), NAME_BYTES), TransitData.text(it.author(), NAME_BYTES),
                    it.sizeX(), it.sizeY(), it.sizeZ(), it.blockCount(), it.entityCount(),
                    (int) (it.createdMs() >>> 32), (int) it.createdMs(),
                    it.authorDir(), it.fileName(), it.imported()));
        }
        return rows;
    }

    /** A page read back. */
    public static List<Item> itemsOf(List<?> rows) {
        List<Item> out = new ArrayList<>(rows.size());
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            out.add(new Item((String) r.get(0), (String) r.get(1), (Integer) r.get(2), (Integer) r.get(3),
                    (Integer) r.get(4), (Integer) r.get(5), (Integer) r.get(6),
                    TransitData.dayTime((Integer) r.get(7), (Integer) r.get(8)), (String) r.get(9),
                    (String) r.get(10), (Boolean) r.get(11)));
        }
        return out;
    }

    /** The material answer's key as the cache keys it ("authorDir/fileName"). */
    public static String materialKey(String authorDir, String fileName) {
        return authorDir + "/" + fileName;
    }
}
