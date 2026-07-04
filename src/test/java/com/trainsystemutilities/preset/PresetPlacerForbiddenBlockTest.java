package com.trainsystemutilities.preset;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TSU-01: preset 配置の RCE / 構造ロード系ブロック拒否ガードを検証する。
 *
 * <p>{@code findForbiddenBlock} は palette の "Name" だけを見るため Minecraft bootstrap 不要。
 * barrier / bedrock 等の griefing 寄りブロックは創作自由度のため許可される、という設計判断も
 * ここで固定する (= clean 扱いになること)。
 */
class PresetPlacerForbiddenBlockTest {

    private static CompoundTag paletteEntry(String blockId) {
        CompoundTag t = new CompoundTag();
        t.putString("Name", blockId);
        return t;
    }

    @Test
    void cleanPreset_returnsNull() {
        TrainPreset preset = new TrainPreset();
        preset.palette.add(paletteEntry("minecraft:oak_planks"));
        preset.palette.add(paletteEntry("create:track"));
        preset.palette.add(paletteEntry("minecraft:barrier"));  // barrier は RCE ではないので許可
        preset.palette.add(paletteEntry("minecraft:bedrock"));  // bedrock も許可
        assertNull(PresetPlacer.findForbiddenBlock(preset));
    }

    @Test
    void commandBlocks_areForbidden() {
        for (String id : new String[]{
                "minecraft:command_block",
                "minecraft:chain_command_block",
                "minecraft:repeating_command_block"}) {
            TrainPreset preset = new TrainPreset();
            preset.palette.add(paletteEntry("minecraft:oak_planks"));
            preset.palette.add(paletteEntry(id));
            assertEquals(id, PresetPlacer.findForbiddenBlock(preset));
        }
    }

    @Test
    void structureAndJigsaw_areForbidden() {
        TrainPreset s = new TrainPreset();
        s.palette.add(paletteEntry("minecraft:structure_block"));
        assertEquals("minecraft:structure_block", PresetPlacer.findForbiddenBlock(s));

        TrainPreset j = new TrainPreset();
        j.palette.add(paletteEntry("minecraft:jigsaw"));
        assertEquals("minecraft:jigsaw", PresetPlacer.findForbiddenBlock(j));
    }

    @Test
    void nullPreset_returnsNull() {
        assertNull(PresetPlacer.findForbiddenBlock(null));
    }
}
