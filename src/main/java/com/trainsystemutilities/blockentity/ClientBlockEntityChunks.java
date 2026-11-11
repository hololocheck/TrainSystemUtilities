package com.trainsystemutilities.blockentity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The chunks of a client level that hold one kind of TSU block entity, so a world renderer finds its own block entities
 * without walking every block entity of every chunk around the player every frame, of any mod. The station sign, poster
 * and monitor renderers each did that (measured 2026-11-11 with 1000 Xenophora printers nearby and no TSU block in the
 * world: 1 - 11 % of the render thread together, JFR).
 *
 * <p>Client block entities only: one joins when it is given a client level ({@code setLevel}, which every path that puts
 * a block entity into a client chunk runs - the chunk packet, a placed block - before {@code clearRemoved}) and leaves
 * once it is removed. Server block entities never join, so the integrated server's thread never touches a set (project
 * CLAUDE.md 5.1). Entries are held weakly, so a client level that is thrown away (a dimension change, a
 * disconnect) cannot keep its block entities alive, and {@link #chunksHolding} answers for the one level it is asked
 * about only.
 *
 * <p>A block entity given a client level outside any chunk joins too: the item renderer's cached model stand-in
 * ({@code GeoBlockItemRenderer}, at {@code BlockPos.ZERO}) and the poster screen's wiki stand-in. Such an entry only adds
 * its chunk to the ones walked; the walk still reads that chunk's own block entities, so nothing more is drawn.
 *
 * <p>The answer only picks the chunks a renderer walks; which block entities it draws, and in what order, is still decided
 * by the renderer's own walk over those chunks' block entities. The same shape as SAS's {@code ShownRangeDevices}.
 */
public final class ClientBlockEntityChunks {

    /** {@link com.trainsystemutilities.structure.blockentity.StationNameSignBlockEntity}: StationSignWorldRenderer. */
    public static final ClientBlockEntityChunks STATION_SIGNS = new ClientBlockEntityChunks();
    /** {@link PosterManagementBlockEntity}: PosterMonitorRenderer. */
    public static final ClientBlockEntityChunks POSTER_MANAGERS = new ClientBlockEntityChunks();
    /** {@link MonitorBlockEntity} and its subclasses: MonitorWorldRenderer. */
    public static final ClientBlockEntityChunks MONITORS = new ClientBlockEntityChunks();

    private final Set<BlockEntity> client =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private ClientBlockEntityChunks() {}

    /** From the block entity's {@code setLevel}, after super: joins when the level is a client's. */
    public void joined(BlockEntity be, Level level) {
        if (level != null && level.isClientSide()) client.add(be);
    }

    /**
     * The chunks ({@link ChunkPos#asLong}) of {@code level}'s block entities of this kind, or null when it has none - the
     * renderer then walks nothing. A removed one (a broken block, a chunk the client dropped) leaves the set here, on the
     * render thread, so no {@code setRemoved} override - which the server's block entities run too - touches it.
     */
    @Nullable
    public LongSet chunksHolding(Level level) {
        LongSet chunks = null;
        synchronized (client) {
            Iterator<BlockEntity> it = client.iterator();
            while (it.hasNext()) {
                BlockEntity be = it.next();
                if (be.isRemoved()) {
                    it.remove();
                    continue;
                }
                if (be.getLevel() != level) continue;
                BlockPos pos = be.getBlockPos();
                if (chunks == null) chunks = new LongOpenHashSet();
                chunks.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            }
        }
        return chunks;
    }
}
