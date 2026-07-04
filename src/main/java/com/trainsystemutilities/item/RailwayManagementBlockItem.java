package com.trainsystemutilities.item;

import com.trainsystemutilities.blockentity.RailwayManagementBlockEntity;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * 鉄道管理ブロックのアイテム。
 * 駅ブロック右クリック → 駅情報をアイテムに保存（イベントハンドラで処理）
 * 設置時 → アイテムのNBTから駅情報を読み取ってリンク
 */
public class RailwayManagementBlockItem extends BlockItem {

    public RailwayManagementBlockItem(Properties properties) {
        super(ModBlocks.RAILWAY_MANAGEMENT_BLOCK.get(), properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        // Station detection is handled by ItemInteractionHandler event
        // This only runs for non-station blocks (normal placement)
        InteractionResult result = super.useOn(context);

        if (result.consumesAction() && !context.getLevel().isClientSide()) {
            // After placement, link to stored station
            BlockPos placedPos = context.getClickedPos().relative(context.getClickedFace());
            Level level = context.getLevel();
            BlockEntity placedBE = level.getBlockEntity(placedPos);

            if (placedBE instanceof RailwayManagementBlockEntity rmbe) {
                var stack = context.getItemInHand();
                if (stack.has(DataComponents.CUSTOM_DATA)) {
                    CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
                    if (tag.contains("LinkedStation")) {
                        String stationName = tag.getString("LinkedStation");
                        BlockPos stationPos = BlockPos.of(tag.getLong("LinkedStationPos"));
                        rmbe.linkToStation(stationName, stationPos);

                        if (context.getPlayer() != null) {
                            context.getPlayer().displayClientMessage(Component.translatable(
                                    "tsu.rm_item.linked", stationName), true);
                        }
                    }
                }
            }
        }

        return result;
    }

    @Override
    public void appendHoverText(net.minecraft.world.item.ItemStack stack,
                                 TooltipContext context, List<Component> tooltip,
                                 net.minecraft.world.item.TooltipFlag flag) {
        if (stack.has(DataComponents.CUSTOM_DATA)) {
            CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            if (tag.contains("LinkedStation")) {
                tooltip.add(Component.translatable("tsu.rm_item.tooltip_linked", tag.getString("LinkedStation")));
            }
        } else {
            tooltip.add(Component.translatable("tsu.rm_item.tooltip_hint"));
        }
    }
}
