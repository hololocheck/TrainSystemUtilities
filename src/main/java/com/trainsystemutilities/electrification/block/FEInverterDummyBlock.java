package com.trainsystemutilities.electrification.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * FE インバータ (装飾版)。見た目は {@link FEInverterBlock} と完全に同一の 3 連マルチブロックだが、
 * BlockEntity を持たず FE バッファとしての機能も一切持たない。
 *
 * <p>用途: パンタグラフを純装飾として使いたいプレイヤー向け。実装上は
 * {@code dummyInverters} 集合に登録されるだけで、ContraptionElectrificationTicker の
 * FE 集電 / 走行停止判定では「インバータあり」と見なされない (= 装飾モード)。
 *
 * <p>ただし管理コンピュータ UI 側のゲートは「実 inverter または dummy inverter のいずれか + panto」
 * を満たせばパンタ展開/折畳ボタンを表示するため、UI から装飾パンタの動作制御が可能になる。
 *
 * <p>マルチブロック配置 / 当たり判定 / 削除挙動はすべて {@link FEInverterBlock} から継承。
 */
public class FEInverterDummyBlock extends FEInverterBlock {

    public static final MapCodec<FEInverterDummyBlock> CODEC = simpleCodec(FEInverterDummyBlock::new);

    public FEInverterDummyBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
    }
}
