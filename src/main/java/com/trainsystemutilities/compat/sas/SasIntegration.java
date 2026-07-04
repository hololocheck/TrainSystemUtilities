package com.trainsystemutilities.compat.sas;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Spatial Audio System (SAS) との統合のための soft-dep ラッパ。
 *
 * <p>すべてのメソッドは SAS が未導入の場合に no-op / false / null を返す。
 * これにより TSU 本体は SAS なしでも動作する。
 *
 * <p>SAS 側 API: {@code belugalab.sas.api.SasApi} (詳細は SAS リポジトリ参照)。
 */
public final class SasIntegration {

    private SasIntegration() {}

    public static final String SAS_MOD_ID = "spatialaudiosystem";

    private static volatile Boolean cachedLoaded = null;

    /** SAS が現在ロードされているか (キャッシュ済み)。 */
    public static boolean isLoaded() {
        Boolean c = cachedLoaded;
        if (c != null) return c;
        try {
            cachedLoaded = ModList.get() != null && ModList.get().isLoaded(SAS_MOD_ID);
        } catch (Throwable t) {
            cachedLoaded = false;
        }
        return cachedLoaded;
    }

    /** {@code stack} が SAS の記憶媒体アイテムか。SAS 未導入なら false。 */
    public static boolean isRecordingMedium(ItemStack stack) {
        if (!isLoaded()) return false;
        try { return belugalab.sas.api.SasApi.isRecordingMedium(stack); }
        catch (Throwable t) { return false; }
    }

    /** {@code stack} が SAS の範囲指定ボードアイテムか。SAS 未導入なら false。 */
    public static boolean isRangeBoard(ItemStack stack) {
        if (!isLoaded()) return false;
        try { return belugalab.sas.api.SasApi.isRangeBoard(stack); }
        catch (Throwable t) { return false; }
    }

    /** 記憶媒体に音声データが格納されているか。 */
    public static boolean hasAudio(ItemStack recordingMedium) {
        if (!isLoaded()) return false;
        try { return belugalab.sas.api.SasApi.hasAudio(recordingMedium); }
        catch (Throwable t) { return false; }
    }

    /**
     * 記憶媒体の音声を server から全 client にブロードキャストして再生する。
     * 範囲指定ボードがあれば spatial attenuation を適用。
     *
     * @return true なら再生開始成功
     */
    public static boolean playAudio(ServerLevel level, BlockPos pos,
                                     ItemStack recordingMedium, ItemStack rangeBoard) {
        return playAudio(level, pos, recordingMedium, rangeBoard, true);
    }

    public static boolean playAudio(ServerLevel level, BlockPos pos,
                                     ItemStack recordingMedium, ItemStack rangeBoard,
                                     boolean attenuationMode) {
        if (!isLoaded()) return false;
        try {
            return belugalab.sas.api.SasApi.playAudio(level, pos, recordingMedium, rangeBoard, attenuationMode);
        } catch (Throwable t) {
            TrainSystemUtilities.LOGGER.warn("[SasIntegration] playAudio failed: {}", t.toString());
            return false;
        }
    }

    /** 指定位置の playback を停止。 */
    public static void stopAudio(ServerLevel level, BlockPos pos) {
        if (!isLoaded()) return;
        try { belugalab.sas.api.SasApi.stopAudio(level, pos); }
        catch (Throwable ignored) {}
    }
}
