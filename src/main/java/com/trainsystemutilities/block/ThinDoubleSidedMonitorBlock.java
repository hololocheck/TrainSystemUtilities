package com.trainsystemutilities.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 薄型両面モニターブロック。
 * 厚み: HALF=8px, SLIM=4px
 */
public class ThinDoubleSidedMonitorBlock extends DoubleSidedMonitorBlock {
    public static final MapCodec<ThinDoubleSidedMonitorBlock> CODEC = simpleCodec(ThinDoubleSidedMonitorBlock::new);

    private final int thickness;

    private final VoxelShape SHAPE_NORTH;
    private final VoxelShape SHAPE_SOUTH;
    private final VoxelShape SHAPE_WEST;
    private final VoxelShape SHAPE_EAST;

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public ThinDoubleSidedMonitorBlock(Properties properties, int thickness) {
        super(properties);
        this.thickness = thickness;
        double t = thickness;
        // 両面なので中央に配置
        double offset = (16 - t) / 2.0;
        SHAPE_NORTH = box(0, 0, offset, 16, 16, offset + t);
        SHAPE_SOUTH = box(0, 0, offset, 16, 16, offset + t);
        SHAPE_WEST  = box(offset, 0, 0, offset + t, 16, 16);
        SHAPE_EAST  = box(offset, 0, 0, offset + t, 16, 16);
    }

    public ThinDoubleSidedMonitorBlock(Properties properties) {
        this(properties, 8);
    }

    public int getThickness() { return thickness; }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH, SOUTH -> SHAPE_NORTH;
            case WEST, EAST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }
}
