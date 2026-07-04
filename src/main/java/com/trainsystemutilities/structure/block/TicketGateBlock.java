package com.trainsystemutilities.structure.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.structure.blockentity.TicketGateBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 自動改札。 4 方向、 単一ブロック、 Geckolib 描画。 券売機と同様に駅グループへ自動リンクし、
 * 切符を持って右クリック (= 投入) すると発駅→着駅の経路上にこの駅があれば扉を開き切符を回収、
 * {@link #CLOSE_DELAY_TICKS} 後に自動で閉じる。 経路外/無効な切符は開かない (= 切符以外では開かない)。
 * 柵扱いで筐体は常時 collision・ジャンプ越え不可、 中央通路のみ開閉。
 */
public class TicketGateBlock extends BaseEntityBlock {
    public static final MapCodec<TicketGateBlock> CODEC = simpleCodec(TicketGateBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    /** 切符投入で開いた後、 自動で閉じるまでの tick 数 (= 3 秒)。 */
    private static final int CLOSE_DELAY_TICKS = 60;

    // 柵扱い: 筐体 (= ゲート以外) は開いていても常時 collision、 高さ 24 voxel (= 1.5 block、 vanilla 柵と同じ)
    // で ジャンプ越え不可。 中央通路 (= ゲート) のみ 閉=塞ぐ / 開=通り抜け可。
    // NORTH/SOUTH: 通路は東西 (X 軸)、 筐体は南北 (Z 端) に。 EAST/WEST: 90° 回転で X/Z 入替。
    // 通路幅は player 当たり判定 (0.6 block = 9.6 voxel) を確実に通すため 12 voxel 確保
    // (= collision 筐体は視覚 3 voxel より薄い 2 voxel。 視覚に少し食い込むが通り抜け可能性を優先)。
    private static final VoxelShape CABINETS_NS = Shapes.or(
            Block.box(0, 0, 0, 16, 24, 2),
            Block.box(0, 0, 14, 16, 24, 16));
    private static final VoxelShape CLOSED_NS = Shapes.or(
            CABINETS_NS, Block.box(0, 0, 2, 16, 24, 14));
    private static final VoxelShape CABINETS_EW = Shapes.or(
            Block.box(0, 0, 0, 2, 24, 16),
            Block.box(14, 0, 0, 16, 24, 16));
    private static final VoxelShape CLOSED_EW = Shapes.or(
            CABINETS_EW, Block.box(2, 0, 0, 14, 24, 16));

    public TicketGateBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面が player を向くよう 90° 反時計回り回転 (= 券売機と同じ)
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getCounterClockWise());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        boolean open = state.getValue(OPEN);
        return switch (state.getValue(FACING)) {
            case EAST, WEST -> open ? CABINETS_EW : CLOSED_EW;
            default         -> open ? CABINETS_NS : CLOSED_NS; // NORTH / SOUTH
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TicketGateBlockEntity(pos, state);
    }

    /** 駅範囲内に設置されたら所属駅を自動紐付け (= 券売機と同方式)。 */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof net.minecraft.server.level.ServerLevel sl
                && level.getBlockEntity(pos) instanceof TicketGateBlockEntity gate) {
            String dim = level.dimension().location().toString();
            var group = com.trainsystemutilities.station.StationGroupSavedData.get(sl.getServer())
                    .findContaining(dim, pos);
            if (group != null) gate.setAssociatedStationGroup(group.id());
        }
    }

    /** 切符を持って右クリック = 投入。 経路上の駅なら 開く + 回収 + 自動閉じ。 経路外/無効は開かない。
     *  切符以外は default 動作 (= 空手トグル) に委ねる。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(com.trainsystemutilities.registry.ModItems.TICKET.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // 人が通り抜ける通路の前後 2 面 (= FACING に直交する horizontal face) からのみ受け付ける。
        // 筐体の側面 (FACING 軸) / 上下面 からは開かない (= 反対の筐体側からの誤開閉を防ぐ)。
        Direction clicked = hit.getDirection();
        Direction facing = state.getValue(FACING);
        if (clicked != facing.getClockWise() && clicked != facing.getCounterClockWise()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof TicketGateBlockEntity gate)
                || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return ItemInteractionResult.SUCCESS;
        }
        net.minecraft.server.MinecraftServer server = sp.getServer();
        if (server == null) return ItemInteractionResult.SUCCESS;

        java.util.UUID gateStation = resolveStation(server, level, pos, gate);
        if (gateStation == null) {
            sp.displayClientMessage(Component.translatable("tsu.ticket_gate.no_station_warn")
                    .withStyle(ChatFormatting.RED), true);
            return ItemInteractionResult.CONSUME;
        }

        boolean entered = Boolean.TRUE.equals(
                stack.get(com.trainsystemutilities.registry.ModDataComponents.TICKET_ENTERED.get()));
        java.util.UUID originId = stack.get(com.trainsystemutilities.registry.ModDataComponents.TICKET_FROM_ID.get());

        if (!entered) {
            // 未入場 → 入場処理。 入場は発駅でのみ可。 切符に「入場済」を記録し、 回収はしない (= 出場時に使う)。
            if (originId == null || !gateStation.equals(originId)) {
                sp.displayClientMessage(Component.translatable("tsu.ticket_gate.not_origin")
                        .withStyle(ChatFormatting.RED), true);
                return ItemInteractionResult.CONSUME;
            }
            stack.set(com.trainsystemutilities.registry.ModDataComponents.TICKET_ENTERED.get(), true);
            gate.openForTicket(CLOSE_DELAY_TICKS);
            sp.displayClientMessage(Component.translatable("tsu.ticket_gate.entered")
                    .withStyle(ChatFormatting.GREEN), true);
            return ItemInteractionResult.CONSUME;
        }

        // 入場済 → 出場処理: 運賃検証 → 有効なら 切符 1 枚回収 + 開 + 自動閉じ。
        if (!isValidExit(server, gateStation, stack)) {
            sp.displayClientMessage(Component.translatable("tsu.ticket_gate.invalid_ticket")
                    .withStyle(ChatFormatting.RED), true);
            return ItemInteractionResult.CONSUME;
        }
        stack.shrink(1);
        gate.openForTicket(CLOSE_DELAY_TICKS);
        sp.displayClientMessage(Component.translatable("tsu.ticket_gate.passed")
                .withStyle(ChatFormatting.GREEN), true);
        return ItemInteractionResult.CONSUME;
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, com.trainsystemutilities.registry.ModBlockEntities.TICKET_GATE.get(),
                (lvl, p, st, be) -> TicketGateBlockEntity.serverTick(lvl, p, st, be));
    }

    /** 改札の所属駅を解決。 紐付け済みかつ範囲内ならそれ、 さもなくば findContaining で再解決し保存。 範囲外は null。 */
    private static java.util.UUID resolveStation(net.minecraft.server.MinecraftServer server, Level level,
                                                 BlockPos pos, TicketGateBlockEntity gate) {
        var sgData = com.trainsystemutilities.station.StationGroupSavedData.get(server);
        String dim = level.dimension().location().toString();
        java.util.UUID assoc = gate.getAssociatedStationGroup();
        com.trainsystemutilities.station.StationGroup grp = assoc != null ? sgData.get(assoc) : null;
        if (grp == null || !grp.contains(dim, pos)) {
            grp = sgData.findContaining(dim, pos);
            if (grp != null) gate.setAssociatedStationGroup(grp.id());
        }
        return grp != null ? grp.id() : null;
    }

    /**
     * 切符の有効性 (= 日本の運賃ルール準拠)。 発駅からこの改札駅までの運賃 (= 物理線路距離) が、
     * 発駅→着駅の支払い運賃以下なら出場可。 近い駅での途中下車 (= 回収) は可、 着駅より遠い駅 (= 乗り越し)
     * は不可。 環状線は {@code TrainRouter.railFareDistance} が双方向の線路を「短い方の向き」で運賃計算する
     * (= 大都市近郊区間 / 環状線は最も安くなる経路で計算、 という実ルールに準拠)。 列車スケジュールに
     * 依存しないので列車未運行でも判定できる。
     */
    private static boolean isValidExit(net.minecraft.server.MinecraftServer server, java.util.UUID gateStation,
                                       ItemStack ticket) {
        java.util.UUID destId = ticket.get(com.trainsystemutilities.registry.ModDataComponents.TICKET_TO_ID.get());
        if (destId == null) return false;
        if (gateStation.equals(destId)) return true;   // 着駅は常に有効
        java.util.UUID originId = ticket.get(com.trainsystemutilities.registry.ModDataComponents.TICKET_FROM_ID.get());
        if (originId == null) return false;             // 旧切符 (発駅 ID 無し) → 着駅のみ有効
        if (gateStation.equals(originId)) return false; // 発駅では退出不可

        double farePaid   = com.trainsystemutilities.station.routing.TrainRouter.railFareDistance(server, originId, destId);
        double fareToGate = com.trainsystemutilities.station.routing.TrainRouter.railFareDistance(server, originId, gateStation);
        if (farePaid < 0 || fareToGate < 0) return false;  // 線路で繋がっていない → 無効 (着駅は上で処理済)
        return fareToGate <= farePaid + 0.5;  // 運賃 (物理線路距離) が支払い以下なら出場可 (= 手前の駅で途中下車可)。 乗り越しは不可。
    }
}
