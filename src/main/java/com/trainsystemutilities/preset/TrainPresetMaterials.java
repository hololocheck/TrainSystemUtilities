package com.trainsystemutilities.preset;

import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TrainPresetMaterials {

    public record MaterialEntry(Item item, int count) {
        public ItemStack stack() {
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        }
    }

    private TrainPresetMaterials() {
    }

    public static LinkedHashMap<Item, Integer> collectBaseRequirements(Level level, TrainPreset preset) {
        LinkedHashMap<Item, Integer> requirements = new LinkedHashMap<>();
        if (level == null || preset == null) {
            return requirements;
        }

        HolderGetter<Block> blockLookup = level.holderLookup(Registries.BLOCK);
        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        boolean anchorIsTrack = isAnchorTrack(preset);

        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.relPos().getY() < preset.anchorRelY) {
                continue;
            }
            if (anchorIsTrack && entry.relPos().equals(anchorRel)) {
                continue;
            }

            Item item = resolveRequiredItem(blockLookup, preset, entry);
            if (item == null || item == Items.AIR) {
                continue;
            }
            requirements.merge(item, 1, Integer::sum);
        }

        return requirements;
    }

    public static LinkedHashMap<Item, Integer> collectPlacementRequirements(Level level, BlockPos origin,
                                                                            TrainPreset preset, int rotY) {
        LinkedHashMap<Item, Integer> requirements = new LinkedHashMap<>();
        if (level == null || origin == null || preset == null) {
            return requirements;
        }

        HolderGetter<Block> blockLookup = level.holderLookup(Registries.BLOCK);
        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        BlockPos anchorRot = rotateRel(anchorRel, preset.sizeX, preset.sizeZ, rotY);
        boolean anchorIsTrack = isAnchorTrack(preset);

        for (TrainPreset.Entry entry : preset.blocks) {
            if (entry.relPos().getY() < preset.anchorRelY) {
                continue;
            }
            if (anchorIsTrack && entry.relPos().equals(anchorRel)) {
                continue;
            }
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) {
                continue;
            }

            BlockState targetState = decodeBlockState(blockLookup, preset.palette.get(entry.paletteIdx()));
            if (targetState == null) {
                continue;
            }

            BlockPos relRot = rotateRel(entry.relPos(), preset.sizeX, preset.sizeZ, rotY);
            BlockPos worldPos = origin.offset(
                    relRot.getX() - anchorRot.getX(),
                    relRot.getY() - anchorRot.getY(),
                    relRot.getZ() - anchorRot.getZ());

            BlockState existing = level.getBlockState(worldPos);
            if (isTrack(existing) && !isTrack(targetState)) {
                continue;
            }
            if (existing.equals(targetState)) {
                continue;
            }

            Item item = resolveRequiredItem(targetState, entry.beNbt());
            if (item == null || item == Items.AIR) {
                continue;
            }
            requirements.merge(item, 1, Integer::sum);
        }

        return requirements;
    }

    public static List<MaterialEntry> toEntries(Map<Item, Integer> requirements) {
        List<MaterialEntry> entries = new ArrayList<>();
        if (requirements == null) {
            return entries;
        }

        for (var entry : requirements.entrySet()) {
            if (entry.getKey() == null || entry.getKey() == Items.AIR || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            entries.add(new MaterialEntry(entry.getKey(), entry.getValue()));
        }

        entries.sort(Comparator
                .comparingInt(MaterialEntry::count).reversed()
                .thenComparing(e -> BuiltInRegistries.ITEM.getKey(e.item()).toString()));
        return entries;
    }

    public static String encode(Map<Item, Integer> requirements) {
        return encodeEntries(toEntries(requirements));
    }

    public static String encodeEntries(List<MaterialEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (MaterialEntry entry : entries) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(entry.item());
            if (id == null || entry.count() <= 0) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(id).append('=').append(entry.count());
        }
        return builder.toString();
    }

    public static List<MaterialEntry> decode(String encoded) {
        List<MaterialEntry> entries = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return entries;
        }

        String[] parts = encoded.split(";");
        for (String part : parts) {
            int sep = part.indexOf('=');
            if (sep <= 0 || sep >= part.length() - 1) {
                continue;
            }

            ResourceLocation id = ResourceLocation.tryParse(part.substring(0, sep));
            if (id == null) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                continue;
            }

            try {
                int count = Integer.parseInt(part.substring(sep + 1));
                if (count > 0) {
                    entries.add(new MaterialEntry(item, count));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return entries;
    }

    public static String formatCompactCount(int count) {
        if (count < 1000) {
            return Integer.toString(count);
        }

        if (count < 10_000) {
            double k = count / 1000.0;
            String text = String.format(Locale.ROOT, "%.1fK", k);
            return text.endsWith(".0K") ? text.replace(".0K", "K") : text;
        }

        return (count / 1000) + "K";
    }

    private static Item resolveRequiredItem(HolderGetter<Block> blockLookup, TrainPreset preset, TrainPreset.Entry entry) {
        if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) {
            return null;
        }
        BlockState state = decodeBlockState(blockLookup, preset.palette.get(entry.paletteIdx()));
        return resolveRequiredItem(state, entry.beNbt());
    }

    private static Item resolveRequiredItem(BlockState state, CompoundTag beNbt) {
        if (state == null || state.isAir()) {
            return null;
        }

        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        // Create の bogey ブロックは Item 化されていないので
        // 構成材である railway_casing を必要素材としてマップする。
        if (blockId != null && blockId.getPath().contains("bogey")) {
            ResourceLocation casingId = ResourceLocation.fromNamespaceAndPath("create", "railway_casing");
            Item casing = BuiltInRegistries.ITEM.get(casingId);
            if (casing != Items.AIR) return casing;
        }

        Item item = state.getBlock().asItem();
        if (item == Items.AIR && blockId != null && BuiltInRegistries.ITEM.containsKey(blockId)) {
            item = BuiltInRegistries.ITEM.get(blockId);
        }

        if (item != Items.AIR) {
            return item;
        }

        if (beNbt != null && !beNbt.isEmpty()) {
            String id = beNbt.getString("id");
            if (!id.isEmpty()) {
                ResourceLocation itemId = ResourceLocation.tryParse(id);
                if (itemId != null) {
                    Item beItem = BuiltInRegistries.ITEM.get(itemId);
                    if (beItem != Items.AIR) {
                        return beItem;
                    }
                }
            }
        }

        return null;
    }

    private static BlockState decodeBlockState(HolderGetter<Block> blockLookup, CompoundTag blockStateTag) {
        try {
            return NbtUtils.readBlockState(blockLookup, blockStateTag);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isAnchorTrack(TrainPreset preset) {
        if (preset == null) {
            return false;
        }
        BlockPos anchorRel = new BlockPos(preset.anchorRelX, preset.anchorRelY, preset.anchorRelZ);
        for (TrainPreset.Entry entry : preset.blocks) {
            if (!anchorRel.equals(entry.relPos())) {
                continue;
            }
            if (entry.paletteIdx() < 0 || entry.paletteIdx() >= preset.palette.size()) {
                return false;
            }
            return isTrack(preset.palette.get(entry.paletteIdx()));
        }
        return false;
    }

    // C2: PresetPlacer と逐語重複していた isTrack / rotateRel を本クラスに集約 (package-visible static)。
    static boolean isTrack(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (state.is(BlockTags.RAILS)) {
            return true;
        }
        if (state.getBlock() instanceof ITrackBlock) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.contains("track") || path.contains("rail");
    }

    static boolean isTrack(CompoundTag blockStateTag) {
        if (blockStateTag == null || blockStateTag.isEmpty()) {
            return false;
        }
        String name = blockStateTag.getString("Name");
        if (name == null || name.isEmpty()) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) {
            return false;
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null) {
            return false;
        }
        return isTrack(block.defaultBlockState());
    }

    static BlockPos rotateRel(BlockPos rel, int sizeX, int sizeZ, int rotY) {
        int r = ((rotY % 4) + 4) % 4;
        int x = rel.getX();
        int y = rel.getY();
        int z = rel.getZ();
        return switch (r) {
            case 1 -> new BlockPos(sizeZ - 1 - z, y, x);
            case 2 -> new BlockPos(sizeX - 1 - x, y, sizeZ - 1 - z);
            case 3 -> new BlockPos(z, y, sizeX - 1 - x);
            default -> rel;
        };
    }
}
