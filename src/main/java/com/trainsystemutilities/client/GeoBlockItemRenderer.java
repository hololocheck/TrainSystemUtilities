package com.trainsystemutilities.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton {@link BlockEntityWithoutLevelRenderer} that renders any
 * {@link com.trainsystemutilities.item.GeoBlockItem} by delegating to its
 * block's {@link BlockEntityRenderer}.
 *
 * <p>For each unique block, a transient {@link BlockEntity} is created once
 * and cached. On every item render call, that BE is passed to the
 * corresponding {@code BlockEntityRenderer} (Geckolib or vanilla BER) so the
 * inventory icon matches the visual a player sees when the block is placed.
 *
 * <p>Cached BEs hold no per-instance state (no per-tick logic runs on them);
 * they exist only to satisfy the renderer's API.
 */
public final class GeoBlockItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static GeoBlockItemRenderer INSTANCE;

    public static GeoBlockItemRenderer get() {
        if (INSTANCE == null) {
            Minecraft mc = Minecraft.getInstance();
            INSTANCE = new GeoBlockItemRenderer(
                    mc.getBlockEntityRenderDispatcher(),
                    mc.getEntityModels());
        }
        return INSTANCE;
    }

    private final Map<Block, BlockEntity> beCache = new HashMap<>();

    private GeoBlockItemRenderer(
            net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher,
            net.minecraft.client.model.geom.EntityModelSet modelSet) {
        super(dispatcher, modelSet);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void renderByItem(ItemStack stack, ItemDisplayContext context,
                              PoseStack pose, MultiBufferSource buffer,
                              int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof BlockItem bi)) return;
        Block block = bi.getBlock();
        BlockEntity be = beCache.computeIfAbsent(block, b -> {
            if (b instanceof EntityBlock eb) {
                return eb.newBlockEntity(BlockPos.ZERO, b.defaultBlockState());
            }
            return null;
        });
        if (be == null) return;
        if (be.getLevel() == null && Minecraft.getInstance().level != null) {
            be.setLevel(Minecraft.getInstance().level);
        }

        BlockEntityRenderer renderer =
                Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null) return;

        pose.pushPose();
        try {
            renderer.render(be, 0f, pose, buffer, packedLight, packedOverlay);
        } catch (Throwable t) {
            // swallow: ItemStack 描画失敗で他アイテムが影響受けないように
        } finally {
            pose.popPose();
        }
    }
}
