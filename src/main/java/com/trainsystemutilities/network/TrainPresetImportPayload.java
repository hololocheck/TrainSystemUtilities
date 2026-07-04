package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetCodec;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.util.BoundedInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayInputStream;

/**
 * Client → Server: プリセットプレイス DL したバイナリをワールドの presets ディレクトリに保存。
 *
 * <p>{@link com.trainsystemutilities.client.gui.PresetPlaceDetailScreen} の「DL」ボタンから送られる。
 * 保存先: {@code <world>/trainsystemutilities/presets/<importing player UUID>/<name>.tsupreset}
 *
 * <p>挙動:
 * <ul>
 *   <li>preset.author / authorUUID は元投稿者のものを <strong>保持</strong> (盗作防止のため上書きしない)</li>
 *   <li>preset.importedFromPresetId に元投稿の Supabase preset.id を記録 (UI で投稿ボタンを抑止)</li>
 *   <li>ファイル自体は import したプレイヤーの UUID フォルダに保存 → MODE_MINE で見える</li>
 * </ul>
 *
 * <p>セキュリティ:
 * <ul>
 *   <li>サイズ上限 256KB (Supabase 側上限と一致)</li>
 *   <li>NBT パース失敗 (GZip ヘッダ不正等) は拒否</li>
 * </ul>
 */
public record TrainPresetImportPayload(String presetName, String sourcePresetId, byte[] data)
        implements CustomPacketPayload {

    /** Supabase 側上限と同じ 256KB。 */
    public static final int MAX_BYTES = 256 * 1024;

    public static final Type<TrainPresetImportPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "train_preset_import"));

    public static final StreamCodec<FriendlyByteBuf, TrainPresetImportPayload> STREAM_CODEC =
            StreamCodec.of(TrainPresetImportPayload::write, TrainPresetImportPayload::read);

    private static void write(FriendlyByteBuf buf, TrainPresetImportPayload p) {
        buf.writeUtf(p.presetName, 64);
        buf.writeUtf(p.sourcePresetId == null ? "" : p.sourcePresetId, 64);
        buf.writeVarInt(p.data.length);
        buf.writeBytes(p.data);
    }

    private static TrainPresetImportPayload read(FriendlyByteBuf buf) {
        // P0-4 #12: BoundedStreamCodec で全 read を bounded 化 (= 旧 readBytes() 直は
        // length 検証順序の race-window があった)。
        String name = BoundedStreamCodec.readBoundedUtf(buf, 64);
        String srcId = BoundedStreamCodec.readBoundedUtf(buf, 64);
        byte[] data = BoundedStreamCodec.readBoundedByteArray(buf, MAX_BYTES);
        return new TrainPresetImportPayload(name, srcId.isEmpty() ? null : srcId, data);
    }

    /** SECURITY: プレイヤーごとの簡易レート制限 (UUID → last import nano)。
     *  共有サーバーで連続 import によるディスク枯渇を防ぐ。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> LAST_IMPORT_NS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** プレイヤー 1 人あたり最低この間隔を空けないと import を受け付けない (10 秒)。 */
    private static final long IMPORT_INTERVAL_NS = 10L * 1_000_000_000L;

    public static void handle(TrainPresetImportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (payload.data == null || payload.data.length == 0) {
                TrainSystemUtilities.LOGGER.warn("[Import] empty data from {}", player.getName().getString());
                return;
            }
            if (payload.data.length > MAX_BYTES) {
                TrainSystemUtilities.LOGGER.warn("[Import] oversized {} bytes from {}",
                        payload.data.length, player.getName().getString());
                return;
            }
            // P0-4 #12: atomic check-then-set で rate limit。 旧コードは get → put が
            // 別呼出しで TOCTOU race。 ConcurrentHashMap.compute で atomic 化。
            long now = System.nanoTime();
            long[] prevHolder = new long[1];
            Long updated = LAST_IMPORT_NS.compute(player.getUUID(), (k, prev) -> {
                if (prev != null && now - prev < IMPORT_INTERVAL_NS) {
                    prevHolder[0] = prev;
                    return prev; // rate-limited: 値を変えない
                }
                return now;
            });
            if (updated != now) {
                TrainSystemUtilities.LOGGER.warn("[Import] rate-limited from {} ({}ms since last)",
                        player.getName().getString(), (now - prevHolder[0]) / 1_000_000);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("tsu.preset.delete_failed")
                                .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
                return;
            }
            try {
                // TSU-21: zip bomb 対策の BoundedInputStream は decompressed 側に被せる。
                // 旧コードは readCompressed(BoundedInputStream(ByteArrayInputStream)) で、 BoundedInputStream が
                // GZIP の外側 (= 圧縮データ側) に来てしまい compressed byte しか数えていなかった
                // (= 高圧縮率なら展開後が MAX_NBT_SIZE を超えても通ってしまう)。 readCompressed と等価に
                // GZIP→DataInputStream→NbtIo.read を手動展開し、 間に BoundedInputStream を挟んで
                // decompressed byte を MAX_NBT_SIZE で bound する。
                CompoundTag tag;
                try (var bais = new ByteArrayInputStream(payload.data);
                     var gz = new java.util.zip.GZIPInputStream(bais);
                     var bounded = new BoundedInputStream(gz, TrainPresetStorage.MAX_NBT_SIZE);
                     var dis = new java.io.DataInputStream(bounded)) {
                    tag = NbtIo.read(dis, NbtAccounter.create(TrainPresetStorage.MAX_NBT_SIZE));
                }
                TrainPreset preset = TrainPresetCodec.fromNbt(tag);
                // author / authorUUID は元投稿者のものを保持 (上書きしない = 盗作防止)
                preset.importedFromPresetId = payload.sourcePresetId;
                preset.name = sanitizeForFs(payload.presetName, preset.name);
                // ファイルは import したプレイヤーのフォルダに保存 (= MODE_MINE で表示される)
                var path = TrainPresetStorage.save(player.getServer(), preset, player.getUUID());
                TrainSystemUtilities.LOGGER.info("[Import] {} bytes → {} (orig author: {})",
                        payload.data.length, path, preset.author);
                var server = player.getServer();
                if (server != null) {
                    var entries = TrainPresetStorage.listAll(server);
                    java.util.List<TrainPresetListResponsePayload.Item> items = new java.util.ArrayList<>();
                    for (var e : entries) {
                        try {
                            TrainPreset header = TrainPresetStorage.loadHeader(e.file());
                            items.add(new TrainPresetListResponsePayload.Item(
                                    header.name == null || header.name.isEmpty() ? e.name() : header.name,
                                    header.author == null ? "" : header.author,
                                    header.sizeX, header.sizeY, header.sizeZ,
                                    header.blocks.size(),
                                    header.entities.size(),
                                    header.createdEpochMs,
                                    e.authorDir(),
                                    e.file().getFileName().toString(),
                                    header.importedFromPresetId != null
                                            && !header.importedFromPresetId.isEmpty()));
                        } catch (Exception ignored) { TrainSystemUtilities.LOGGER.debug("[Preset] list header load failed during import", ignored); }
                    }
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                            new TrainPresetListResponsePayload(items));
                }
            } catch (Exception e) {
                TrainSystemUtilities.LOGGER.warn("[Import] decode/save failed: {}", e.getMessage());
            }
        });
    }

    private static String sanitizeForFs(String reqName, String fallback) {
        String s = reqName == null || reqName.isBlank() ? fallback : reqName;
        if (s == null || s.isBlank()) return "imported";
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
