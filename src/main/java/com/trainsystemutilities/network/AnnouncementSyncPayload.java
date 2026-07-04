package com.trainsystemutilities.network;

import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.announce.AnnouncementConfig;
import com.trainsystemutilities.client.gui.RailwayAnnouncementClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: アナウンス設定 (条件メタデータ) を client に同期。
 * Menu open 直後 + 設定変更後に server から送信される。
 */
public record AnnouncementSyncPayload(BlockPos pos, CompoundTag configNbt) implements CustomPacketPayload {

    public static final Type<AnnouncementSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "announcement_sync"));

    public static final StreamCodec<FriendlyByteBuf, AnnouncementSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeBlockPos(p.pos); buf.writeNbt(p.configNbt); },
                    buf -> new AnnouncementSyncPayload(buf.readBlockPos(), buf.readNbt())
            );

    public static void handle(AnnouncementSyncPayload payload, IPayloadContext context) {
        Minecraft.getInstance().execute(() -> {
            AnnouncementConfig cfg = AnnouncementConfig.load(payload.configNbt);
            RailwayAnnouncementClientState.setConfig(payload.pos, cfg);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
