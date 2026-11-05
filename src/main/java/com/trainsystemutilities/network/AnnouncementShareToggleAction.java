package com.trainsystemutilities.network;

import com.manta.api.util.safe.SafeName;
import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;

/**
 * 共有先 station の toggle (ON/OFF 反転) — the railway management host's {@code announcement-share} action
 * ({@link RailwayManagementData}; AnnouncementShareTogglePayload until MANTA_7_CONCEPT C4).
 * shareType 0=検知カード, 1=範囲指定ボード。 変更後の設定は host の field に載る。
 */
public final class AnnouncementShareToggleAction {

    public static final byte TYPE_DETECTION = 0;
    public static final byte TYPE_RANGE = 1;

    private AnnouncementShareToggleAction() {}

    public static void apply(RailwayManagementBlockEntity rmbe, String stationName, int shareType) {
        if (stationName == null || stationName.isEmpty()) return;
        // P0-4 #7: station name is a user-supplied display name used as a config key.
        // Reject control chars / BiDi / oversized strings; silently drop the toggle on invalid input.
        try {
            SafeName.validate(stationName);
        } catch (IllegalArgumentException e) {
            return;
        }

        var cfg = rmbe.getAnnouncementConfig();
        if (shareType == TYPE_RANGE) {
            cfg.toggleRangeShared(stationName);
        } else {
            cfg.toggleDetectionShared(stationName);
        }
        rmbe.setAnnouncementConfig(cfg);
    }
}
