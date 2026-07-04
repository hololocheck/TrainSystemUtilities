package com.trainsystemutilities.compat;

import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.fml.ModList;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TFMG (Create: The Factory Must Grow) 連携の facade。 P0-3 placeholder。
 *
 * <p>現状: TSU は TFMG 連携機能を持っていないが、 Phase 21 で電化システム拡張時に
 * TFMG の電力受給連携を予定。 cross-mod bridge pattern を 4 mod 全部で揃えるために
 * 先に scaffold を用意。
 *
 * <p>P0-3 cross-mod bridge 完走の symmetry 確保用。 actual SPI 呼出が必要になった
 * タイミングで {@link #safeRun} 経由で wire する。
 */
public final class TfmgBridge {

    public static final String MOD_ID = "create_things_and_misc";

    private static final AtomicBoolean selfCheckDone = new AtomicBoolean(false);
    private static volatile boolean available = false;

    private TfmgBridge() {}

    public static boolean isAvailable() {
        if (selfCheckDone.compareAndSet(false, true)) {
            try {
                available = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
                if (available) {
                    TrainSystemUtilities.LOGGER.info("[TfmgBridge] {} detected: future TFMG integration enabled", MOD_ID);
                } else {
                    TrainSystemUtilities.LOGGER.info(
                            "[TfmgBridge] {} not loaded (placeholder, no current integration)", MOD_ID);
                }
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    public static boolean safeRun(String label, Runnable action) {
        if (action == null) return false;
        if (!isAvailable()) return false;
        try {
            action.run();
            return true;
        } catch (LinkageError e) {
            TrainSystemUtilities.LOGGER.warn(
                    "[TfmgBridge] '{}' skipped due to TFMG class link error: {}", label, e.toString());
            available = false;
            return false;
        }
    }
}
