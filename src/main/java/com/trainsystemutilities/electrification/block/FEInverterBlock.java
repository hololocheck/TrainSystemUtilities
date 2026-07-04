package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.FEInverterBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * FE インバータ (3 連マルチブロック)。床下機器のように 3 ブロック分の長さを占める。
 *
 * <p>構造: {@link Part#TAIL} → {@link Part#CENTER} → {@link Part#HEAD} の順に FACING 方向に並ぶ。
 * 設置時に CENTER がクリック位置、HEAD/TAIL が前後に自動配置される。BlockEntity は CENTER のみ持ち、
 * HEAD/TAIL は描画と当たり判定だけの "シャドウ" ブロック。
 *
 * <p>当たり判定: 各パートが 1×0.5×1 (高さ半ブロック)。3 連で合計 3×0.5×1 の床下機器形状。
 */
public class FEInverterBlock extends BaseEntityBlock {

    public enum Part implements StringRepresentable {
        HEAD("head"),
        CENTER("center"),
        TAIL("tail");

        private final String name;
        Part(String name) { this.name = name; }

        @Override
        public String getSerializedName() { return name; }
    }

    public static final MapCodec<FEInverterBlock> CODEC = simpleCodec(FEInverterBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /** 全パート共通の hitbox: ブロックの上半分 (= Y 8〜16)。
     *  モデルが上半分にあるので当たり判定もそこに合わせる。これで「車両下にぶら下がる」見た目になる。 */
    private static final VoxelShape SHAPE = Block.box(0, 8, 0, 16, 16, 16);

    public FEInverterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.CENTER));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos centerPos = context.getClickedPos();
        BlockPos headPos = centerPos.relative(facing);
        BlockPos tailPos = centerPos.relative(facing.getOpposite());
        // 設置可能な空きが前後にあるか確認 (= 3 ブロック分連続して空く必要)
        if (!level.getBlockState(headPos).canBeReplaced(context)
                || !level.getBlockState(tailPos).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.CENTER);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                             @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide()) return;
        if (state.getValue(PART) != Part.CENTER) return;
        Direction facing = state.getValue(FACING);
        // HEAD = FACING 方向、TAIL = 反対方向
        BlockState headState = defaultBlockState().setValue(FACING, facing).setValue(PART, Part.HEAD);
        BlockState tailState = defaultBlockState().setValue(FACING, facing).setValue(PART, Part.TAIL);
        level.setBlock(pos.relative(facing), headState, 3);
        level.setBlock(pos.relative(facing.getOpposite()), tailState, 3);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            Direction facing = state.getValue(FACING);
            // 自分のパート以外の 2 ブロックも一緒に削除 (= 一塊として扱う)
            switch (state.getValue(PART)) {
                case HEAD -> {
                    Direction back = facing.getOpposite();
                    safeRemove(level, pos.relative(back));         // CENTER
                    safeRemove(level, pos.relative(back, 2));      // TAIL
                }
                case CENTER -> {
                    safeRemove(level, pos.relative(facing));               // HEAD
                    safeRemove(level, pos.relative(facing.getOpposite())); // TAIL
                }
                case TAIL -> {
                    safeRemove(level, pos.relative(facing));     // CENTER
                    safeRemove(level, pos.relative(facing, 2));  // HEAD
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** 同じインバータ部品なら削除する (= 異なるブロックは触らない、再帰防止)。 */
    private static void safeRemove(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FEInverterBlock) {
            level.removeBlock(pos, false);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // CENTER のみ実際の蓄電 BE を持つ。HEAD/TAIL は BE 無し (= 軽量化)
        if (state.getValue(PART) != Part.CENTER) return null;
        return new FEInverterBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        // HEAD/TAIL クリック時は CENTER に転送
        if (state.getValue(PART) != Part.CENTER) {
            BlockPos centerPos = centerPosOf(pos, state);
            BlockState centerState = level.getBlockState(centerPos);
            if (centerState.getBlock() instanceof FEInverterBlock) {
                return useWithoutItem(centerState, level, centerPos, player, hit);
            }
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof FEInverterBlockEntity inv)) return InteractionResult.PASS;
        int fe = inv.getEnergyStored();
        int cap = inv.getMaxEnergyStored();
        ChatFormatting color = fe > 0 ? ChatFormatting.AQUA : ChatFormatting.RED;
        Component drive = Component.translatable(inv.canDrive()
                ? "tsu.fe_inverter.state_drivable" : "tsu.fe_inverter.state_undrivable");
        player.displayClientMessage(Component.translatable("tsu.fe_inverter.status_fmt",
                drive, String.format("%,d", fe), String.format("%,d", cap))
                .withStyle(color), false);
        return InteractionResult.CONSUME;
    }

    /** HEAD / TAIL の位置から CENTER の位置を算出。 */
    private static BlockPos centerPosOf(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return switch (state.getValue(PART)) {
            case HEAD -> pos.relative(facing.getOpposite());
            case TAIL -> pos.relative(facing);
            case CENTER -> pos;
        };
    }
}
