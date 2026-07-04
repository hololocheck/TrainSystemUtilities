package com.trainsystemutilities.item;

import com.trainsystemutilities.compat.ae2.TrainPresetAe2Integration;
import com.trainsystemutilities.network.TrainPresetPlaceResultPayload;
import com.trainsystemutilities.preset.PresetPlacer;
import com.trainsystemutilities.preset.TrainPreset;
import com.trainsystemutilities.preset.TrainPresetMaterials;
import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.preset.TrainPresetStorage;
import com.trainsystemutilities.registry.ModDataComponents;
import com.trainsystemutilities.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 列車プリセットツール。
 */
public class TrainPresetToolItem extends Item {

    public static final int TOOL_MODE_GUI = 0;
    public static final int TOOL_MODE_SELECTION = 1;
    public static final int TOOL_MODE_PLACE = 2;

    public static final int SUB_ORIGIN = 0;
    /** 統合「回転」モード。alt+shift+ホイールで先頭 bogey 軸を起点に 90° 補正。
     *  自動方向検出 (PLACE_AUTO_ROT_Y) があるため、X/Y/Z 個別の rotation モードは廃止。 */
    public static final int SUB_ROT = 1;
    public static final int SUB_PLACE = 2;
    public static final int SUB_COUNT = 3;
    /** 旧コード互換: 既存参照を壊さないため SUB_ROT_Y を SUB_ROT のエイリアスとして残す。
     *  X/Z は意味を持たず -1 (どの sub にも一致しない)。 */
    public static final int SUB_ROT_Y = SUB_ROT;
    public static final int SUB_ROT_X = -1;
    public static final int SUB_ROT_Z = -1;

    public static final int SOURCE_CHEST = 0;
    public static final int SOURCE_ME = 1;
    /** ME と chest の両方から取り出し。各アイテムごとに ME 優先で消費し、不足分のみ chest から消費。 */
    public static final int SOURCE_BOTH = 2;
    public static final int SOURCE_COUNT = 3;

    public static String sourceLabel(int mode) {
        return switch (mode) {
            case SOURCE_ME -> "ME";
            case SOURCE_BOTH -> "ME / Chest";
            default -> "Chest";
        };
    }

    private static final double MAX_RANGE = 64.0;

    public TrainPresetToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        Level level = context.getLevel();
        ItemStack stack = context.getItemInHand();
        int mode = getToolMode(stack);

        if (mode == TOOL_MODE_GUI) {
            if (level.isClientSide()) {
                openBrowseScreenClient();
            }
            return InteractionResult.SUCCESS;
        }

        if (mode == TOOL_MODE_PLACE) {
            if (!level.isClientSide()) {
                int sub = getPlaceSubMode(stack);
                BlockPos clicked = context.getClickedPos();
                switch (sub) {
                    case SUB_ORIGIN -> {
                        setPlaceOrigin(stack, clicked.immutable());
                        Direction markerDirection = PresetPlacer.resolveMarkerDirection(level, clicked, player.getLookAngle());
                        setPlaceMarkerDirection(stack, markerDirection);
                        setPlaceAutoRotY(stack, resolveAutoRotY(level, clicked, stack, player));
                        setPlaceRotY(stack, 0);
                        player.displayClientMessage(Component.translatable(
                                        "tsu.tool.origin_set_fmt",
                                        clicked.getX(), clicked.getY(), clicked.getZ())
                                .withStyle(ChatFormatting.AQUA), true);
                    }
                    case SUB_PLACE -> {
                        doPlaceAt(level, clicked, getPlaceOrigin(stack), stack, player);
                    }
                    default -> player.displayClientMessage(
                            Component.translatable("tsu.tool.rotate_mode")
                                    .withStyle(ChatFormatting.GRAY),
                            true);
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) clearByEditMode(stack, player);
            return InteractionResult.SUCCESS;
        }

        if (hasRange(stack) && getEditMode(stack) == 0) {
            if (level.isClientSide()) {
                openSaveScreenClient();
            }
            return InteractionResult.SUCCESS;
        }

        if (!level.isClientSide()) {
            setPosition(stack, player, context.getClickedPos());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int mode = getToolMode(stack);

        if (mode == TOOL_MODE_GUI) {
            if (level.isClientSide()) openBrowseScreenClient();
            return InteractionResultHolder.success(stack);
        }

        if (mode == TOOL_MODE_PLACE) {
            if (!level.isClientSide()) {
                int sub = getPlaceSubMode(stack);
                BlockPos look = getLookTargetBlock(player, level);
                switch (sub) {
                    case SUB_ORIGIN -> {
                        if (look != null) {
                            setPlaceOrigin(stack, look.immutable());
                            Direction markerDirection = PresetPlacer.resolveMarkerDirection(level, look, player.getLookAngle());
                            setPlaceMarkerDirection(stack, markerDirection);
                            setPlaceAutoRotY(stack, resolveAutoRotY(level, look, stack, player));
                            setPlaceRotY(stack, 0);
                            player.displayClientMessage(Component.translatable(
                                            "tsu.tool.origin_set_short_fmt",
                                            look.getX(), look.getY(), look.getZ())
                                    .withStyle(ChatFormatting.AQUA), true);
                        }
                    }
                    case SUB_PLACE -> {
                        doPlaceAt(level, look, getPlaceOrigin(stack), stack, player);
                    }
                    default -> {
                    }
                }
            }
            return InteractionResultHolder.success(stack);
        }

        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) clearByEditMode(stack, player);
            return InteractionResultHolder.success(stack);
        }

        if (hasRange(stack) && getEditMode(stack) == 0) {
            if (level.isClientSide()) openSaveScreenClient();
            return InteractionResultHolder.success(stack);
        }

        if (!level.isClientSide()) {
            BlockPos targetPos = getLookTargetBlock(player, level);
            if (targetPos != null) {
                setPosition(stack, player, targetPos);
            }
        }
        return InteractionResultHolder.success(stack);
    }

    private static void doPlaceAt(Level level, BlockPos clickedPos, BlockPos storedOrigin, ItemStack stack, Player player) {
        if (level.isClientSide()) return;
        if (!(player instanceof ServerPlayer sp)) return;

        TrainPreset preset = loadSelectedPreset(sp, stack, player, true);
        if (preset == null) return;

        BlockPos markerPos = storedOrigin != null ? storedOrigin : clickedPos;
        if (markerPos == null) {
            player.displayClientMessage(
                    Component.translatable("tsu.tool.no_origin")
                            .withStyle(ChatFormatting.RED),
                    true);
            return;
        }

        Direction markerDirection = storedOrigin != null
                ? getPlaceMarkerDirection(stack)
                : PresetPlacer.resolveMarkerDirection(level, markerPos, player.getLookAngle());
        if (markerDirection == null && clickedPos != null && clickedPos.equals(markerPos)) {
            markerDirection = PresetPlacer.resolveMarkerDirection(level, clickedPos, player.getLookAngle());
        }

        // TSU-01: RCE / 構造ロード系ブロックを含む preset は配置を拒否し理由を表示する
        // (素材・粘着剤を消費する前に弾く)。 PresetPlacer.placeAt も最終ゲートとして二重に拒否する。
        String forbiddenBlock = PresetPlacer.findForbiddenBlock(preset);
        if (forbiddenBlock != null) {
            player.displayClientMessage(Component.translatable(
                            "tsu.tool.preset_forbidden_block", forbiddenBlock)
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        // 粘着剤タンク残量チェック (1 ブロック接着 = GLUE_PER_BLOCK)
        // 接着対象ブロック数で見積もり、設置後に実際に接着できた数だけ消費する。
        int glueEstimate = PresetPlacer.countGlueableBlocks(preset) * GLUE_PER_BLOCK;
        if (glueEstimate > 0 && getGlueTank(stack) < glueEstimate) {
            player.displayClientMessage(Component.translatable(
                            "tsu.tool.glue_short_fmt",
                            getGlueTank(stack), glueEstimate)
                    .withStyle(ChatFormatting.RED), true); // 粘着剤不足
            return;
        }

        int manualRotY = getPlaceRotY(stack);
        int autoRotY = getPlaceAutoRotY(stack);
        if (autoRotY < 0) {
            autoRotY = PresetPlacer.resolveAutoRotY(level, markerPos, preset, player.getLookAngle());
        }
        int effectiveRotY = Math.floorMod(autoRotY + manualRotY, 4);
        BlockPos origin = PresetPlacer.resolvePlacementOrigin(level, markerPos, preset, effectiveRotY);
        // クリエイティブはデフォルトで素材消費スキップ。テスト用に /tsupreset test forceConsume true で強制可能。
        boolean forceConsume = com.trainsystemutilities.command.TrainPresetCommand.isForceConsumeEnabled();
        if ((!player.getAbilities().instabuild || forceConsume)
                && !tryConsumePlacementMaterials(level, origin, preset, effectiveRotY, stack, player)) {
            return;
        }
        var result = PresetPlacer.placeAt(sp.serverLevel(), origin, preset, effectiveRotY);
        // 実際に SuperGlue 接着できたブロック数だけ消費 (skip された分は課金しない)
        int glueConsumed = result.gluedBlocks() * GLUE_PER_BLOCK;
        if (glueConsumed > 0) tryConsumeGlue(stack, glueConsumed);
        clearPlacementStatus(stack);
        String placeDetail = Component.translatable(
                "tsu.tool.place_summary_fmt",
                result.blocksPlaced(), result.entitiesSpawned(),
                result.glueClusters(), result.gluedBlocks(), glueConsumed).getString();
        PacketDistributor.sendToPlayer(sp,
                new TrainPresetPlaceResultPayload(
                        Component.translatable("tsu.tool.place_done").getString(),
                        placeDetail));

        // 設置完了後は PLACE モードを自動解除 (GUI モードへ戻る)。
        setToolMode(stack, TOOL_MODE_GUI);
        setPlaceSubMode(stack, SUB_ORIGIN);
        setPlaceOrigin(stack, null);
        setPlaceRotY(stack, 0);
        stack.remove(ModDataComponents.SELECTED_PRESET.get());
    }

    private static boolean tryConsumePlacementMaterials(Level level, BlockPos origin, TrainPreset preset, int rotY,
                                                        ItemStack stack, Player player) {
        var requirements = TrainPresetMaterials.collectPlacementRequirements(level, origin, preset, rotY);
        clearPlacementStatus(stack);
        if (requirements.isEmpty()) {
            return true;
        }

        int sourceMode = getMaterialSourceMode(stack);

        if (sourceMode == SOURCE_BOTH) {
            return tryConsumeBoth(level, requirements, stack, player);
        }

        if (sourceMode == SOURCE_ME) {
            if (!ModList.get().isLoaded("ae2")) {
                setPlacementStatusMessage(stack, Component.translatable("tsu.tool.ae2_missing").getString());
                return false;
            }

            // 新方式: AE2 Wireless Access Point リンク (GridLinkables 経由で WAP slot にツールを入れて取得)
            String wapLabel = getLinkedWapLabel(stack);
            if (stack.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) == null) {
                setPlacementStatusMessage(stack, Component.translatable("tsu.tool.wap_unlinked").getString());
                return false;
            }

            var missing = TrainPresetSupply.getWapMissing(player, stack, requirements);
            if (!missing.isEmpty()) {
                setPlacementStatusMessage(stack, buildMissingStatus(Component.translatable("tsu.tool.me_short").getString(), wapLabel));
                setMissingItems(stack, TrainPresetMaterials.encode(missing));
                return false;
            }

            if (!TrainPresetSupply.consumeFromWap(player, stack, requirements)) {
                setPlacementStatusMessage(stack, buildMissingStatus(Component.translatable("tsu.tool.me_extract_fail").getString(), wapLabel));
                setMissingItems(stack, TrainPresetMaterials.encode(requirements));
                return false;
            }
            return true;
        }

        BlockPos chestPos = getLinkedChestPos(stack);
        if (chestPos == null) {
            setPlacementStatusMessage(stack, Component.translatable("tsu.tool.chest_unlinked").getString());
            return false;
        }

        var missing = TrainPresetSupply.getChestMissing(level, chestPos, requirements);
        if (!missing.isEmpty()) {
            setPlacementStatusMessage(stack, buildMissingStatus(Component.translatable("tsu.tool.chest_short").getString(), getLinkedChestLabel(stack)));
            setMissingItems(stack, TrainPresetMaterials.encode(missing));
            return false;
        }

        if (!TrainPresetSupply.consumeFromChest(level, chestPos, requirements)) {
            setPlacementStatusMessage(stack, buildMissingStatus(Component.translatable("tsu.tool.chest_extract_fail").getString(), getLinkedChestLabel(stack)));
            setMissingItems(stack, TrainPresetMaterials.encode(requirements));
            return false;
        }

        return true;
    }

    /**
     * SOURCE_BOTH モード:
     * 各アイテムごとに ME 優先 (WAP 経由)、不足分のみ chest から消費する。
     * 事前に「合計 (ME + chest) >= 必要数」を全アイテムで確認し、足りなければ消費せず false。
     */
    private static boolean tryConsumeBoth(Level level, java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer> requirements,
                                          ItemStack stack, Player player) {
        boolean ae2 = ModList.get().isLoaded("ae2");
        boolean wapLinked = ae2 && stack.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) != null;
        BlockPos chestPos = getLinkedChestPos(stack);

        if (!wapLinked && chestPos == null) {
            setPlacementStatusMessage(stack, Component.translatable("tsu.tool.both_unlinked").getString());
            return false;
        }

        // ME / chest 各々の利用可能量を見積もる
        var meMissing = wapLinked
                ? TrainPresetSupply.getWapMissing(player, stack, requirements)
                : new java.util.LinkedHashMap<>(requirements); // WAP 未リンクなら全部 ME 不足
        var chestRemainder = new java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer>();
        for (var e : requirements.entrySet()) {
            int meHave = e.getValue() - meMissing.getOrDefault(e.getKey(), 0);
            int chestNeed = Math.max(0, e.getValue() - meHave);
            if (chestNeed > 0) chestRemainder.put(e.getKey(), chestNeed);
        }

        var chestMissing = chestPos == null
                ? new java.util.LinkedHashMap<>(chestRemainder)
                : TrainPresetSupply.getChestMissing(level, chestPos, chestRemainder);

        if (!chestMissing.isEmpty()) {
            String label = (wapLinked ? getLinkedWapLabel(stack) : "")
                    + (chestPos != null ? (" + " + getLinkedChestLabel(stack)) : "");
            setPlacementStatusMessage(stack, buildMissingStatus(Component.translatable("tsu.tool.both_short").getString(), label));
            setMissingItems(stack, TrainPresetMaterials.encode(chestMissing));
            return false;
        }

        // 消費フェーズ: ME → 残りを chest
        var meRequest = new java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer>();
        for (var e : requirements.entrySet()) {
            int meHave = e.getValue() - meMissing.getOrDefault(e.getKey(), 0);
            if (meHave > 0) meRequest.put(e.getKey(), meHave);
        }
        if (wapLinked && !meRequest.isEmpty()
                && !TrainPresetSupply.consumeFromWap(player, stack, meRequest)) {
            setPlacementStatusMessage(stack, Component.translatable("tsu.tool.me_extract_fail_simple").getString());
            setMissingItems(stack, TrainPresetMaterials.encode(meRequest));
            return false;
        }
        if (chestPos != null && !chestRemainder.isEmpty()
                && !TrainPresetSupply.consumeFromChest(level, chestPos, chestRemainder)) {
            // B3: ME は消費済みだが chest 抽出に失敗。 永久消失を防ぐため ME 消費分を player に返す
            // (inventory 溢れは足元に drop)。
            for (var e : meRequest.entrySet()) {
                returnItemsToPlayer(player, e.getKey(), e.getValue());
            }
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.warn(
                    "ME consumed but chest extraction failed; returned {} to player (no loss)", meRequest);
            setPlacementStatusMessage(stack, Component.translatable("tsu.tool.chest_extract_fail_after_me").getString());
            setMissingItems(stack, TrainPresetMaterials.encode(chestRemainder));
            return false;
        }
        return true;
    }

    /** B3: 消費したが place に失敗したアイテムを player に返す (inventory 溢れは drop)。永久消失を防ぐ。 */
    private static void returnItemsToPlayer(net.minecraft.world.entity.player.Player player,
                                            net.minecraft.world.item.Item item, int count) {
        if (player == null || item == null || count <= 0) return;
        int max = new ItemStack(item).getMaxStackSize();
        while (count > 0) {
            int n = Math.min(count, max);
            ItemStack s = new ItemStack(item, n);
            if (!player.getInventory().add(s) && !s.isEmpty()) {
                player.drop(s, false);
            }
            count -= n;
        }
    }

    private static String buildMissingStatus(String prefix, String label) {
        if (label == null || label.isBlank()) {
            return prefix;
        }
        return prefix + " [" + label + "]";
    }

    private static String buildSourceLabel(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock().getName().getString() + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static void openSaveScreenClient() {
        try {
            Class<?> screen = Class.forName("com.trainsystemutilities.client.gui.TrainPresetSaveScreenV2");
            screen.getMethod("open").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    private static void openBrowseScreenClient() {
        try {
            Class<?> screen = Class.forName("com.trainsystemutilities.client.gui.TrainPresetBrowseScreenV2");
            screen.getMethod("open").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    private static TrainPreset loadSelectedPreset(ServerPlayer player, ItemStack stack, Player feedbackTarget,
                                                    boolean notifyErrors) {
        String key = stack.get(ModDataComponents.SELECTED_PRESET.get());
        if (key == null || key.isEmpty()) {
            if (notifyErrors) {
                feedbackTarget.displayClientMessage(
                        Component.translatable("tsu.tool.preset_unselected")
                                .withStyle(ChatFormatting.RED),
                        true);
            }
            return null;
        }

        int sep = key.indexOf('/');
        if (sep < 0) {
            if (notifyErrors) {
                feedbackTarget.displayClientMessage(
                        Component.translatable("tsu.tool.preset_key_invalid")
                                .withStyle(ChatFormatting.RED),
                        true);
            }
            return null;
        }

        String authorDir = key.substring(0, sep);
        String fileName = key.substring(sep + 1);
        // SECURITY: path traversal 防御 — 任意 NBT 読込・設置誘導の抑止
        var path = TrainPresetStorage.safeResolveExisting(player.getServer(), authorDir, fileName);
        if (path == null) {
            if (notifyErrors) {
                feedbackTarget.displayClientMessage(
                        Component.translatable("tsu.tool.preset_key_invalid")
                                .withStyle(ChatFormatting.RED),
                        true);
            }
            return null;
        }
        try {
            return TrainPresetStorage.load(path);
        } catch (Exception ex) {
            if (notifyErrors) {
                feedbackTarget.displayClientMessage(
                        Component.translatable("tsu.tool.load_fail_fmt", ex.getMessage())
                                .withStyle(ChatFormatting.RED),
                        true);
            }
            return null;
        }
    }

    private static void clearByEditMode(ItemStack stack, Player player) {
        int editMode = getEditMode(stack);
        if (editMode == 1) {
            stack.remove(ModDataComponents.RANGE_POS1.get());
            player.displayClientMessage(Component.translatable("tsu.tool.pos1_clear")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else if (editMode == 2) {
            stack.remove(ModDataComponents.RANGE_POS2.get());
            player.displayClientMessage(Component.translatable("tsu.tool.pos2_clear")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else {
            stack.remove(ModDataComponents.RANGE_POS1.get());
            stack.remove(ModDataComponents.RANGE_POS2.get());
            player.displayClientMessage(Component.translatable("tsu.tool.range_clear")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }

    private static void setPosition(ItemStack stack, Player player, BlockPos pos) {
        int editMode = getEditMode(stack);
        if (editMode == 1) {
            stack.set(ModDataComponents.RANGE_POS1.get(), pos);
            notifyPosSet(player, "Pos1", pos);
        } else if (editMode == 2) {
            stack.set(ModDataComponents.RANGE_POS2.get(), pos);
            notifyPosSet(player, "Pos2", pos);
        } else {
            BlockPos pos1 = stack.get(ModDataComponents.RANGE_POS1.get());
            if (pos1 == null) {
                stack.set(ModDataComponents.RANGE_POS1.get(), pos);
                notifyPosSet(player, "Pos1", pos);
            } else {
                stack.set(ModDataComponents.RANGE_POS2.get(), pos);
                notifyPosSet(player, "Pos2", pos);
            }
        }
    }

    private static void notifyPosSet(Player player, String label, BlockPos pos) {
        player.displayClientMessage(
                Component.literal(label + ": " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                        .withStyle(ChatFormatting.GREEN), true);
    }

    private static BlockPos getLookTargetBlock(Player player, Level level) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(MAX_RANGE));
        BlockHitResult hitResult = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            return hitResult.getBlockPos();
        }
        return null;
    }

    public static int getToolMode(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.TOOL_MODE.get());
        return m != null ? m : TOOL_MODE_GUI;
    }

    public static void setToolMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.TOOL_MODE.get(), mode);
    }

    public static int getEditMode(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.RANGE_EDIT_MODE.get());
        return m != null ? m : 0;
    }

    public static void setEditMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.RANGE_EDIT_MODE.get(), mode);
    }

    public static int getPlaceSubMode(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.PLACE_SUB_MODE.get());
        return m != null ? m : SUB_ORIGIN;
    }

    public static void setPlaceSubMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.PLACE_SUB_MODE.get(), ((mode % SUB_COUNT) + SUB_COUNT) % SUB_COUNT);
    }

    public static BlockPos getPlaceOrigin(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_ORIGIN.get());
    }

    public static void setPlaceOrigin(ItemStack stack, BlockPos pos) {
        if (pos == null) {
            stack.remove(ModDataComponents.PLACE_ORIGIN.get());
            stack.remove(ModDataComponents.PLACE_MARKER_DIRECTION.get());
            stack.remove(ModDataComponents.PLACE_AUTO_ROT_Y.get());
        } else {
            stack.set(ModDataComponents.PLACE_ORIGIN.get(), pos);
        }
    }

    public static Direction getPlaceMarkerDirection(ItemStack stack) {
        Integer dir = stack.get(ModDataComponents.PLACE_MARKER_DIRECTION.get());
        return dir == null ? null : Direction.from3DDataValue(dir);
    }

    public static void setPlaceMarkerDirection(ItemStack stack, Direction direction) {
        if (direction == null) {
            stack.remove(ModDataComponents.PLACE_MARKER_DIRECTION.get());
        } else {
            stack.set(ModDataComponents.PLACE_MARKER_DIRECTION.get(), direction.get3DDataValue());
        }
    }

    public static int getPlaceRotY(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.PLACE_ROT_Y.get());
        return m != null ? m : 0;
    }

    public static void setPlaceRotY(ItemStack stack, int v) {
        stack.set(ModDataComponents.PLACE_ROT_Y.get(), ((v % 4) + 4) % 4);
    }

    public static int getPlaceAutoRotY(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.PLACE_AUTO_ROT_Y.get());
        return m != null ? m : -1;
    }

    public static void setPlaceAutoRotY(ItemStack stack, int v) {
        stack.set(ModDataComponents.PLACE_AUTO_ROT_Y.get(), ((v % 4) + 4) % 4);
    }

    public static int getMaterialSourceMode(ItemStack stack) {
        Integer mode = stack.get(ModDataComponents.PLACE_SOURCE_MODE.get());
        return mode != null ? mode : SOURCE_CHEST;
    }

    public static void setMaterialSourceMode(ItemStack stack, int mode) {
        int normalized = ((mode % SOURCE_COUNT) + SOURCE_COUNT) % SOURCE_COUNT;
        stack.set(ModDataComponents.PLACE_SOURCE_MODE.get(), normalized);
    }

    /** delta=+1 で ME → Both → Chest → ME の順に循環、-1 で逆順。 */
    public static void cycleMaterialSourceMode(ItemStack stack, int delta) {
        int next = getMaterialSourceMode(stack) + (delta >= 0 ? 1 : -1);
        setMaterialSourceMode(stack, next);
    }

    public static BlockPos getLinkedChestPos(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_LINKED_CHEST_POS.get());
    }

    public static String getLinkedChestLabel(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_LINKED_CHEST_LABEL.get());
    }

    public static void setLinkedChest(ItemStack stack, BlockPos pos, String label) {
        stack.set(ModDataComponents.PLACE_LINKED_CHEST_POS.get(), pos);
        stack.set(ModDataComponents.PLACE_LINKED_CHEST_LABEL.get(), label == null ? "" : label);
    }

    public static void clearLinkedChest(ItemStack stack) {
        stack.remove(ModDataComponents.PLACE_LINKED_CHEST_POS.get());
        stack.remove(ModDataComponents.PLACE_LINKED_CHEST_LABEL.get());
    }

    public static BlockPos getLinkedMePos(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_LINKED_ME_POS.get());
    }

    public static String getLinkedMeLabel(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_LINKED_ME_LABEL.get());
    }

    public static void setLinkedMe(ItemStack stack, BlockPos pos, String label) {
        stack.set(ModDataComponents.PLACE_LINKED_ME_POS.get(), pos);
        stack.set(ModDataComponents.PLACE_LINKED_ME_LABEL.get(), label == null ? "" : label);
    }

    public static void clearLinkedMe(ItemStack stack) {
        stack.remove(ModDataComponents.PLACE_LINKED_ME_POS.get());
        stack.remove(ModDataComponents.PLACE_LINKED_ME_LABEL.get());
        // WAP リンクも一緒に解除 (両方とも ME 系)
        stack.remove(ModDataComponents.PLACE_LINKED_WAP_POS.get());
        stack.remove(ModDataComponents.PLACE_LINKED_WAP_LABEL.get());
    }

    public static String getLinkedWapLabel(ItemStack stack) {
        String label = stack.get(ModDataComponents.PLACE_LINKED_WAP_LABEL.get());
        return label == null ? "" : label;
    }

    public static String getMissingItems(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_MISSING_ITEMS.get());
    }

    public static void setMissingItems(ItemStack stack, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            stack.remove(ModDataComponents.PLACE_MISSING_ITEMS.get());
        } else {
            stack.set(ModDataComponents.PLACE_MISSING_ITEMS.get(), encoded);
        }
    }

    public static String getPlacementStatusMessage(ItemStack stack) {
        return stack.get(ModDataComponents.PLACE_STATUS_MESSAGE.get());
    }

    public static void setPlacementStatusMessage(ItemStack stack, String message) {
        if (message == null || message.isBlank()) {
            stack.remove(ModDataComponents.PLACE_STATUS_MESSAGE.get());
        } else {
            stack.set(ModDataComponents.PLACE_STATUS_MESSAGE.get(), message);
        }
    }

    public static void clearPlacementStatus(ItemStack stack) {
        stack.remove(ModDataComponents.PLACE_MISSING_ITEMS.get());
        stack.remove(ModDataComponents.PLACE_STATUS_MESSAGE.get());
        stack.remove(ModDataComponents.PLACE_RESUME_READY.get());
    }

    public static boolean isPlaceResumeReady(ItemStack stack) {
        Boolean v = stack.get(ModDataComponents.PLACE_RESUME_READY.get());
        return v != null && v;
    }

    public static void setPlaceResumeReady(ItemStack stack, boolean ready) {
        if (ready) {
            stack.set(ModDataComponents.PLACE_RESUME_READY.get(), true);
        } else {
            stack.remove(ModDataComponents.PLACE_RESUME_READY.get());
        }
    }

    /** PLACE モードの再試行 (サーバ側): 保存済 origin/preset/rotation を使って doPlaceAt を再実行。 */
    public static void retryPlacement(Level level, ItemStack stack, Player player) {
        BlockPos origin = getPlaceOrigin(stack);
        if (origin == null) return;
        doPlaceAt(level, null, origin, stack, player);
    }

    // ===== 粘着剤タンク =====
    /** タンク最大容量 (gauge)。 */
    public static final int GLUE_TANK_MAX = 10000;
    /** 1 ブロック接着あたりの消費量 (gauge)。SuperGlueEntity 1 つに含まれる非線路ブロック数 × この値が課金される。 */
    public static final int GLUE_PER_BLOCK = 1;
    /** @deprecated 旧 carriage ベース見積もり用。現行は {@link #GLUE_PER_BLOCK} ベースで課金する。 */
    @Deprecated
    public static final int GLUE_PER_CARRIAGE = 10;
    /** スライムボール 1 個 = 何 gauge 分か。 */
    public static final int GLUE_PER_SLIME_BALL = 10;
    /** スライムブロック 1 個 = 何 gauge 分か (ball 9 個 = 90)。 */
    public static final int GLUE_PER_SLIME_BLOCK = 90;

    public static int getGlueTank(ItemStack stack) {
        Integer v = stack.get(ModDataComponents.GLUE_TANK.get());
        return v != null ? v : 0;
    }

    public static void setGlueTank(ItemStack stack, int v) {
        stack.set(ModDataComponents.GLUE_TANK.get(), Math.max(0, Math.min(GLUE_TANK_MAX, v)));
    }

    /** 残容量に余裕がある分だけ amount を加算。実際に加算した量を返す。 */
    public static int addGlueTank(ItemStack stack, int amount) {
        int cur = getGlueTank(stack);
        int next = Math.min(GLUE_TANK_MAX, cur + Math.max(0, amount));
        int actual = next - cur;
        if (actual > 0) setGlueTank(stack, next);
        return actual;
    }

    /** amount だけ消費。残量不足なら false (消費しない)。 */
    public static boolean tryConsumeGlue(ItemStack stack, int amount) {
        int cur = getGlueTank(stack);
        if (cur < amount) return false;
        setGlueTank(stack, cur - amount);
        return true;
    }

    public static String subModeLabel(int sub) {
        switch (sub) {
            case SUB_ORIGIN: return Component.translatable("tsu.tool.mode_origin").getString();
            case SUB_ROT: return Component.translatable("tsu.tool.mode_rotate").getString();
            case SUB_PLACE: return Component.translatable("tsu.tool.mode_place").getString();
            default: return "?";
        }
    }

    /* OLD removed - subModeLabel above replaces this
    @SuppressWarnings("unused")
    private static String subModeLabel_OLD2(int sub) {
        return switch (sub) {
            case SUB_ORIGIN -> "\u8d77\u70b9";   // 起点
            case SUB_ROT -> "\u56de\u8ee2";      // 回転 (統合モード)
            case SUB_PLACE -> "\u8a2d\u7f6e";    // 設置
            default -> "?";
        };
    }
    */

    private static int resolveAutoRotY(Level level, BlockPos clickedPos, ItemStack stack, Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return 0;
        }
        TrainPreset preset = loadSelectedPreset(sp, stack, player, false);
        if (preset == null) {
            return 0;
        }
        return PresetPlacer.resolveAutoRotY(level, clickedPos, preset, player.getLookAngle());
    }

    public static String subModeIcon(int sub) {
        return switch (sub) {
            case SUB_ORIGIN -> "\u2316";  // ⌖
            case SUB_ROT -> "\u21bb";     // ↻
            case SUB_PLACE -> "\u25c9";   // ◉
            default -> "?";
        };
    }

    public static boolean hasRange(ItemStack stack) {
        return stack.has(ModDataComponents.RANGE_POS1.get())
                && stack.has(ModDataComponents.RANGE_POS2.get());
    }

    public static BlockPos getPos1(ItemStack stack) {
        return stack.get(ModDataComponents.RANGE_POS1.get());
    }

    public static BlockPos getPos2(ItemStack stack) {
        return stack.get(ModDataComponents.RANGE_POS2.get());
    }

    public static ItemStack findHeldTool(Player player) {
        return belugalab.tsu.api.HeldTools.find(player, ModItems.TRAIN_PRESET_TOOL.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int mode = getToolMode(stack);
        String modeName = switch (mode) {
            case TOOL_MODE_SELECTION -> "Selection";
            case TOOL_MODE_PLACE -> "Place";
            default -> "GUI";
        };
        tooltip.add(Component.translatable("tsu.tool.mode_label_fmt", modeName).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tsu.tool.alt_wheel_hint")
                .withStyle(ChatFormatting.DARK_GRAY));

        BlockPos p1 = getPos1(stack);
        BlockPos p2 = getPos2(stack);
        if (p1 != null) {
            tooltip.add(Component.literal("Pos1: " + p1.getX() + "," + p1.getY() + "," + p1.getZ())
                    .withStyle(ChatFormatting.GREEN));
        }
        if (p2 != null) {
            tooltip.add(Component.literal("Pos2: " + p2.getX() + "," + p2.getY() + "," + p2.getZ())
                    .withStyle(ChatFormatting.GREEN));
        }

        int editMode = getEditMode(stack);
        if (editMode == 1) tooltip.add(Component.translatable("tsu.tool.editing_pos1").withStyle(ChatFormatting.YELLOW));
        else if (editMode == 2) tooltip.add(Component.translatable("tsu.tool.editing_pos2").withStyle(ChatFormatting.YELLOW));

        String preset = stack.get(ModDataComponents.SELECTED_PRESET.get());
        if (preset != null && !preset.isEmpty()) {
            tooltip.add(Component.translatable("tsu.tool.preset_label_fmt", preset).withStyle(ChatFormatting.GOLD));
        }

        tooltip.add(Component.translatable("tsu.tool.material_supply_fmt", sourceLabel(getMaterialSourceMode(stack)))
                .withStyle(ChatFormatting.BLUE));

        // Chest リンク情報
        String chestLabel = getLinkedChestLabel(stack);
        if (chestLabel != null && !chestLabel.isBlank()) {
            tooltip.add(Component.literal("  Chest: " + chestLabel).withStyle(ChatFormatting.DARK_AQUA));
        }

        // ME (Wireless Access Point) リンク情報
        String wapLabel = getLinkedWapLabel(stack);
        if (wapLabel != null && !wapLabel.isBlank()) {
            tooltip.add(Component.translatable("tsu.tool.me_linked")
                    .withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.literal("    " + wapLabel).withStyle(ChatFormatting.DARK_GREEN));
        }
    }
}
