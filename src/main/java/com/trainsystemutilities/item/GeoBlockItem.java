package com.trainsystemutilities.item;

import com.trainsystemutilities.client.GeoBlockItemRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * {@link BlockItem} variant that renders the corresponding block's
 * {@code BlockEntityRenderer} (Geckolib or vanilla BER) in item context
 * (inventory slot, hotbar, ground drop, hand).
 *
 * <p>Use this for blocks whose visual is provided by a Geckolib model attached
 * to their BlockEntity — without this wrapper, the inventory would show a
 * plain {@code cube_all} placeholder while the world shows the rich
 * BlockBench model.
 */
public class GeoBlockItem extends BlockItem {

    public GeoBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return GeoBlockItemRenderer.get();
            }
        });
    }
}
