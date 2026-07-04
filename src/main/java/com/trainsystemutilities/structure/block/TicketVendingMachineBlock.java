package com.trainsystemutilities.structure.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.structure.blockentity.TicketVendingMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** 券売機。 4 方向、 2 ブロック高の collision、 Geckolib 描画。 */
public class TicketVendingMachineBlock extends BaseEntityBlock {
    public static final MapCodec<TicketVendingMachineBlock> CODEC = simpleCodec(TicketVendingMachineBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 1 cell 内に collision (= 高さ 32 voxel = 2 block 高)。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 32, 16);

    public TicketVendingMachineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面が player を向くよう 90° 反時計回り回転
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getCounterClockWise());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TicketVendingMachineBlockEntity(pos, state);
    }

    /** 既存の駅グループ範囲内に設置されたら自動で所属駅に紐付け (= 後設置で自動接続)。 */
    @Override
    protected void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                           BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof net.minecraft.server.level.ServerLevel sl
                && level.getBlockEntity(pos) instanceof TicketVendingMachineBlockEntity be) {
            String dim = level.dimension().location().toString();
            var group = com.trainsystemutilities.station.StationGroupSavedData.get(sl.getServer())
                    .findContaining(dim, pos);
            if (group != null) be.setAssociatedStationGroup(group.id());
        }
    }

    /** 右クリック → 券売機 UI を開く。 所属駅を遅延解決し、 販売可な行き先を計算して S2C 送信。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof TicketVendingMachineBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return InteractionResult.CONSUME;
        }
        net.minecraft.server.MinecraftServer server = serverPlayer.getServer();
        if (server == null) return InteractionResult.CONSUME;
        var sgData = com.trainsystemutilities.station.StationGroupSavedData.get(server);

        // 所属駅を遅延解決 (= 未紐付け / 範囲外なら findContaining で再解決 → BE に保存)
        String dim = level.dimension().location().toString();
        java.util.UUID assoc = be.getAssociatedStationGroup();
        com.trainsystemutilities.station.StationGroup origin = assoc != null ? sgData.get(assoc) : null;
        if (origin == null || !origin.contains(dim, pos)) {
            com.trainsystemutilities.station.StationGroup found = sgData.findContaining(dim, pos);
            if (found != null) {
                origin = found;
                be.setAssociatedStationGroup(found.id());
            }
        }
        // 駅範囲外 (= 所属駅なし) では券売機を開かない (= 駅に設置してこそ機能する)
        if (origin == null) {
            serverPlayer.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("tsu.ticket.no_station_warn")
                            .withStyle(net.minecraft.ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        java.util.UUID originId = origin.id();
        String originName = origin.name();

        // 行き先 = 自ネットワーク (= origin と同じ物理線路網) かつ 販売可な着駅 − 自駅、 名前順。
        // ネットワーク判定は列車スケジュールに依存せず Create TrackGraph (= 線路の繋がり) で行うので、
        // 列車未運行でも管理用コンピューターで sellable にした駅は表示される。 管理用コンピューターの
        // networkGroups と同じ物理ネットワーク基準なので、 両者の表示が一致する。
        var sellable = com.trainsystemutilities.station.TicketConfigSavedData.get(server).sellable();
        net.minecraft.core.BlockPos seed =
                (origin.stationBlockPositions() != null && !origin.stationBlockPositions().isEmpty())
                        ? origin.stationBlockPositions().get(0) : pos;
        java.util.Set<java.util.UUID> network = new java.util.HashSet<>();
        for (net.minecraft.core.BlockPos sp :
                com.trainsystemutilities.network.TrackNetworkScanner.networkStationPositions(level, seed)) {
            var ng = sgData.findContaining(dim, sp);
            if (ng != null) network.add(ng.id());
        }
        java.util.List<com.trainsystemutilities.network.OpenTicketVendingPayload.Dest> dests =
                new java.util.ArrayList<>();
        for (com.trainsystemutilities.station.StationGroup g : sgData.all()) {
            if (originId != null && g.id().equals(originId)) continue;
            if (!network.contains(g.id())) continue;               // 同一物理ネットワークのみ
            if (!sellable.isEmpty() && !sellable.contains(g.id())) continue;
            dests.add(new com.trainsystemutilities.network.OpenTicketVendingPayload.Dest(g.id(), g.name()));
        }
        dests.sort(java.util.Comparator.comparing(
                com.trainsystemutilities.network.OpenTicketVendingPayload.Dest::name));

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer,
                new com.trainsystemutilities.network.OpenTicketVendingPayload(pos, originName, dests));
        return InteractionResult.CONSUME;
    }
}
