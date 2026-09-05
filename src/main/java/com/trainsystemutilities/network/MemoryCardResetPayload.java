package com.trainsystemutilities.network;

import com.manta.api.util.codec.BoundedStreamCodec;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.item.MemoryCardItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: 手に持っているメモリーカードの初期化 (= 記録内容の全消去)。
 *
 * <p>シフト右クリックはこの payload を送らない。 client で確認ダイアログ
 * ({@code MemoryCardResetScreen}) を開き、 ユーザーが「初期化する」を押したときだけ送る。
 * カードには管理用コンピューターの設定一式が入るようになったため (1.0.10)、
 * 誤操作 1 回で復元不能になるのを防ぐ。
 *
 * <p>server 側は「送信者が実際にその手にメモリーカードを持っているか」だけを検証する。
 * 対象は送信者自身のインベントリなので座標 gate は不要。
 */
public record MemoryCardResetPayload(int hand) implements CustomPacketPayload {

    public static final Type<MemoryCardResetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    TrainSystemUtilities.MOD_ID, "memory_card_reset"));

    public static final StreamCodec<FriendlyByteBuf, MemoryCardResetPayload> STREAM_CODEC =
            StreamCodec.of(MemoryCardResetPayload::write, MemoryCardResetPayload::read);

    private static void write(FriendlyByteBuf buf, MemoryCardResetPayload p) {
        buf.writeVarInt(p.hand);
    }

    private static MemoryCardResetPayload read(FriendlyByteBuf buf) {
        return new MemoryCardResetPayload(BoundedStreamCodec.readBoundedVarInt(buf, 1)); // 0..1
    }

    public static void handle(MemoryCardResetPayload p, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            InteractionHand hand = p.hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack stack = sp.getItemInHand(hand);
            if (!(stack.getItem() instanceof MemoryCardItem)) return;
            MemoryCardItem.resetCard(stack, sp);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
