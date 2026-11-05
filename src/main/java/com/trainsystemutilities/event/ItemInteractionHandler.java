package com.trainsystemutilities.event;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

@EventBusSubscriber(modid = TrainSystemUtilities.MOD_ID)
public class ItemInteractionHandler {

    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        ItemStack stack = event.getItemStack();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();

        if (stack.isEmpty()) return;

        if (stack.is(ModItems.RAILWAY_MANAGEMENT_BLOCK.get())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof com.simibubi.create.content.trains.station.StationBlockEntity stationBE) {
                if (!level.isClientSide()) {
                    try {
                        var station = stationBE.getStation();
                        if (station != null) {
                            CompoundTag tag = new CompoundTag();
                            tag.putString("LinkedStation", station.name);
                            tag.putLong("LinkedStationPos", pos.asLong());
                            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

                            player.displayClientMessage(Component.translatable(
                                    "message.trainsystemutilities.station_selected_fmt", station.name)
                                    .withStyle(net.minecraft.ChatFormatting.AQUA), true);
                        }
                    } catch (Exception e) {
                        TrainSystemUtilities.LOGGER.warn("Failed to read station: {}", e.getMessage());
                    }
                }
                event.cancelWithResult(ItemInteractionResult.SUCCESS);
            }
        }
    }
}
