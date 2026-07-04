package com.trainsystemutilities.station;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.trainsystemutilities.blockentity.LineSymbol;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Create GlobalStation UUID から、それに割り当てられた {@link LineSymbol} を逆引きする
 * server-side ヘルパ。
 *
 * <p>仕組み:
 * <ol>
 *   <li>Create 全 trackNetworks を走査し、target の stationId に一致する
 *       {@link GlobalStation} を探す → station 名 + 位置を取得</li>
 *   <li>{@link ManagementComputerBlockEntity#serverInstances()} を走査し、
 *       (name, pos) ペアに対応する LineSymbol を引いている管理コンピュータを見つける</li>
 *   <li>最初に見つかったものを返す (同じ駅を複数 MC に登録するのは想定外)</li>
 * </ol>
 *
 * <p>結果は thread-unsafe なので server tick から呼び出すこと。
 */
public final class LineSymbolResolver {

    private LineSymbolResolver() {}

    public static LineSymbol forStationId(UUID stationId) {
        if (stationId == null) return null;
        try {
            GlobalStation gs = findGlobalStation(stationId);
            if (gs == null) return null;
            String name = gs.name;
            BlockPos pos = gs.getBlockEntityPos();
            if (name == null) return null;
            for (ManagementComputerBlockEntity mc : ManagementComputerBlockEntity.serverInstances()) {
                LineSymbol s = mc.getSymbolForStation(name, pos);
                if (s != null) return s;
                // pos なしフォールバック (legacy)
                LineSymbol s2 = mc.getSymbolForStation(name);
                if (s2 != null) return s2;
            }
        } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[LineSymbol] station symbol lookup failed", ignored); }
        return null;
    }

    private static GlobalStation findGlobalStation(UUID id) {
        if (Create.RAILWAYS == null || Create.RAILWAYS.trackNetworks == null) return null;
        for (var graph : Create.RAILWAYS.trackNetworks.values()) {
            try {
                for (GlobalStation s : graph.getPoints(EdgePointType.STATION)) {
                    if (id.equals(s.getId())) return s;
                }
            } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[LineSymbol] station symbol lookup failed", ignored); }
        }
        return null;
    }
}
