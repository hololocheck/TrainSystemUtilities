package com.trainsystemutilities.network;

import belugalab.mcss3.util.codec.BoundedStreamCodec;
import belugalab.mcss3.util.safe.SafeLog;
import belugalab.mcss3.util.safe.SafeName;
import com.trainsystemutilities.TrainSystemUtilities;
import com.trainsystemutilities.station.StationGroup;
import com.trainsystemutilities.station.StationGroupSavedData;
import com.trainsystemutilities.station.StationGroupScanner;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.trainsystemutilities.item.StationRangeToolItem;
import com.trainsystemutilities.registry.ModItems;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: 駅グループ作成リクエスト (駅名のみ; 路線記号は管理用コンピューター側)。
 *
 * <p>サーバ側で範囲内の Create 駅ブロックを検出して {@link StationGroup} を生成、
 * {@link StationGroupSavedData} に保存。
 */
public record StationGroupCreatePayload(String name, BlockPos pos1, BlockPos pos2,
                                         BlockPos creatorPos, int numberingDir)
        implements CustomPacketPayload {

    /** 互換コンストラクタ: creatorPos / numberingDir 不在のクライアントから受信した時用。 */
    public StationGroupCreatePayload(String name, BlockPos pos1, BlockPos pos2) {
        this(name, pos1, pos2, BlockPos.ZERO, 0);
    }
    public StationGroupCreatePayload(String name, BlockPos pos1, BlockPos pos2, BlockPos creatorPos) {
        this(name, pos1, pos2, creatorPos, 0);
    }

    public static final Type<StationGroupCreatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "station_group_create"));

    public static final StreamCodec<FriendlyByteBuf, StationGroupCreatePayload> STREAM_CODEC =
            StreamCodec.of(StationGroupCreatePayload::write, StationGroupCreatePayload::read);

    /** SECURITY (TSU-03): scan 体積上限 (= 64^3 相当)。これを超える範囲指定は DoS とみなし拒否。 */
    private static final long MAX_SCAN_VOLUME = 262_144L;
    /** SECURITY (TSU-03): create の per-player cooldown (2 秒)。連続 create による scan spam を抑止。 */
    private static final long CREATE_COOLDOWN_NANOS = 2_000_000_000L;
    private static final java.util.Map<java.util.UUID, Long> lastCreateNanos =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void write(FriendlyByteBuf buf, StationGroupCreatePayload p) {
        buf.writeUtf(p.name, 64);
        buf.writeBlockPos(p.pos1);
        buf.writeBlockPos(p.pos2);
        buf.writeBlockPos(p.creatorPos);
        buf.writeVarInt(p.numberingDir);
    }

    private static StationGroupCreatePayload read(FriendlyByteBuf buf) {
        return new StationGroupCreatePayload(
                // P0-4 #7: station group name (generic display name; 128 bytes per SafeName.DEFAULT_MAX_BYTES)
                BoundedStreamCodec.readBoundedUtf(buf, 128),
                buf.readBlockPos(), buf.readBlockPos(),
                buf.readBlockPos(),
                // P0-4 #7 hotfix3: write は writeVarInt なので read も readBoundedVarInt 必須
                // (= 旧 readBoundedInt は 4 byte 固定読みで encoding mismatch、 buffer 整合性破壊)。
                BoundedStreamCodec.readBoundedVarInt(buf, 2));
    }

    public static void handle(StationGroupCreatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // SECURITY (TSU-03): per-player rate limit (連続 create による scan spam / DoS 防止)
            long nowNs = System.nanoTime();
            Long lastNs = lastCreateNanos.put(player.getUUID(), nowNs);
            if (lastNs != null && nowNs - lastNs < CREATE_COOLDOWN_NANOS) return;
            if (lastCreateNanos.size() > 256) {
                lastCreateNanos.values().removeIf(v -> nowNs - v > CREATE_COOLDOWN_NANOS * 8);
            }
            String name = payload.name == null ? "" : payload.name.trim();
            if (name.isEmpty() || name.length() > 64) {
                player.displayClientMessage(Component.translatable("tsu.station_tool.name_invalid")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            // P0-4 #7: display name persisted to SavedData NBT and Create StationBlockEntity name — reject control/BiDi/oversized
            try {
                SafeName.validate(name);
            } catch (IllegalArgumentException ex) {
                TrainSystemUtilities.LOGGER.warn("[StationGroup] reject create — name failed SafeName validation: {}",
                        SafeLog.sanitize(ex.getMessage()));
                player.displayClientMessage(Component.translatable("tsu.station_tool.name_invalid")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            ServerLevel level = player.serverLevel();

            // SECURITY (TSU-NET-001): 選択座標は client payload ではなく、プレイヤーが手に持つ
            // 範囲ツールの server 側 state (RANGE_POS1/POS2) を権威ソースとして使う。
            // これらは useOn/use が server 側で ray-cast (最大 64 ブロック) して設定したもので、
            // client が任意の遠隔座標を注入できない。payload.pos1/pos2 は互換のため残すが信用しない。
            ItemStack tool = findHeldRangeTool(player);
            if (tool == null) {
                player.displayClientMessage(Component.literal(
                        "駅範囲指定ツールを手に持って作成してください")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            BlockPos toolPos1 = StationRangeToolItem.getPos1(tool);
            BlockPos toolPos2 = StationRangeToolItem.getPos2(tool);
            if (toolPos1 == null || toolPos2 == null) {
                player.displayClientMessage(Component.literal(
                        "範囲が未確定です (ツールで pos1/pos2 を指定してください)")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            BlockPos minP = new BlockPos(
                    Math.min(toolPos1.getX(), toolPos2.getX()),
                    Math.min(toolPos1.getY(), toolPos2.getY()),
                    Math.min(toolPos1.getZ(), toolPos2.getZ()));
            BlockPos maxP = new BlockPos(
                    Math.max(toolPos1.getX(), toolPos2.getX()),
                    Math.max(toolPos1.getY(), toolPos2.getY()),
                    Math.max(toolPos1.getZ(), toolPos2.getZ()));

            // SECURITY (TSU-03): scan 体積に上限。巨大 cuboid (最大数千万ブロック) を
            // server tick 上で scan させる DoS を防ぐ。
            long volume = (long) (maxP.getX() - minP.getX() + 1)
                    * (maxP.getY() - minP.getY() + 1)
                    * (maxP.getZ() - minP.getZ() + 1);
            if (volume > MAX_SCAN_VOLUME) {
                TrainSystemUtilities.LOGGER.warn(
                        "[security] reject station group create — scan volume {} > cap {} by {}",
                        volume, MAX_SCAN_VOLUME, player.getName().getString());
                player.displayClientMessage(Component.literal(
                        "選択範囲が大きすぎます (上限 " + MAX_SCAN_VOLUME + " ブロック)")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }

            // SECURITY (TSU-NET-001): scan は現在ロード済みの chunk に限定する。
            // scanner の getBlockEntity は未ロード chunk を forceload するため、遠隔/未ロード領域を
            // scan させない (= 近傍保証 + forceload DoS 防止)。全 chunk がロード済みでなければ拒否。
            if (!allChunksLoaded(level, minP, maxP)) {
                TrainSystemUtilities.LOGGER.warn(
                        "[security] reject station group create — range not fully loaded by {}",
                        player.getName().getString());
                player.displayClientMessage(Component.literal(
                        "選択範囲がロードされていません (近くで作成してください)")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }

            // 重複範囲チェック: 同一 dim で全く同じ min/max を持つグループがあれば拒否
            String dim = level.dimension().location().toString();
            var data = StationGroupSavedData.get(player.server);
            StationGroup dup = data.findExactRange(dim, minP, maxP);
            if (dup != null) {
                player.displayClientMessage(
                        Component.translatable("tsu.station_tool.duplicate_range_fmt", dup.name())
                                .withStyle(ChatFormatting.RED), false);
                TrainSystemUtilities.LOGGER.info(
                        "[StationGroup] reject create — same range already exists as '{}' (id={})",
                        SafeLog.sanitize(dup.name()), dup.id());
                return;
            }

            List<StationGroupScanner.DetectedStation> detected =
                    StationGroupScanner.scan(level, minP, maxP);
            TrainSystemUtilities.LOGGER.info("[StationGroup] scan range ({},{},{})~({},{},{}) detected {} Create stations",
                    minP.getX(), minP.getY(), minP.getZ(), maxP.getX(), maxP.getY(), maxP.getZ(),
                    detected.size());
            if (detected.isEmpty()) {
                player.displayClientMessage(Component.translatable("tsu.station_tool.no_stations_in_range")
                        .withStyle(ChatFormatting.RED), true);
                return;
            }
            // 番線番号: 「creatorPos (= グループ作成時のプレイヤー位置) に最も近い駅 = 1 番線」。
            // creatorPos → AABB 中心 ベクトルを「外向き」と見なし、その逆方向 (内側 = creator 側)
            // が 1 番線。これにより東西南北どの方向に駅を作っても「内側から 1-4」が統一される。
            //
            // SECURITY (TSU-NET-001): creatorPos は client payload を信用せず、常に server 側の
            // 実プレイヤー位置を使う (番線連番は creator 近傍を 1 番線とするため位置詐称を防ぐ)。
            BlockPos creatorPos = player.blockPosition();

            // 1) 駅並びの主軸 (long axis): AABB の長辺 = X か Z か
            int dxRange = maxP.getX() - minP.getX();
            int dzRange = maxP.getZ() - minP.getZ();
            net.minecraft.core.Direction primaryAxis = (dxRange >= dzRange)
                    ? net.minecraft.core.Direction.EAST   // X 軸並び
                    : net.minecraft.core.Direction.SOUTH; // Z 軸並び
            double cx = (minP.getX() + maxP.getX()) / 2.0;
            double cz = (minP.getZ() + maxP.getZ()) / 2.0;

            // 2) ascending 判定:
            //   numberingDir == LEFT (1)  → 主軸 min 端を 1 番線 (= ascending=true) で固定
            //   numberingDir == RIGHT (2) → 主軸 max 端を 1 番線 (= ascending=false) で固定
            //   numberingDir == AUTO (0)  → creator pos が AABB の min 端 / max 端のどちら側に近いかで判定
            // SECURITY (TSU-NET-001): numberingDir も client payload でなく server 側ツール state を使う。
            int numberingDir = StationRangeToolItem.getNumberingDir(tool);
            boolean ascendingByPrimary;
            String reason;
            if (numberingDir == 1) {
                ascendingByPrimary = true;
                reason = "manual-left";
            } else if (numberingDir == 2) {
                ascendingByPrimary = false;
                reason = "manual-right";
            } else {
                // AUTO: 主軸方向に min 端中心 / max 端中心 の XZ 座標を計算 (副軸方向は AABB 中心を使用):
                double minEndX, minEndZ, maxEndX, maxEndZ;
                if (primaryAxis == net.minecraft.core.Direction.EAST) {
                    minEndX = minP.getX(); maxEndX = maxP.getX();
                    minEndZ = cz;          maxEndZ = cz;
                } else {
                    minEndX = cx;          maxEndX = cx;
                    minEndZ = minP.getZ(); maxEndZ = maxP.getZ();
                }
                double dxMin = creatorPos.getX() - minEndX;
                double dzMin = creatorPos.getZ() - minEndZ;
                double dxMax = creatorPos.getX() - maxEndX;
                double dzMax = creatorPos.getZ() - maxEndZ;
                double distToMinSq = dxMin * dxMin + dzMin * dzMin;
                double distToMaxSq = dxMax * dxMax + dzMax * dzMax;
                ascendingByPrimary = distToMinSq <= distToMaxSq;
                reason = "auto-nearest-end (minDist=" + (long)distToMinSq
                        + " maxDist=" + (long)distToMaxSq + ")";
            }

            // 副キー: 主軸と直交する軸 (横並び駅もサポート)
            net.minecraft.core.Direction secondaryAxis = primaryAxis.getClockWise();

            detected.sort((a, b) -> {
                int va = a.pos().getX() * primaryAxis.getStepX() + a.pos().getZ() * primaryAxis.getStepZ();
                int vb = b.pos().getX() * primaryAxis.getStepX() + b.pos().getZ() * primaryAxis.getStepZ();
                int c = Integer.compare(va, vb);
                if (!ascendingByPrimary) c = -c;
                if (c != 0) return c;
                int sa = a.pos().getX() * secondaryAxis.getStepX() + a.pos().getZ() * secondaryAxis.getStepZ();
                int sb = b.pos().getX() * secondaryAxis.getStepX() + b.pos().getZ() * secondaryAxis.getStepZ();
                c = Integer.compare(sa, sb);
                if (c != 0) return c;
                return Integer.compare(a.pos().getY(), b.pos().getY());
            });
            TrainSystemUtilities.LOGGER.info(
                    "[StationGroup] platform sort: creatorPos=({},{},{}) AABB center=({},{}) primaryAxis={} ascending={} reason={} ({} stations)",
                    creatorPos.getX(), creatorPos.getY(), creatorPos.getZ(),
                    cx, cz, primaryAxis, ascendingByPrimary, reason, detected.size());
            List<UUID> ids = new ArrayList<>(detected.size());
            List<BlockPos> positions = new ArrayList<>(detected.size());
            for (var d : detected) {
                ids.add(d.id());
                positions.add(d.pos());
                TrainSystemUtilities.LOGGER.info("[StationGroup]   station id={} pos=({},{},{})",
                        d.id(), d.pos().getX(), d.pos().getY(), d.pos().getZ());
            }

            // 作成者のクライアント言語に基づいて命名テンプレを決定
            String langCode = "ja_jp";
            try {
                langCode = player.clientInformation().language();
            } catch (Throwable ignored) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[StationGroup] client language read failed", ignored); }
            String namingTemplate = StationGroup.pickTemplate(langCode);
            TrainSystemUtilities.LOGGER.info(
                    "[StationGroup] creator language='{}' → naming template='{}'",
                    SafeLog.sanitize(langCode), SafeLog.sanitize(namingTemplate));

            StationGroup group = new StationGroup(
                    UUID.randomUUID(), name, dim,
                    minP, maxP, ids, positions, namingTemplate, player.getUUID());

            data.put(group);

            // 駅構造解析 (旧 NavGraph) — 残存コード、軽量なので継続実行
            try {
                var analysis = com.trainsystemutilities.station.routing.navgraph
                        .StationStructureAnalyzer.analyze(level, group);
                if (!analysis.skipped() && analysis.graph() != null) {
                    com.trainsystemutilities.station.routing.navgraph.NavGraphCache
                            .put(analysis.graph());
                }
            } catch (Throwable t) {
                TrainSystemUtilities.LOGGER.error("[Analyzer] failed for group={}: {}",
                        group.id(), t.toString(), t);
            }

            // Phase A: NavField をバックグラウンド構築 (= 主要ナビ用)
            // 完了時に action bar / chat で player に通知される。
            // ナビ実行時には既に構築済みなので待ち時間ゼロ。
            com.trainsystemutilities.station.routing.navfield.NavFieldBuildScheduler
                    .scheduleAll(level, group, player);
            player.displayClientMessage(
                    Component.literal(String.format(
                            "駅 '%s' 作成: 経路解析を開始しました (バックグラウンド)", name))
                            .withStyle(ChatFormatting.AQUA), false);

            // ツール状態リセット
            for (var hand : net.minecraft.world.InteractionHand.values()) {
                var stack = player.getItemInHand(hand);
                if (stack.is(com.trainsystemutilities.registry.ModItems.STATION_RANGE_TOOL.get())) {
                    stack.remove(com.trainsystemutilities.registry.ModDataComponents.RANGE_POS1.get());
                    stack.remove(com.trainsystemutilities.registry.ModDataComponents.RANGE_POS2.get());
                    stack.remove(com.trainsystemutilities.registry.ModDataComponents.RANGE_EDIT_MODE.get());
                }
            }

            // 既存の Create 駅ブロックの名前を即座に更新 (placeholder pass)
            // Mixin は updateName 呼出時にしか発火しないため、保存直後に手動でも更新する。
            for (int i = 0; i < positions.size(); i++) {
                BlockPos p = positions.get(i);
                int platformNum = i + 1;
                String formatted = group.formatStationName(platformNum);
                var be = level.getBlockEntity(p);
                if (be instanceof com.simibubi.create.content.trains.station.StationBlockEntity sbe) {
                    boolean changed = sbe.updateName(formatted);
                    TrainSystemUtilities.LOGGER.info(
                            "[StationGroup]   rename pos=({},{},{}) → '{}' result={}",
                            p.getX(), p.getY(), p.getZ(), SafeLog.sanitize(formatted), changed);
                }
            }

            player.displayClientMessage(
                    Component.translatable("tsu.station_tool.created_fmt", name, ids.size())
                            .withStyle(ChatFormatting.GREEN), true);
            TrainSystemUtilities.LOGGER.info("[StationGroup] {} created '{}' with {} stations by {}",
                    group.id(), SafeLog.sanitize(name), ids.size(), player.getName().getString());

            // 全プレイヤーに最新リストをブロードキャスト
            StationGroupRenamePayload.broadcastListToAll(player.server);
        });
    }

    /** SECURITY (TSU-NET-001): プレイヤーが手に持つ駅範囲指定ツールを返す (main → offhand)。無ければ null。 */
    private static ItemStack findHeldRangeTool(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.STATION_RANGE_TOOL.get())) return stack;
        }
        return null;
    }

    /**
     * SECURITY (TSU-NET-001): AABB がまたぐ全 chunk が現在ロード済みか。
     * 未ロードが 1 つでもあれば false (= scanner の getBlockEntity による forceload を防ぐ)。
     */
    private static boolean allChunksLoaded(ServerLevel level, BlockPos minP, BlockPos maxP) {
        int minCx = minP.getX() >> 4, maxCx = maxP.getX() >> 4;
        int minCz = minP.getZ() >> 4, maxCz = maxP.getZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    /** 検出した駅 BE 群の中で多数決の FACING。同数なら最初に出た方向 (NORTH 優先)。 */
    private static net.minecraft.core.Direction pickDominantFacing(
            java.util.List<StationGroupScanner.DetectedStation> detected) {
        java.util.EnumMap<net.minecraft.core.Direction, Integer> count =
                new java.util.EnumMap<>(net.minecraft.core.Direction.class);
        for (var d : detected) {
            count.merge(d.facing(), 1, Integer::sum);
        }
        net.minecraft.core.Direction best = net.minecraft.core.Direction.NORTH;
        int bestCount = -1;
        for (var dir : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST}) {
            int c = count.getOrDefault(dir, 0);
            if (c > bestCount) { bestCount = c; best = dir; }
        }
        return best;
    }

    /** pos の rightDir 方向の値 (= 駅における「左右位置」)。 */
    private static int rightOffset(BlockPos pos, net.minecraft.core.Direction rightDir) {
        return pos.getX() * rightDir.getStepX() + pos.getZ() * rightDir.getStepZ();
    }

    /** pos の forwardDir 方向の値 (= 駅における「前後位置」/ ホーム上のどこか)。 */
    private static int forwardOffset(BlockPos pos, net.minecraft.core.Direction forwardDir) {
        return pos.getX() * forwardDir.getStepX() + pos.getZ() * forwardDir.getStepZ();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
