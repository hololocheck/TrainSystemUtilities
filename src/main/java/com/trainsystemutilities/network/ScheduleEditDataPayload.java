package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: {@link RequestScheduleEditPayload} への応答。編集対象列車の
 * schedule を Create native NBT で運ぶ。client は開いている
 * {@code ManagementComputerScreenV2} に渡して editor を開く。
 */
public record ScheduleEditDataPayload(UUID trainId, boolean hasData, CompoundTag scheduleNbt)
        implements CustomPacketPayload {

    public static final Type<ScheduleEditDataPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "schedule_edit_data"));

    public static final StreamCodec<FriendlyByteBuf, ScheduleEditDataPayload> STREAM_CODEC =
            StreamCodec.of(ScheduleEditDataPayload::write, ScheduleEditDataPayload::read);

    private static void write(FriendlyByteBuf buf, ScheduleEditDataPayload p) {
        buf.writeUUID(p.trainId == null ? new UUID(0, 0) : p.trainId);
        buf.writeBoolean(p.hasData);
        buf.writeNbt(p.scheduleNbt == null ? new CompoundTag() : p.scheduleNbt);
    }

    private static ScheduleEditDataPayload read(FriendlyByteBuf buf) {
        UUID trainId = buf.readUUID();
        boolean hasData = buf.readBoolean();
        CompoundTag nbt = buf.readNbt();
        if (nbt == null) nbt = new CompoundTag();
        return new ScheduleEditDataPayload(trainId, hasData, nbt);
    }

    public static void handle(ScheduleEditDataPayload payload, IPayloadContext context) {
        // client-side のみ。main thread に再投入して、開いている管理用コンピューター画面へ渡す。
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen
                    instanceof com.trainsystemutilities.client.gui.ManagementComputerScreenV2 s) {
                s.onScheduleEditData(payload.trainId, payload.hasData, payload.scheduleNbt);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
