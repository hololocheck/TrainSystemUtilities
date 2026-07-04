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
 * 薄型モニターブロック（片面）。
 * 厚み: HALF=8px, SLIM=4px
 */
public class ThinMonitorBlock extends MonitorBlock {
    public static final MapCodec<ThinMonitorBlock> CODEC = simpleCodec(ThinMonitorBlock::new);

    private final int thickness; // px (8 or 4)

    // VoxelShapes for each facing direction
    private final VoxelShape SHAPE_NORTH;
    private final VoxelShape SHAPE_SOUTH;
    private final VoxelShape SHAPE_WEST;
    private final VoxelShape SHAPE_EAST;

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    public ThinMonitorBlock(Properties properties, int thickness) {
        super(properties);
        this.thickness = thickness;
        double t = thickness; // px out of 16
        // 前面寄せ: 画面がfacing方向の面に配置
        SHAPE_NORTH = box(0, 0, 0, 16, 16, t);
        SHAPE_SOUTH = box(0, 0, 16 - t, 16, 16, 16);
        SHAPE_WEST  = box(0, 0, 0, t, 16, 16);
        SHAPE_EAST  = box(16 - t, 0, 0, 16, 16, 16);
    }

    public ThinMonitorBlock(Properties properties) {
        this(properties, 8);
    }

    public int getThickness() { return thickness; }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> SHAPE_NORTH;
            case SOUTH -> SHAPE_SOUTH;
            case WEST -> SHAPE_WEST;
            case EAST -> SHAPE_EAST;
            default -> SHAPE_NORTH;
        };
    }
}
