package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.SubstationBlockEntity;
import com.trainsystemutilities.electrification.wire.SubstationRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 変電所キュービクル (3×4×2 マルチブロック = 24 ブロック)。
 * FE を受け取って隣接碍子経由で架線網へ給電する。
 *
 * <p>マルチブロック構造:
 * <ul>
 *   <li>コアブロック = この {@link SubstationBlock} の単一インスタンス。BE を持つ</li>
 *   <li>ダミーブロック × 23 = {@link SubstationDummyBlock}。setPlacedBy で自動配置される</li>
 * </ul>
 *
 * <p>レイアウトは {@link SubstationMultiblock} を参照。FACING プロパティで 4 方向回転。
 * 描画は {@link com.trainsystemutilities.client.electrification.SubstationBlockRenderer} (Geckolib) で
 * 3×4×2 全体を一括描画 (= ダミーは invisible)。
 */
public class SubstationBlock extends BaseEntityBlock {
    public static final MapCodec<SubstationBlock> CODEC = simpleCodec(SubstationBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** 撤去中フラグ: {@link #onRemove} → ダミー破壊 → ダミーの onRemove → コア破壊の
     *  無限再帰を防ぐ。 */
    private static volatile boolean isDismantling = false;

    public SubstationBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos corePos = context.getClickedPos();
        // マルチブロック全 24 マスに置けるか事前チェック
        if (!SubstationMultiblock.canPlace(context.getLevel(), corePos, facing)) {
            // 配置失敗 → null を返して vanilla 側の placement を拒否
            return null;
        }
        return this.defaultBlockState().setValue(FACING, facing);
    }

    /** コアブロック設置直後に呼ばれる。23 マスのダミーを自動配置する。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                              @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        Direction facing = state.getValue(FACING);
        // ダミー設置先 (23 マス) の空きを再確認。
        // ※ コア位置 (pos) は既に SubstationBlock で置かれているため、ここで含めると常に
        //   canBeReplaced=false でチェック失敗してしまう。getDummyPositions で除外する。
        for (BlockPos p : SubstationMultiblock.getDummyPositions(pos, facing)) {
            if (!level.getBlockState(p).canBeReplaced()) {
                // 置けない → コア自身を撤去
                level.removeBlock(pos, false);
                if (placer instanceof Player player) {
                    player.displayClientMessage(Component.translatable("tsu.substation.no_space")
                            .withStyle(ChatFormatting.RED), true);
                }
                return;
            }
        }
        SubstationMultiblock.placeDummies(level, pos, facing);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // ENTITYBLOCK_ANIMATED = vanilla baked model はレンダーせず、BlockEntityRenderer のみで描画
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SubstationBlockEntity(pos, state);
    }

    // 給電 tick は SubstationTickHandler がグローバルに実行する設計に移行したため、
    // BE 単位の getTicker は不要。

    /** ブロック破壊時に:
     *  1. SubstationRegistry から登録解除
     *  2. 残り 23 個のダミーブロックを撤去 */
    @Override
    protected void onRemove(BlockState oldState, Level level, BlockPos pos, BlockState newState,
                              boolean isMoving) {
        if (!oldState.is(newState.getBlock())) {
            // SavedData 登録解除
            if (level instanceof ServerLevel server) {
                SubstationRegistry.get(server).unregister(pos);
            }
            // ダミー撤去 (= dismantle flag で再帰呼び出しを抑止)
            if (!isDismantling) {
                isDismantling = true;
                try {
                    Direction facing = oldState.getValue(FACING);
                    for (BlockPos dp : SubstationMultiblock.getDummyPositions(pos, facing)) {
                        BlockState ds = level.getBlockState(dp);
                        if (ds.getBlock() instanceof SubstationDummyBlock) {
                            level.setBlock(dp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    }
                } finally {
                    isDismantling = false;
                }
            }
        }
        super.onRemove(oldState, level, pos, newState, isMoving);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SubstationBlockEntity sub)) return InteractionResult.PASS;
        int fe = sub.getStoredEnergy();
        int cap = SubstationBlockEntity.CAPACITY;
        int conns = sub.getNetworkConnectionCount();
        Component status = Component.translatable(conns > 0 ? "tsu.substation.status_energized"
                : (fe > 0 ? "tsu.substation.status_waiting" : "tsu.substation.status_no_fe"));
        ChatFormatting color = conns > 0 ? ChatFormatting.AQUA
                : (fe > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED);
        player.displayClientMessage(Component.translatable("tsu.substation.status_fmt",
                status, String.format("%,d", fe), String.format("%,d", cap), conns)
                .withStyle(color), false);
        return InteractionResult.CONSUME;
    }

    /** 中央ブロック (= コア) が dismantle 中であることを {@link SubstationDummyBlock} から
     *  見るための公開ヘルパ。 */
    public static boolean isDismantlingMultiblock() { return isDismantling; }
}
