package com.trainsystemutilities.electrification.blockentity;

import com.trainsystemutilities.electrification.ElectrificationConstants;
import com.trainsystemutilities.electrification.block.InsulatorBlock;
import com.trainsystemutilities.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 碍子 BE。架線接続グラフのノード本体。
 *
 * <p>Phase A1: 取付点 (= ワイヤー端点ワールド座標) を返す getter のみ持つ。
 * 接続情報そのものは {@link com.trainsystemutilities.electrification.wire.WireNetworkSavedData} (A2)
 * 側で一元管理する。BE は描画/ピッキング高速化のためにあとで「自分に入射する接続 ID キャッシュ」を
 * 持つ予定 (A4)。
 */
public class InsulatorBlockEntity extends BlockEntity {

    public InsulatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INSULATOR.get(), pos, state);
    }

    /** ワイヤーが接続される世界座標 (= 碍子ポスト先端)。 */
    public Vec3 getAttachmentPos() {
        return attachmentOf(getBlockPos(), getBlockState().getValue(InsulatorBlock.FACING));
    }

    /** 静的版: BE をロードせずに座標だけから取付点を計算する用 (= SavedData / 描画から使う)。 */
    public static Vec3 attachmentOf(BlockPos pos, Direction facing) {
        double off = ElectrificationConstants.INSULATOR_TIP_OFFSET;
        return Vec3.atCenterOf(pos).add(
                facing.getStepX() * off,
                facing.getStepY() * off,
                facing.getStepZ() * off);
    }
}
