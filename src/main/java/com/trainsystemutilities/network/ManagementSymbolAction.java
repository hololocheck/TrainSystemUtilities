package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.LineSymbol;
import com.trainsystemutilities.blockentity.ManagementComputerBlockEntity;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 路線記号の保存 / 削除 / 駅への割当 — the management computer host's {@code symbol} action
 * ({@link ManagementComputerData}; ManagementSymbolPayload until MANTA_7_CONCEPT C4).
 */
public final class ManagementSymbolAction {

    public static final int ACTION_SAVE = 0;    // index < 0 = 新規追加, index >= 0 = 既存編集
    public static final int ACTION_DELETE = 1;  // index の記号を削除
    public static final int ACTION_ASSIGN = 2;  // 駅 ↔ 記号 割当 (symbolId == 0,0 で解除)

    private ManagementSymbolAction() {}

    public static void apply(ManagementComputerBlockEntity mc, ServerPlayer player, int action, int index,
                             String letters, int number, String borderColor, String name, int borderRadius,
                             String stationName, BlockPos stationPos, UUID symbolId) {
        switch (action) {
            // LineSymbol ctor が letters 大文字化 / number 0-99 / borderRadius 5-25 を clamp する。
            case ACTION_SAVE -> mc.saveLineSymbol(index,
                    new LineSymbol(letters, number, borderColor, name, borderRadius));
            case ACTION_DELETE -> mc.removeLineSymbol(index);
            case ACTION_ASSIGN -> {
                // A private railway management block's station takes a symbol only from a player the block admits
                // itself, as the door side (2026-10-01): the block this computer finds is asked, and the store too -
                // a private block registers its owner there while loaded (decision (19)), which covers a block the
                // computer cannot find (its chunk unloaded) and a station with several blocks, where the one found
                // need not be the one guarding. The store refuses the publish as well, so no reader shows it.
                RailwayManagementBlockEntity rm = mc.findOrScanManagerForStation(stationName, stationPos);
                if (rm != null && !ManagementComputerData.railwayManagerAdmits(rm, player)) return;
                if (!ManagementComputerData.stationAdmits(stationName, stationPos, player)) return;
                mc.assignSymbolToStation(stationName, stationPos,
                        symbolId.equals(new UUID(0, 0)) ? null : symbolId, player.getUUID());
            }
            default -> { /* action は宣言で 0..2 */ }
        }
    }
}
