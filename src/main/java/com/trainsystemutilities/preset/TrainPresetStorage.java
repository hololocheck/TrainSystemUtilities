package com.trainsystemutilities.preset;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 列車プリセットファイルの保存・読込・列挙。
 *
 * 保存先: <world>/trainsystemutilities/presets/&lt;authorUUID&gt;/&lt;name&gt;.tsupreset
 * フォーマット: GZip 圧縮 NBT (NbtIo.writeCompressed)
 *
 * マルチプレイ前提: ワールドディレクトリ配下に置くことで、サーバー上で全プレイヤーが
 * 共有できる。クライアントへのファイル配信は Phase 6 で実装。
 */
public final class TrainPresetStorage {

    /** ファイル拡張子 (NBT GZip 圧縮)。 */
    public static final String EXTENSION = ".tsupreset";

    /** ファイル名に使えない文字を除外するための regex。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._\\- ぁ-んァ-ヶ一-龯]");

    /** 1 プリセットあたりの最大サイズ (NBT デコード時の DoS 対策)。 */
    public static final long MAX_NBT_SIZE = 32L * 1024 * 1024; // 32 MiB

    private TrainPresetStorage() {}

    /** ワールドのプリセットルート: {world}/trainsystemutilities/presets */
    public static Path getRootDir(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("trainsystemutilities").resolve("presets");
    }

    /** 個別 author のディレクトリ。authorUUID == null の場合は "common" 共有領域。 */
    public static Path getAuthorDir(MinecraftServer server, UUID authorUUID) {
        Path root = getRootDir(server);
        return authorUUID == null ? root.resolve("common") : root.resolve(authorUUID.toString());
    }

    /** ファイル名を安全化。空文字や不正文字をアンダースコアに置換し、制御文字除去。 */
    public static String sanitizeName(String raw) {
        if (raw == null) return "preset";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "preset";
        String safe = SAFE_NAME.matcher(trimmed).replaceAll("_");
        if (safe.length() > 64) safe = safe.substring(0, 64);
        return safe.isEmpty() ? "preset" : safe;
    }

    public static Path resolveFile(MinecraftServer server, UUID authorUUID, String rawName) {
        return getAuthorDir(server, authorUUID).resolve(sanitizeName(rawName) + EXTENSION);
    }

    /**
     * SECURITY: client から受け取った {@code authorDir} + {@code fileName} を安全に
     * 既存ファイルの {@link Path} へ resolve する。
     *
     * <p>悪意あるクライアントから {@code fileName = "..\\..\\level.dat"} などの
     * path traversal が送られてもサーバ側の任意ファイルを参照させない。
     *
     * <p>許可条件:
     * <ul>
     *   <li>{@code authorDir} は UUID 文字列または {@code "common"}</li>
     *   <li>{@code fileName} は path separator / {@code ..} / NUL / 制御文字を含まない</li>
     *   <li>{@code fileName} は {@code .tsupreset} 拡張子で終わる</li>
     *   <li>{@code resolve + normalize} 後の絶対 path が {@link #getRootDir(MinecraftServer)} 内に収まる</li>
     * </ul>
     *
     * @return 上記すべて満たせば normalize 済み絶対 Path、それ以外 {@code null}
     */
    public static Path safeResolveExisting(MinecraftServer server, String authorDir, String fileName) {
        if (server == null || authorDir == null || fileName == null) return null;

        // authorDir は UUID 形式 or "common" のみ
        boolean isCommon = "common".equals(authorDir);
        if (!isCommon) {
            try { UUID.fromString(authorDir); } catch (Exception e) { return null; }
        }

        // fileName basic check
        if (fileName.isEmpty() || fileName.length() > 256
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || fileName.indexOf('\0') >= 0) {
            return null;
        }
        if (!fileName.endsWith(EXTENSION)) return null;
        for (int i = 0; i < fileName.length(); i++) {
            if (Character.isISOControl(fileName.charAt(i))) return null;
        }

        Path rootNorm;
        Path resolved;
        try {
            rootNorm = getRootDir(server).toAbsolutePath().normalize();
            resolved = rootNorm.resolve(authorDir).resolve(fileName).normalize();
        } catch (Exception e) { return null; }

        // containment check
        if (!resolved.startsWith(rootNorm)) return null;
        return resolved;
    }

    /** プリセットを保存。同名は上書き。 */
    public static Path save(MinecraftServer server, TrainPreset preset) throws IOException {
        return save(server, preset, preset.authorUUID);
    }

    /** プリセットを {@code ownerUUID} のディレクトリに保存。
     *  preset.author / authorUUID は変更しないため、import 等で
     *  「他人作のプリセットを自分のフォルダに保存する」用途に使う。 */
    public static Path save(MinecraftServer server, TrainPreset preset, UUID ownerUUID) throws IOException {
        Path file = resolveFile(server, ownerUUID, preset.name);
        Files.createDirectories(file.getParent());
        CompoundTag root = TrainPresetCodec.toNbt(preset);
        NbtIo.writeCompressed(root, file);
        TrainSystemUtilities.LOGGER.info("Train preset saved: {} ({} blocks, {} bytes)",
                file, preset.blocks.size(), Files.size(file));
        return file;
    }

    /** ファイルから 1 プリセット読込。 */
    public static TrainPreset load(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_SIZE));
        return TrainPresetCodec.fromNbt(root);
    }

    /** 全プリセットの (file, name, author) メタ一覧 (NBT は読まない、ファイル名から推定)。 */
    public static List<PresetEntry> listAll(MinecraftServer server) {
        Path root = getRootDir(server);
        List<PresetEntry> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> authors = Files.list(root)) {
            authors.filter(Files::isDirectory).forEach(authorDir -> {
                try (Stream<Path> files = Files.list(authorDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                            .forEach(file -> {
                                String fname = file.getFileName().toString();
                                String name = fname.substring(0, fname.length() - EXTENSION.length());
                                out.add(new PresetEntry(file, name, authorDir.getFileName().toString()));
                            });
                } catch (IOException ignored) { TrainSystemUtilities.LOGGER.debug("[Preset] author dir list failed", ignored); }
            });
        } catch (IOException ignored) { TrainSystemUtilities.LOGGER.debug("[Preset] root dir list failed", ignored); }
        out.sort(Comparator.comparing(e -> e.name));
        return out;
    }

    /** ヘッダーだけ読みたい場合用 (NBT 全体を読み込まないので軽量)。 */
    public static TrainPreset loadHeader(Path file) throws IOException {
        // 現状は全読みと同じ。将来的に header-only NBT chunk を分離する余地あり。
        return load(file);
    }

    public static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            TrainSystemUtilities.LOGGER.warn("Failed to delete preset {}: {}", file, e.getMessage());
            return false;
        }
    }

    public record PresetEntry(Path file, String name, String authorDir) {}
}
