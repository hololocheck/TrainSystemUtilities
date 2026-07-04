package com.trainsystemutilities.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.trainsystemutilities.TrainSystemUtilities;

/**
 * モニター連携カード (MonitorLinkCardItem) を持つときに、登録モニターをハイライト表示する。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public class MonitorHighlightRenderer {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();

        // Monitor Link Card: highlight registered monitors
        ItemStack linkCard = null;
        if (mainHand.is(ModItems.MONITOR_LINK_CARD.get())) linkCard = mainHand;
        else if (offHand.is(ModItems.MONITOR_LINK_CARD.get())) linkCard = offHand;

        if (linkCard != null && linkCard.has(DataComponents.CUSTOM_DATA)) {
            var positions = com.trainsystemutilities.item.MonitorLinkCardItem.getRegisteredPositions(linkCard);
            if (!positions.isEmpty()) {
                Vec3 cam = event.getCamera().getPosition();
                PoseStack poseStack = event.getPoseStack();
                poseStack.pushPose();
                poseStack.translate(-cam.x, -cam.y, -cam.z);
                VertexConsumer consumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());

                for (BlockPos mPos : positions) {
                    AABB box = new AABB(mPos).inflate(0.005);
                    LevelRenderer.renderLineBox(poseStack, consumer, box, 0.3f, 0.8f, 1.0f, 0.8f); // Cyan
                }

                mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
                poseStack.popPose();
            }
            return;
        }

        // Phase 5d: 管理用コンピューター item の右クリック登録/highlight ロジックは廃止。
        // モニター連携は MonitorLinkCardItem 経由のみ。
    }
}
