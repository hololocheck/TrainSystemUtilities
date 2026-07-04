package com.trainsystemutilities.item;

import com.simibubi.create.content.trains.track.ITrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 列車検知カード (Train Detection Card)。
 *
 * <p>SAS 統合機能: 線路ブロックを右クリックで検知ポイントとして登録。
 * 鉄道管理ブロックのアナウンス設定 GUI に挿入することで、
 * その線路位置を列車が通過した瞬間にアナウンス再生をトリガーできる。
 *
 * <p>右クリック挙動:
 * <ul>
 *   <li>線路ブロックを右クリック: 位置を保存</li>
 *   <li>Shift+右クリック (空中): 保存をクリア</li>
 * </ul>
 *
 * <p>NBT 形式 (CustomData):
 * <pre>
 * { Pos: long(packed), Dim: string(dimension key) }
 * </pre>
 */
public class TrainDetectionCardItem extends Item {

    private static final String KEY_POS = "Pos";
    private static final String KEY_DIM = "Dim";

    public TrainDetectionCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos pos = context.getClickedPos();

        if (player != null && player.isCrouching()) {
            clearCard(stack, player);
            return InteractionResult.SUCCESS;
        }

        if (!(level.getBlockState(pos).getBlock() instanceof ITrackBlock)) {
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "tsu.detection_card.not_track"), true);
            }
            return InteractionResult.PASS;
        }

        savePosition(stack, level, pos);
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "tsu.detection_card.bound", pos.getX(), pos.getY(), pos.getZ()), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player.isCrouching()) {
            clearCard(stack, player);
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos gp = getBoundPosition(stack);
        if (gp != null) {
            tooltip.add(Component.translatable("tsu.detection_card.tooltip_bound",
                    gp.pos().getX(), gp.pos().getY(), gp.pos().getZ()));
        } else {
            tooltip.add(Component.translatable("tsu.detection_card.tooltip_unbound").withStyle(net.minecraft.ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tsu.detection_card.tooltip_clear").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
    }

    private void clearCard(ItemStack stack, Player player) {
        stack.remove(DataComponents.CUSTOM_DATA);
        player.displayClientMessage(Component.translatable("tsu.detection_card.cleared"), true);
    }

    // === Public data API ===

    private static void savePosition(ItemStack stack, Level level, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(KEY_POS, pos.asLong());
        tag.putString(KEY_DIM, level.dimension().location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** @return bound (dimension, BlockPos) tuple, or null if not bound. */
    public static GlobalPos getBoundPosition(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        if (!(stack.getItem() instanceof TrainDetectionCardItem)) return null;
        if (!stack.has(DataComponents.CUSTOM_DATA)) return null;
        CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        if (!tag.contains(KEY_POS) || !tag.contains(KEY_DIM)) return null;
        try {
            BlockPos pos = BlockPos.of(tag.getLong(KEY_POS));
            ResourceLocation dimLoc = ResourceLocation.parse(tag.getString(KEY_DIM));
            ResourceKey<Level> dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimLoc);
            return GlobalPos.of(dim, pos);
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[DetectionCard] bound pos NBT parse failed", e);
            return null;
        }
    }

    public static boolean isBound(ItemStack stack) {
        return getBoundPosition(stack) != null;
    }
}
