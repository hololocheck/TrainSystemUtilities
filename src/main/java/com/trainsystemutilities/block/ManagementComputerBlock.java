package com.trainsystemutilities.block;

import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import com.mojang.serialization.MapCodec;
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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 管理用コンピューター
 * フロー: ブロック設置後、GUIを開いてモニター連携カード (MonitorLinkCardItem) をスロット1へ挿入 → 自動リンク
 * (Phase 5d 以前は item NBT 経由の右クリック登録だったが廃止)
 */
public class ManagementComputerBlock extends BaseEntityBlock {
    public static final MapCodec<ManagementComputerBlock> CODEC = simpleCodec(ManagementComputerBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LINKED = BooleanProperty.create("linked");

    /** 1 cell 内に collision (= 高さ 32 voxel = 2 block 高。 券売機と同じ方式)。
     *  モデルは上端 35 voxel まで伸びるが、 上の 3 voxel は視覚のみ。 */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 32, 16);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public ManagementComputerBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LINKED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LINKED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Phase 5d: 設置時の自動リンクは廃止。LINKED は GUI からのカード挿入後に true に切り替わる想定。
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(LINKED, false);
    }

    // Phase 5d: setPlacedBy で item NBT を読んで自動リンクするロジックは廃止。
    // 代わりに GUI のモニター連携カードスロット (slot 1) でリンクする。

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ManagementComputerBlockEntity(pos, state);
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ManagementComputerBlockEntity computerBE) {
            // 初回設置者を所有者として記録
            if (computerBE.getOwnerUUID() == null) {
                computerBE.setOwner(player.getUUID(), player.getName().getString());
            }
            // プライベートモード時のアクセス制御
            if (!computerBE.canAccess(player)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "\u00a7c\u3053\u306e\u30d6\u30ed\u30c3\u30af\u306f " + (computerBE.getOwnerName().isEmpty() ? "someone" : computerBE.getOwnerName()) + " \u306b\u3088\u3063\u3066\u30ed\u30c3\u30af\u3055\u308c\u3066\u3044\u307e\u3059"), true);
                return InteractionResult.FAIL;
            }
            // モニターリンク不要でGUIを開ける（マップ機能等）
            computerBE.openManagementScreen(player);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ManagementComputerBlockEntity computerBE) {
                // 未書き戻しの設定をカードへ流し込んでから落とす (20 tick の書き戻し待ちの取りこぼし対策)。
                // dropContents より前に行うこと — drop 後は memoryCard が EMPTY になり書けない。
                computerBE.flushSettingsToCard();
                // 壊された時点で自分が出していた路線記号を LineSymbolStore から取り下げる。
                // 設定はメモリーカード側に残っているので、置き直してカードを差せば復元される。
                // setRemoved() ではなくここで行う: setRemoved は chunk unload でも呼ばれ、
                // unload のたびに全駅の記号が消えることになる。
                computerBE.withdrawSymbolsFromStore();
                net.minecraft.world.Containers.dropContents(level, pos, computerBE);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return createTickerHelper(type, ModBlockEntities.MANAGEMENT_COMPUTER.get(),
                ManagementComputerBlockEntity::tick);
    }
}
