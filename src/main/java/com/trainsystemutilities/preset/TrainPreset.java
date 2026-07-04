package com.trainsystemutilities.preset;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 列車プリセットの in-memory 表現。
 *
 * 範囲選択 (Pos1, Pos2) を min corner 基準に正規化し、
 * BlockState を palette 化、各ブロックを (相対 Pos, palette index, BE NBT) で保持する。
 *
 * 設計方針:
 *  - vanilla structure block 形式に近い palette + entries 構造で、メモリ効率と
 *    NBT 圧縮効率を両立する
 *  - 将来的に carriage entity (CarriageContraptionEntity) のスナップショットも
 *    追加する余地を残すため、Entities リストを保持 (Phase 2 では空のまま)
 *  - 設置時は targetOrigin + 任意 rotation で再配置可能
 */
public class TrainPreset {

    /** スキーマバージョン。形式変更時に上げる。 */
    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String name = "";
    public String author = "";
    public UUID authorUUID = null;
    public long createdEpochMs = 0L;
    /** プリセットプレイス由来の場合に元投稿の Supabase preset.id (UUID 文字列)。
     *  null = 自作 (アップロード可)。非 null = ダウンロード品 (アップロード不可)。 */
    public String importedFromPresetId = null;

    /** 範囲のサイズ (排他的: 1ブロック幅なら 1)。 */
    public int sizeX = 0;
    public int sizeY = 0;
    public int sizeZ = 0;

    /** アンカー: 設置時にこの相対位置がプレイヤーのクリック位置と一致する。
     *  通常は「線路ブロックのうち最も低い位置」。線路がなければ底面中央。
     *  これにより列車を線路に正しく揃えて設置できる (min corner ベースだと埋まる)。 */
    public int anchorRelX = 0;
    public int anchorRelY = 0;
    public int anchorRelZ = 0;

    /** BlockState palette (NBT 形式 = name + Properties)。 */
    public final List<CompoundTag> palette = new ArrayList<>();

    /** 各ブロック配置 (相対 pos, palette index, 任意の BE NBT)。 */
    public final List<Entry> blocks = new ArrayList<>();

    /** Carriage entity スナップショット (Phase 2 では空、将来拡張用)。 */
    public final List<CompoundTag> entities = new ArrayList<>();

    public record Entry(BlockPos relPos, int paletteIdx, CompoundTag beNbt) {}

    /**
     * Palette に BlockState を登録し、index を返す。
     * 同一 BlockState は同一 index を返す (シリアライズ後の比較で重複検知)。
     */
    public int interPalette(BlockState state) {
        CompoundTag tag = net.minecraft.nbt.NbtUtils.writeBlockState(state);
        for (int i = 0; i < palette.size(); i++) {
            if (palette.get(i).equals(tag)) return i;
        }
        palette.add(tag);
        return palette.size() - 1;
    }

    public boolean isEmpty() { return blocks.isEmpty(); }
}
