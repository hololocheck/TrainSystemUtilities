package com.trainsystemutilities.structure.blockentity;

import com.trainsystemutilities.blockentity.LineSymbol;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.station.LineSymbolStore;
import com.trainsystemutilities.station.StationAdjacency;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.structure.block.StationNameSignBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 駅名サイン BE。 4 セル マルチブロックの master が 1 個、 dummy が 3 個。
 *
 * <p>UI を持たず、 表示内容は全て server が解決して client へ同期する:
 * <ul>
 *   <li>設置位置を含む駅グループ → 中央に出す駅名。 範囲外なら何も表示しない</li>
 *   <li>その駅グループに割り当てられた路線記号 → 帯色 (= 記号の縁色) と 駅名左の記号バッジ</li>
 *   <li>同じ路線記号 (letters 一致) で番号 +1 / -1 の駅 → 左右の隣駅名</li>
 * </ul>
 *
 * <p>隣駅の探索は {@link LineSymbolStore} を走査する。 chunk load に依存せず全駅の割り当てを
 * 見られるのはこの store だけで、 遠方の駅の BE を触りに行くと unload 時に解決できない (§5.1)。
 */
public class StationNameSignBlockEntity extends BlockEntity implements GeoBlockEntity {

    /** 解決間隔 (tick)。 駅グループ / 記号の変更は頻繁ではないので粗くてよい。 */
    private static final int RESOLVE_INTERVAL = 40;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** master の位置。 null なら自分が master。 */
    @Nullable
    private BlockPos masterPos;

    // --- server が解決し client へ同期する表示状態 ---
    /** 駅グループ範囲内か。 false なら client は何も描かない。 */
    private boolean inGroup;
    private String stationName = "";
    /** 隣駅 (記号番号 +1) の駅名。 未解決なら空。 表示は左側。 */
    private String prevStationName = "";
    /** 隣駅 (記号番号 -1) の駅名。 未解決なら空。 表示は右側。 */
    private String nextStationName = "";
    /** 路線記号。 未割り当てなら null (= 帯もバッジも出さない)。 */
    @Nullable
    private LineSymbol symbol;

    private int resolveCooldown = 0;

    public StationNameSignBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STATION_NAME_SIGN.get(), pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        // StationSignWorldRenderer walks only the chunks that hold a client sign
        com.trainsystemutilities.blockentity.ClientBlockEntityChunks.STATION_SIGNS.joined(this, level);
    }

    // --- Geckolib (静的モデル) ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 0, state -> PlayState.STOP));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    // --- master / dummy ---

    public boolean isMaster() { return masterPos == null; }

    @Nullable
    public BlockPos getMasterPos() { return masterPos; }

    public void setDummy(BlockPos master) {
        this.masterPos = master;
        setChanged();
    }

    // --- 表示状態 (client からも読む) ---

    public boolean isInGroup() { return inGroup; }
    public String getStationName() { return stationName; }
    public String getPrevStationName() { return prevStationName; }
    public String getNextStationName() { return nextStationName; }
    @Nullable
    public LineSymbol getSymbol() { return symbol; }

    // --- server tick ---

    public static void tick(Level level, BlockPos pos, BlockState state, StationNameSignBlockEntity be) {
        if (level.isClientSide() || !be.isMaster()) return;
        if (--be.resolveCooldown > 0) return;
        be.resolveCooldown = RESOLVE_INTERVAL;
        be.resolve();
    }

    /** 駅グループ / 路線記号 / 隣駅を解決し、 変化があれば client へ同期する。 */
    private void resolve() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();

        boolean oldInGroup = inGroup;
        String oldStation = stationName, oldPrev = prevStationName, oldNext = nextStationName;
        CompoundTag oldSymbol = symbol != null ? symbol.save() : null;

        String dim = level.dimension().location().toString();
        StationGroupSavedData sgData = StationGroupSavedData.get(server);
        StationGroup group = sgData.findContaining(dim, worldPosition);

        if (group == null) {
            // 駅グループ範囲外 = 何も表示しない
            inGroup = false;
            stationName = "";
            prevStationName = "";
            nextStationName = "";
            symbol = null;
        } else {
            inGroup = true;
            stationName = group.name() != null ? group.name() : "";
            symbol = findSymbolForGroup(LineSymbolStore.get(server), dim, group);

            // 隣駅は線路グラフ (= 路線マップが描いているもの) をたどって決める。 左右は看板の
            // FACING から自動割り当て (§StationAdjacency)。 路線記号の番号とは無関係。
            Direction facing = getBlockState().hasProperty(StationNameSignBlock.FACING)
                    ? getBlockState().getValue(StationNameSignBlock.FACING) : Direction.NORTH;
            StationAdjacency.Result adj = StationAdjacency.assign(
                    StationAdjacency.candidates(serverLevel, group), facing);
            prevStationName = adj.left();
            nextStationName = adj.right();
        }

        boolean changed = oldInGroup != inGroup
                || !oldStation.equals(stationName)
                || !oldPrev.equals(prevStationName)
                || !oldNext.equals(nextStationName)
                || !java.util.Objects.equals(oldSymbol, symbol != null ? symbol.save() : null);
        if (changed) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** この駅グループの範囲内にある駅に割り当てられた路線記号を 1 つ返す (無ければ null)。 */
    @Nullable
    private static LineSymbol findSymbolForGroup(LineSymbolStore store, String dim, StationGroup group) {
        for (var e : store.entries().entrySet()) {
            BlockPos p = posOfStationKey(e.getKey());
            if (p != null && group.contains(dim, p)) return e.getValue();
        }
        return null;
    }

    /** stationKey ({@code name|posLong}) から座標を取り出す。 legacy の pos なしキーは null。 */
    @Nullable
    private static BlockPos posOfStationKey(String key) {
        int bar = key.lastIndexOf('|');
        if (bar < 0) return null;
        try {
            return BlockPos.of(Long.parseLong(key.substring(bar + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- NBT / 同期 ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (masterPos != null) tag.putLong("Master", masterPos.asLong());
        tag.putBoolean("InGroup", inGroup);
        tag.putString("Station", stationName);
        tag.putString("Prev", prevStationName);
        tag.putString("Next", nextStationName);
        if (symbol != null) tag.put("Symbol", symbol.save());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        masterPos = tag.contains("Master") ? BlockPos.of(tag.getLong("Master")) : null;
        inGroup = tag.getBoolean("InGroup");
        stationName = tag.getString("Station");
        prevStationName = tag.getString("Prev");
        nextStationName = tag.getString("Next");
        symbol = tag.contains("Symbol") ? LineSymbol.load(tag.getCompound("Symbol")) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
