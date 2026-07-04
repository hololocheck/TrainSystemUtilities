package com.trainsystemutilities.compat;

import com.trainsystemutilities.TrainSystemUtilities;
import net.neoforged.fml.ModList;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manta 連携の facade。 P0-3。
 *
 * <p>用途: TSU は MCSS の SPI (WikiEmbedRegistry, StylesheetRegistry, ChromeRenderer 等) を
 * 直接呼び出していたが、 mod 環境によっては MCSS が absent / older / newer の
 * いずれかになる。 直叩きでは {@link NoClassDefFoundError} で TSU 全体が起動不能。
 *
 * <p>本 bridge を経由することで:
 * <ul>
 *   <li>{@link #isAvailable()} で {@link ModList#isLoaded} 経由の安全 check</li>
 *   <li>{@link #safeRun} で {@link NoClassDefFoundError} / {@link LinkageError} の
 *     graceful catch (= mod 不在で TSU 機能 1 つだけ無効化、 他に影響なし)</li>
 *   <li>初回呼出時に boot-time self-check ログ (= 期待バージョン互換性 sanity)</li>
 * </ul>
 *
 * <p>使い方:
 * <pre>{@code
 * McssBridge.safeRun("registerWikiEmbeds", () -> {
 *     TSUWikiEmbeds.registerAll();
 * });
 * }</pre>
 */
public final class McssBridge {

    public static final String MOD_ID = "manta";

    private static final AtomicBoolean selfCheckDone = new AtomicBoolean(false);
    private static volatile boolean available = false;

    private McssBridge() {}

    /**
     * MCSS が ModList 経由でロード済か。 初回呼出時に self-check + log を行う。
     */
    public static boolean isAvailable() {
        if (selfCheckDone.compareAndSet(false, true)) {
            try {
                available = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
                if (available) {
                    TrainSystemUtilities.LOGGER.info("[McssBridge] {} detected: SPI calls enabled", MOD_ID);
                } else {
                    TrainSystemUtilities.LOGGER.warn(
                            "[McssBridge] {} not loaded: TSU will skip MCSS-dependent features (wiki/CSS/embeds)",
                            MOD_ID);
                }
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.warn(
                        "[McssBridge] self-check failed: {} (treating as unavailable)", t.toString());
                available = false;
            }
        }
        return available;
    }

    /**
     * MCSS が利用可能なら {@code action} を実行。 {@link NoClassDefFoundError} /
     * {@link LinkageError} で MCSS class link 失敗が出た場合は log のみで graceful return。
     *
     * @param label diagnostic log 用の短いラベル (= "registerWikiEmbeds" 等)
     * @param action MCSS SPI を呼び出す Runnable
     * @return action が実行された場合 true、 skip された場合 false
     */
    public static boolean safeRun(String label, Runnable action) {
        if (action == null) return false;
        if (!isAvailable()) {
            return false;
        }
        try {
            action.run();
            return true;
        } catch (LinkageError e) {
            TrainSystemUtilities.LOGGER.warn(
                    "[McssBridge] '{}' skipped due to MCSS class link error: {}", label, e.toString());
            // future call の self-check fail を促す
            available = false;
            return false;
        } catch (Throwable t) {
            // domain 例外は caller が自前で扱う (= bridge は class link 失敗のみ吸収)
            throw t;
        }
    }
}
