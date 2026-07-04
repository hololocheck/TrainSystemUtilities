package com.trainsystemutilities.client.structure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.MemoryCardItem;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * メモリーカード (= screen_door_group) を持っている時、 group メンバーを
 * LevelRenderer.renderLineBox で 緑 outline highlight。
 * 駅範囲指定ツールと同じパターンで確実に描画される。
 */
@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID, value = Dist.CLIENT)
public final class ScreenDoorGroupHighlightRenderer {

    private ScreenDoorGroupHighlightRenderer() {}

    private static final float R = 0.40f, G = 1.00f, B = 0.40f, A = 0.85f;

    /** Phase 21: 鉄道管理ブロック GUI が「現在表示すべき group メンバー」 を通知する経路。 */
    private static volatile long[] guiHighlightMembers = null;

    /** Phase 21: Screen 側で popup 開閉時に call。 closes 時は null で clear。 */
    public static void setGuiHighlight(long[] members) {
        guiHighlightMembers = members;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        LocalPlayer player = mc.player;

        long[] members = resolveMembersToHighlight(player);
        if (members == null || members.length == 0) return;

        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());

        for (long packed : members) {
            BlockPos p = BlockPos.of(packed);
            AABB box = new AABB(
                    p.getX() - camPos.x, p.getY() - camPos.y, p.getZ() - camPos.z,
                    p.getX() + 1 - camPos.x, p.getY() + 1 - camPos.y, p.getZ() + 1 - camPos.z);
            LevelRenderer.renderLineBox(pose, consumer, box, R, G, B, A);
        }
        buffer.endBatch(RenderType.lines());
    }

    /** 手持ちカードを優先し、 なければ GUI 通知の members を返す。 両方ない時は null。 */
    private static long[] resolveMembersToHighlight(LocalPlayer player) {
        ItemStack held = findHeldCard(player);
        if (!held.isEmpty() && held.has(DataComponents.CUSTOM_DATA)) {
            CompoundTag tag = held.get(DataComponents.CUSTOM_DATA).copyTag();
            if (MemoryCardItem.TYPE_SCREEN_DOOR_GROUP.equals(tag.getString("Type"))) {
                long[] m = readMembers(tag);
                if (m.length > 0) return m;
            }
        }
        return guiHighlightMembers;
    }

    /** NBT sync で ListTag<LongTag> → LongArrayTag に自動圧縮されるため両方対応。 */
    private static long[] readMembers(CompoundTag tag) {
        Tag raw = tag.get(MemoryCardItem.TAG_MEMBERS);
        if (raw instanceof LongArrayTag lat) {
            return lat.getAsLongArray();
        }
        if (raw instanceof ListTag lt) {
            long[] arr = new long[lt.size()];
            for (int i = 0; i < lt.size(); i++) {
                if (lt.get(i) instanceof LongTag longTag) arr[i] = longTag.getAsLong();
            }
            return arr;
        }
        return new long[0];
    }

    /** メイン手 or オフハンドにメモリーカードがあれば返す。 */
    private static ItemStack findHeldCard(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.MEMORY_CARD.get())) return main;
        ItemStack off = player.getOffhandItem();
        if (off.is(ModItems.MEMORY_CARD.get())) return off;
        return ItemStack.EMPTY;
    }
}
