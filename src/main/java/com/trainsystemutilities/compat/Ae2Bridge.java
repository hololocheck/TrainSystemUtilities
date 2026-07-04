package com.trainsystemutilities.compat;

import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.fml.ModList;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AE2 (Applied Energistics 2) 連携の facade。 P0-3。
 *
 * <p>用途: TSU は AE2 を optional dep として {@code TrainPresetAe2Integration} で参照する。
 * AE2 不在の環境で TSU が起動した場合、 graceful degrade して TSU 本体機能は動作させる。
 *
 * <p>使い方:
 * <pre>{@code
 * Ae2Bridge.safeRun("registerGridLinkable", () -> {
 *     TrainPresetAe2Integration.registerGridLinkable();
 * });
 * }</pre>
 */
public final class Ae2Bridge {

    public static final String MOD_ID = "ae2";

    private static final AtomicBoolean selfCheckDone = new AtomicBoolean(false);
    private static volatile boolean available = false;

    private Ae2Bridge() {}

    public static boolean isAvailable() {
        if (selfCheckDone.compareAndSet(false, true)) {
            try {
                available = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
                if (available) {
                    TrainSystemUtilities.LOGGER.info("[Ae2Bridge] {} detected: AE2 integration enabled", MOD_ID);
                } else {
                    TrainSystemUtilities.LOGGER.info(
                            "[Ae2Bridge] {} not loaded: AE2 integration features are no-op (optional dep)", MOD_ID);
                }
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn(
                        "[Ae2Bridge] self-check failed: {} (treating as unavailable)", t.toString());
                available = false;
            }
        }
        return available;
    }

    /**
     * AE2 が利用可能なら {@code action} を実行。 class link 失敗時は graceful return。
     */
    public static boolean safeRun(String label, Runnable action) {
        if (action == null) return false;
        if (!isAvailable()) return false;
        try {
            action.run();
            return true;
        } catch (LinkageError e) {
            TrainSystemUtilities.LOGGER.warn(
                    "[Ae2Bridge] '{}' skipped due to AE2 class link error: {}", label, e.toString());
            available = false;
            return false;
        }
    }
}
