package com.trainsystemutilities.network;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.screendoor.ScreenDoorCondition;

/**
 * ホームドア発火条件の add / remove / update — the railway management host's {@code door-condition} action
 * ({@link RailwayManagementData}; ScreenDoorConditionPayload until MANTA_7_CONCEPT C4). The payload's decoder
 * bounded none of its ints, and the screen sends a condition's track / event / action as the block entity holds
 * them (an older world's can be anything): railway-management-state.json keeps them unbounded, the op to 0..2 and
 * the index to 0 or more - the only values its senders give.
 *
 * <p>op:
 * <ul>
 *   <li>{@link #OP_ADD} — デフォルト entry を末尾に追加 (= idx / fields は無視)</li>
 *   <li>{@link #OP_REMOVE} — idx の entry を削除</li>
 *   <li>{@link #OP_UPDATE} — idx の entry を fields で上書き</li>
 * </ul>
 */
public final class ScreenDoorConditionAction {

    public static final int OP_ADD = 0;
    public static final int OP_REMOVE = 1;
    public static final int OP_UPDATE = 2;

    private ScreenDoorConditionAction() {}

    public static void apply(RailwayManagementBlockEntity rmbe, int op, int idx,
                             int trackNumber, int eventType, int actionType) {
        switch (op) {
            case OP_ADD -> rmbe.addScreenDoorCondition(ScreenDoorCondition.defaultEntry());
            case OP_REMOVE -> rmbe.removeScreenDoorCondition(idx);
            case OP_UPDATE -> rmbe.updateScreenDoorCondition(idx,
                    new ScreenDoorCondition(trackNumber, eventType, actionType));
            default -> {}
        }
    }
}
