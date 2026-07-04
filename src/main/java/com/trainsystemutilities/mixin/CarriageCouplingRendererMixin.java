package com.trainsystemutilities.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.Create;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.entity.*;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 連結直後の接合部ワイヤーを描画する。
 * クライアントマップやエンティティ状態は一切操作しない。
 * クライアントエンティティのボギーアンカー（正規レンダラーで更新済み）を使用。
 */
@Mixin(value = CarriageCouplingRenderer.class, remap = false)
public class CarriageCouplingRendererMixin {

    /** DEBUG: 連結ワイヤー描画 inject を一時無効化。検証完了 → false。 */
    private static final boolean DEBUG_DISABLE_COUPLING_RENDER = false;

    @Inject(method = "renderAll", at = @At("TAIL"), require = 0)
    private static void trainsystemutilities$renderJunctionWire(PoseStack ms, MultiBufferSource buffer, Vec3 camera, CallbackInfo ci) {
        if (DEBUG_DISABLE_COUPLING_RENDER) return;
        try {
            var serverTrains = Create.RAILWAYS.trains;
            var clientTrains = CreateClient.RAILWAYS.trains;
            Level level = Minecraft.getInstance().level;
            if (level == null) return;

            float partialTicks = AnimationTickHolder.getPartialTicks();

            for (var entry : serverTrains.entrySet()) {
                if (clientTrains.containsKey(entry.getKey())) continue;

                Train train = entry.getValue();
                List<Carriage> carriages = train.carriages;
                if (carriages.size() < 2) continue;

                for (int i = 0; i < carriages.size() - 1; i++) {
                    Carriage sc1 = carriages.get(i);
                    Carriage sc2 = carriages.get(i + 1);

                    // 旧列車が既に描画するペアはスキップ（接合部のみ描画）
                    if (isPairInAnyClientTrain(clientTrains.values(), sc1, sc2)) continue;

                    // サーバーエンティティIDでクライアントエンティティを検索
                    CarriageContraptionEntity se1 = sc1.anyAvailableEntity();
                    CarriageContraptionEntity se2 = sc2.anyAvailableEntity();
                    if (se1 == null || se2 == null) continue;

                    Entity cr1 = level.getEntity(se1.getId());
                    Entity cr2 = level.getEntity(se2.getId());
                    if (!(cr1 instanceof CarriageContraptionEntity ce1)) continue;
                    if (!(cr2 instanceof CarriageContraptionEntity ce2)) continue;

                    // クライアント側キャリッジのボギーアンカーを使用
                    Carriage cc1 = ce1.getCarriage();
                    Carriage cc2 = ce2.getCarriage();
                    if (cc1 == null || cc2 == null) continue;

                    CarriageBogey bogey1 = cc1.trailingBogey();
                    CarriageBogey bogey2 = cc2.leadingBogey();
                    Vec3 anchor = bogey1.couplingAnchors.getSecond();
                    Vec3 anchor2 = bogey2.couplingAnchors.getFirst();
                    if (anchor == null || anchor2 == null) continue;
                    if (!anchor.closerThan(camera, 64)) continue;

                    // ワイヤー描画（Createと同じ方式）
                    renderCouplingWire(ms, buffer, camera, train, i,
                            ce1, ce2, anchor, anchor2, partialTicks);
                }
            }
        } catch (Exception e) {
            // ignore rendering errors
        }
    }

    private static boolean isPairInAnyClientTrain(
            java.util.Collection<Train> clientTrainList, Carriage c1, Carriage c2) {
        for (Train ct : clientTrainList) {
            List<Carriage> cc = ct.carriages;
            for (int k = 0; k < cc.size() - 1; k++) {
                if (cc.get(k) == c1 && cc.get(k + 1) == c2) return true;
            }
        }
        return false;
    }

    private static void renderCouplingWire(PoseStack ms, MultiBufferSource buffer, Vec3 camera,
            Train train, int pairIndex,
            CarriageContraptionEntity ce1, CarriageContraptionEntity ce2,
            Vec3 anchor, Vec3 anchor2, float partialTicks) {

        VertexConsumer vb = buffer.getBuffer(RenderType.solid());
        BlockState air = Blocks.AIR.defaultBlockState();

        int light1 = CarriageCouplingRenderer.getPackedLightCoords(ce1, partialTicks);
        int light2 = CarriageCouplingRenderer.getPackedLightCoords(ce2, partialTicks);

        double diffX = anchor2.x - anchor.x;
        double diffY = anchor2.y - anchor.y;
        double diffZ = anchor2.z - anchor.z;
        float yRot = AngleHelper.deg(Mth.atan2(diffZ, diffX)) + 90;
        float xRot = AngleHelper.deg(Math.atan2(diffY, Math.sqrt(diffX * diffX + diffZ * diffZ)));

        ms.pushPose();

        // 第1ヘッド
        ms.pushPose();
        ms.translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z);
        CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_HEAD, air)
                .rotateYDegrees(-yRot).rotateXDegrees(xRot)
                .light(light1).renderInto(ms, vb);

        // ケーブル
        float margin = 3 / 16f;
        double dist = train.carriageSpacing.get(pairIndex) - 2 * margin;
        int segs = Math.max((int) Math.round(dist * 4), 1);
        double stretch = ((anchor2.distanceTo(anchor) - 2 * margin) * 4) / segs;
        for (int j = 0; j < segs; j++) {
            CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_CABLE, air)
                    .rotateYDegrees(-yRot + 180).rotateXDegrees(-xRot)
                    .translate(0, 0, margin + 2 / 16f)
                    .scale(1, 1, (float) stretch)
                    .translate(0, 0, j / 4f)
                    .light(light1).renderInto(ms, vb);
        }
        ms.popPose();

        // 第2ヘッド
        ms.pushPose();
        ms.translate(anchor2.x - camera.x, anchor2.y - camera.y, anchor2.z - camera.z);
        CachedBuffers.partial(AllPartialModels.TRAIN_COUPLING_HEAD, air)
                .rotateYDegrees(-yRot + 180).rotateXDegrees(-xRot)
                .light(light2).renderInto(ms, vb);
        ms.popPose();

        ms.popPose();
    }
}
