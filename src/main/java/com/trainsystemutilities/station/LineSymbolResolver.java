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
 *   <li>その (name, pos) で {@link LineSymbolStore} を引く</li>
 * </ol>
 *
 * <p><b>1.0.10: 権威は store 一本。</b> 以前は
 * {@link ManagementComputerBlockEntity#serverInstances()} を走査して block entity の
 * {@code stationSymbolMap} を直接読んでいた。 これは store を経由しない<b>もう 1 つの読み手</b>で、
 * メモリーカードが抜かれて駅から取り下げた記号を、 乗換ターミナル側だけが表示し続けていた
 * (独立レビュー 2026-08-29 の指摘)。 モニター / 駅名サイン / GUI と同じ store を見る。
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
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return null;
            var store = LineSymbolStore.get(server);
            LineSymbol s = store.getSymbol(ManagementComputerBlockEntity.stationKey(name, pos));
            if (s != null) return s;
            // pos なしフォールバック (legacy な bare-name 公開)
            return store.getSymbol(name);
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
