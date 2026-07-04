package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import com.trainsystemutilities.electrification.blockentity.OverheadPoleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 架線柱 (overhead pole)。 線路脇に立つ縦支柱。
 *
 * <p>16 方向配置: ANGLE_8 = 0..15、 各 22.5°。 Create 線路の TrackShape と整合させて並べる用途。
 * カーブで滑らかに追従できるよう 8 方向 → 16 方向に拡張 (= 2025-06)。
 *
 * <p>描画は Geckolib (= {@link com.trainsystemutilities.client.electrification.OverheadPoleBlockRenderer})
 * 経由。 {@link RenderShape#ENTITYBLOCK_ANIMATED} でバニラ baked model 描画を抑止し、
 * BER で 16 方向の rotation を適用する (= blockstate JSON の y rotation は 0/90/180/270 のみで
 * 22.5° 刻みを表現できないため)。
 */
public class OverheadPoleBlock extends BaseEntityBlock {
    public static final MapCodec<OverheadPoleBlock> CODEC = simpleCodec(OverheadPoleBlock::new);

    /** 16 方向角 (0=N, 1=NNE, 2=NE, 3=ENE, 4=E, ... 15=NNW)、 値 × 22.5° = yaw 度数。 */
    public static final IntegerProperty ANGLE_8 = IntegerProperty.create("angle_8", 0, 7);

    /** Geckolib geo cube (= [-4,0,-4] to [4,16,4] = 8×16×8) に合わせた collision。 */
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 16, 12);

    public OverheadPoleBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(ANGLE_8, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ANGLE_8);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        int angle = OverheadPlacementHelper.resolveAngle8(context);
        return this.defaultBlockState().setValue(ANGLE_8, angle);
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
        return new OverheadPoleBlockEntity(pos, state);
    }
}
