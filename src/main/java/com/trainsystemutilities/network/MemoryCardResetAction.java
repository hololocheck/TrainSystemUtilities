package com.trainsystemutilities.network;

import com.trainsystemutilities.item.MemoryCardItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * 手に持っているメモリーカードの初期化 (= 記録内容の全消去) — the tools host's {@code memory-card-reset} action
 * ({@link ToolsData}; MemoryCardResetPayload until MANTA_7_CONCEPT C4). hand: 0 = MAIN_HAND, 1 = OFF_HAND
 * (tools-state.json の宣言)。
 *
 * <p>シフト右クリックはこの action を送らない。 client で確認ダイアログ
 * ({@code MemoryCardResetScreen}) を開き、 ユーザーが「初期化する」を押したときだけ送る。
 * カードには管理用コンピューターの設定一式が入るようになったため (1.0.10)、
 * 誤操作 1 回で復元不能になるのを防ぐ。
 *
 * <p>server 側は「送信者が実際にその手にメモリーカードを持っているか」だけを検証する。
 * 対象は送信者自身のインベントリなので座標 gate は不要。
 */
public final class MemoryCardResetAction {

    private MemoryCardResetAction() {}

    public static void apply(ServerPlayer sp, int hand) {
        InteractionHand h = hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = sp.getItemInHand(h);
        if (!(stack.getItem() instanceof MemoryCardItem)) return;
        MemoryCardItem.resetCard(stack, sp);
    }
}
