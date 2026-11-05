package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.item.TrainPresetToolItem;
import com.trainsystemutilities.network.PresetsData;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The wiki's demo train preset tool (改善1, 2026-10-07: the user asked the preset pages to drag a preset into place, check
 * its materials and confirm, and to refill the glue from the tool's own screen): a tool with some glue in its tank, and
 * three presets of the player's own - small trains built here from vanilla blocks, so their pictures and their material
 * lists are the real renderer's and the real count's. Nothing here is saved, sent or the player's real data.
 */
public final class TrainPresetWikiDemo {

    private TrainPresetWikiDemo() {}

    /** The glue the demo tool holds when a page opens (of TrainPresetToolItem.GLUE_TANK_MAX). */
    public static final int GLUE_AT_START = 1500;

    /** The demo tool: a train preset tool with {@link #GLUE_AT_START} glue. */
    public static ItemStack tool() {
        ItemStack s = new ItemStack(ModItems.TRAIN_PRESET_TOOL.get());
        TrainPresetToolItem.setGlueTank(s, GLUE_AT_START);
        return s;
    }

    /** A demo preset: its list row (the player's own) and its blocks. */
    public record Demo(PresetsData.Item item, TrainPreset preset) {
        public String key() {
            return item.authorDir() + "/" + item.fileName();
        }
    }

    /** The three demo presets, the player's own (authorDir is the player's id, as the browser's own list asks). */
    public static List<Demo> presets() {
        Minecraft mc = Minecraft.getInstance();
        String dir = mc != null && mc.player != null ? mc.player.getUUID().toString() : "00000000-wiki";
        String author = mc != null && mc.player != null ? mc.player.getGameProfile().getName() : "wiki";
        List<Demo> out = new ArrayList<>();
        out.add(demo(dir, author, 1, "E235系 4両", 4, Blocks.LIME_CONCRETE.defaultBlockState(),
                Blocks.WHITE_CONCRETE.defaultBlockState()));
        out.add(demo(dir, author, 2, "通勤電車 3両", 3, Blocks.ORANGE_CONCRETE.defaultBlockState(),
                Blocks.WHITE_CONCRETE.defaultBlockState()));
        out.add(demo(dir, author, 3, "貨物列車 3両", 3, Blocks.BROWN_TERRACOTTA.defaultBlockState(),
                Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()));
        return out;
    }

    /**
     * The demo presets and two-car stand-ins after them, {@code count} in all: BelugaAOS's UI sweep's "mine-long"
     * (WikiLiveCapture's sweep-only states) - more tiles than the list shows, so its scrollbar thumb is up to be dragged.
     */
    public static List<Demo> presets(int count) {
        List<Demo> out = presets();
        PresetsData.Item first = out.get(0).item();
        for (int n = out.size() + 1; n <= count; n++) {
            out.add(demo(first.authorDir(), first.author(), n, "回送列車 " + n, 2,
                    Blocks.GRAY_CONCRETE.defaultBlockState(), Blocks.WHITE_CONCRETE.defaultBlockState()));
        }
        return out;
    }

    private static Demo demo(String dir, String author, int n, String name, int cars, BlockState stripe, BlockState body) {
        TrainPreset p = train(name, author, cars, stripe, body);
        PresetsData.Item item = new PresetsData.Item(name, author, p.sizeX, p.sizeY, p.sizeZ, p.blocks.size(), 0,
                1_791_331_200_000L + n * 86_400_000L, dir, "wiki-demo-" + n + ".nbt", false);
        return new Demo(item, p);
    }

    /** Cars of 3 x 4 x 9 blocks, one apart: bogies, a band of {@code stripe}, windows, a roof. */
    private static TrainPreset train(String name, String author, int cars, BlockState stripe, BlockState body) {
        TrainPreset p = new TrainPreset();
        p.name = name;
        p.author = author;
        int len = 9;
        p.sizeX = 3;
        p.sizeY = 4;
        p.sizeZ = cars * (len + 1) - 1;
        BlockState wheel = Blocks.BLACK_CONCRETE.defaultBlockState();
        BlockState floor = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState roof = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        for (int c = 0; c < cars; c++) {
            int z0 = c * (len + 1);
            for (int z = 0; z < len; z++) {
                boolean end = z == 0 || z == len - 1;
                if (z == 1 || z == 2 || z == len - 3 || z == len - 2) put(p, wheel, 1, 0, z0 + z);
                for (int x = 0; x < 3; x++) {
                    boolean wall = x != 1;
                    // y 1: the band on the walls, the floor inside, the ends in the body's colour
                    put(p, end ? body : wall ? stripe : floor, x, 1, z0 + z);
                    // y 2: windows along the walls, the body at the ends and between them
                    if (wall || end) put(p, !end && wall && z % 2 == 1 ? glass : body, x, 2, z0 + z);
                    // y 3: the roof
                    put(p, roof, x, 3, z0 + z);
                }
            }
        }
        return p;
    }

    private static void put(TrainPreset p, BlockState state, int x, int y, int z) {
        p.blocks.add(new TrainPreset.Entry(new BlockPos(x, y, z), p.interPalette(state), null));
    }
}
