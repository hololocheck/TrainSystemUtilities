package com.trainsystemutilities.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Permission check ヘルパー。 P0-8。
 *
 * <p>用途: TSU の command + payload handler 全域で permission check を統一する。
 * 「magic number 2」 (= Minecraft permission level) を直書きせず、 文脈を持つ
 * helper method 経由で呼ぶことで:
 * <ul>
 *   <li>permission policy 変更時の grep ポイントが明確</li>
 *   <li>{@link com.trainsystemutilities.docs.PERMISSION_MATRIX PERMISSION_MATRIX.md}
 *     との対応が一目瞭然</li>
 *   <li>PMD lint rule (P0-13) で {@code hasPermission(int)} 直叩きを reject する基盤</li>
 * </ul>
 *
 * <p>Minecraft permission level:
 * <ul>
 *   <li>0 = 全 player (default)</li>
 *   <li>1 = bypass spawn protection</li>
 *   <li>2 = OP (cheat command 可、 game rule 変更可)</li>
 *   <li>3 = OP + multiplayer management (kick/ban)</li>
 *   <li>4 = full server admin</li>
 * </ul>
 */
public final class PermissionHelper {

    /** TSU の「OP-only」 操作のデフォルト level。 game state を変更する command + payload。 */
    public static final int LEVEL_OP = 2;

    /** TSU の「owner only」 (= 自分の所有物のみ操作可) は player UUID 比較で判定。 */
    private PermissionHelper() {}

    /**
     * Player が OP かを check。 OP-only command / payload で必須。
     *
     * @return player が OP (= permission level >= 2) なら true
     */
    public static boolean isOp(ServerPlayer player) {
        if (player == null) return false;
        return player.hasPermissions(LEVEL_OP);
    }

    /**
     * 任意の player について OP check (非 ServerPlayer 経由)。 通常 ServerPlayer 経由が望ましい。
     */
    public static boolean isOp(Player player) {
        if (player == null) return false;
        return player.hasPermissions(LEVEL_OP);
    }

    /**
     * Player が owner か (= UUID 一致) を check。 owner-only 操作で使う。
     *
     * @param player 操作者
     * @param ownerUuid 所有者の UUID (= preset の作者、 station group の creator 等)
     * @return UUID 一致なら true
     */
    public static boolean isOwner(ServerPlayer player, java.util.UUID ownerUuid) {
        if (player == null || ownerUuid == null) return false;
        return ownerUuid.equals(player.getUUID());
    }

    /**
     * Owner or OP のいずれかか。 「自分のもの + OP は何でも」 pattern。
     */
    public static boolean isOwnerOrOp(ServerPlayer player, java.util.UUID ownerUuid) {
        return isOp(player) || isOwner(player, ownerUuid);
    }

    /**
     * Owner-managed object の管理権限。owner 未設定 (= legacy / public) は誰でも可、
     * owner 設定済みなら owner 本人 or OP のみ。station group の rename / delete 等で使う。
     */
    public static boolean canManageOwned(ServerPlayer player, java.util.UUID ownerUuid) {
        return ownerUuid == null || isOwnerOrOp(player, ownerUuid);
    }

    /** block GUI / payload の proximity gate 距離 (= vanilla container reach 相当の 8 ブロック)。 */
    public static final double USE_REACH_SQR = 64.0;

    /**
     * Player がその block に手が届く距離にいるか。 block GUI 由来の payload が remote spoof
     * (= GUI を開かずに任意座標の block へ packet 送信) されるのを防ぐ proximity gate。
     *
     * @return player が pos の中心から {@link #USE_REACH_SQR} 以内なら true
     */
    public static boolean isWithinReach(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return false;
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz <= USE_REACH_SQR;
    }
}
