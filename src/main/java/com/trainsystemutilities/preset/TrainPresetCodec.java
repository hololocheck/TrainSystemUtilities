package com.trainsystemutilities.preset;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/**
 * TrainPreset ↔ NBT (CompoundTag) 相互変換。
 * GZip 圧縮はストレージ層 (TrainPresetStorage) で行う。
 */
public final class TrainPresetCodec {

    private TrainPresetCodec() {}

    public static CompoundTag toNbt(TrainPreset preset) {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", preset.version);
        root.putString("Name", preset.name == null ? "" : preset.name);
        root.putString("Author", preset.author == null ? "" : preset.author);
        if (preset.authorUUID != null) root.putUUID("AuthorUUID", preset.authorUUID);
        root.putLong("Created", preset.createdEpochMs);
        if (preset.importedFromPresetId != null && !preset.importedFromPresetId.isEmpty()) {
            root.putString("ImportedFromId", preset.importedFromPresetId);
        }
        root.putIntArray("Size", new int[]{preset.sizeX, preset.sizeY, preset.sizeZ});
        root.putIntArray("Anchor", new int[]{preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ});

        ListTag palette = new ListTag();
        for (CompoundTag entry : preset.palette) palette.add(entry);
        root.put("Palette", palette);

        ListTag blocks = new ListTag();
        for (TrainPreset.Entry e : preset.blocks) {
            CompoundTag b = new CompoundTag();
            b.putIntArray("Pos", new int[]{e.relPos().getX(), e.relPos().getY(), e.relPos().getZ()});
            b.putInt("State", e.paletteIdx());
            if (e.beNbt() != null && !e.beNbt().isEmpty()) b.put("BE", e.beNbt());
            blocks.add(b);
        }
        root.put("Blocks", blocks);

        if (!preset.entities.isEmpty()) {
            ListTag ents = new ListTag();
            for (CompoundTag e : preset.entities) ents.add(e);
            root.put("Entities", ents);
        }
        return root;
    }

    public static TrainPreset fromNbt(CompoundTag root) {
        TrainPreset p = new TrainPreset();
        p.version = root.getInt("Version");
        p.name = root.getString("Name");
        p.author = root.getString("Author");
        if (root.hasUUID("AuthorUUID")) p.authorUUID = root.getUUID("AuthorUUID");
        p.createdEpochMs = root.getLong("Created");
        if (root.contains("ImportedFromId", Tag.TAG_STRING)) {
            p.importedFromPresetId = root.getString("ImportedFromId");
        }
        int[] size = root.getIntArray("Size");
        if (size.length >= 3) {
            p.sizeX = size[0]; p.sizeY = size[1]; p.sizeZ = size[2];
        }
        if (root.contains("Anchor")) {
            int[] anchor = root.getIntArray("Anchor");
            if (anchor.length >= 3) {
                p.anchorRelX = anchor[0];
                p.anchorRelY = anchor[1];
                p.anchorRelZ = anchor[2];
            }
        } else {
            // 旧フォーマット fallback: 底面中央
            p.anchorRelX = Math.max(0, p.sizeX / 2);
            p.anchorRelY = 0;
            p.anchorRelZ = Math.max(0, p.sizeZ / 2);
        }

        ListTag palette = root.getList("Palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < palette.size(); i++) p.palette.add(palette.getCompound(i));

        ListTag blocks = root.getList("Blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = blocks.getCompound(i);
            int[] pos = b.getIntArray("Pos");
            if (pos.length < 3) continue;
            BlockPos rel = new BlockPos(pos[0], pos[1], pos[2]);
            int idx = b.getInt("State");
            CompoundTag be = b.contains("BE", Tag.TAG_COMPOUND) ? b.getCompound("BE") : null;
            p.blocks.add(new TrainPreset.Entry(rel, idx, be));
        }

        if (root.contains("Entities", Tag.TAG_LIST)) {
            ListTag ents = root.getList("Entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < ents.size(); i++) p.entities.add(ents.getCompound(i));
        }
        return p;
    }

    /** UUID 不在チェック用 (NBT.hasUUID は private 互換のため直接書く)。 */
    @SuppressWarnings("unused")
    private static UUID safeUUID(CompoundTag tag, String key) {
        return tag.hasUUID(key) ? tag.getUUID(key) : null;
    }
}
