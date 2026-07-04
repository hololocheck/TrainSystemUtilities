package com.trainsystemutilities.electrification.item;

import com.trainsystemutilities.client.electrification.OverheadPoleAutoSettingsScreenOpener;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig;
import com.trainsystemutilities.electrification.autoplace.AutoPlaceExecutor;
import com.trainsystemutilities.electrification.autoplace.AutoPlacePathFinder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 架線柱設置補助ツール (= 1 クリック配置)。
 *
 * <p>**自動配置アルゴリズムは破棄済み**。
 *
 * <p>UX:
 * <ol>
 *   <li>ツールを持つ / 右クリック → GUI が開く (= default tool mode = GUI)</li>
 *   <li>GUI で 高さ/クリアランス/複線化数/片持ち/トラス/碍子 を設定</li>
 *   <li>「配置する」 モードに切替 → 線路を右クリックで **その場所に 1 個 portal を配置**</li>
 *   <li>hover 中は半透明プレビュー (= 別 client renderer で実装)</li>
 * </ol>
 *
 * <p>サブモード (= ctrl+wheel で循環):
 * <ul>
 *   <li>GUIへ戻る (0) — 右クリックで GUI へ戻る</li>
 *   <li>配置する (1) — 右クリックした線路に portal 1 個配置</li>
 * </ul>
 */
public class OverheadPoleAutoToolItem extends Item {

    /** 線路選択のレイキャスト最大距離 (= 駅範囲指定ツール等と統一の 64 ブロック)。 */
    public static final double MAX_LOOK_DISTANCE = 64.0;

    public OverheadPoleAutoToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** 64 ブロックの自前 raycast で視線先のブロック位置を取得。 */
    public static BlockPos rayTraceBlock(Level level, Player player) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(MAX_LOOK_DISTANCE));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos clickedPos = context.getClickedPos();
        if (player == null) return InteractionResult.PASS;

        // shift+右クリックでチェストをリンク (= ME倉庫の AE2 GridLinkable は AE2 が shift+右クリックを
        // 先に消費するため、 ここへ来るのは通常チェストのみ)。 useOn は server 権威で clickedPos は手元
        // なので spoof 無し・payload 不要。 列車プリセットツールと共用の PLACE_LINKED_CHEST_POS に保存。
        if (player.isShiftKeyDown()
                && com.trainsystemutilities.preset.TrainPresetSupply.canLinkChest(level, clickedPos)) {
            if (!level.isClientSide) {
                var state = level.getBlockState(clickedPos);
                String label = state.getBlock().getName().getString()
                        + " (" + clickedPos.getX() + ", " + clickedPos.getY() + ", " + clickedPos.getZ() + ")";
                stack.set(com.trainsystemutilities.registry.ModDataComponents.PLACE_LINKED_CHEST_POS.get(),
                        clickedPos.immutable());
                stack.set(com.trainsystemutilities.registry.ModDataComponents.PLACE_LINKED_CHEST_LABEL.get(), label);
                player.displayClientMessage(Component.translatable("tsu.tool.chest_linked_fmt", label)
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return InteractionResult.SUCCESS;
        }

        // GUI mode: 設定スクリーンを開く
        if (AutoPlaceConfig.getToolMode(stack) == AutoPlaceConfig.TOOL_MODE_GUI) {
            if (level.isClientSide) OverheadPoleAutoSettingsScreenOpener.open();
            return InteractionResult.SUCCESS;
        }

        // SELECTION mode → サブモードで分岐
        int subMode = AutoPlaceConfig.getSubMode(stack);

        // GUIへ戻る: tool mode を GUI に戻す + 設定画面を開く
        if (subMode == AutoPlaceConfig.SUB_MODE_GUI_RETURN) {
            if (!level.isClientSide) {
                AutoPlaceConfig.setToolMode(stack, AutoPlaceConfig.TOOL_MODE_GUI);
                AutoPlaceConfig.setSubMode(stack, AutoPlaceConfig.SUB_MODE_PLACE);
            }
            if (level.isClientSide) OverheadPoleAutoSettingsScreenOpener.open();
            return InteractionResult.SUCCESS;
        }

        // 配置する: 線路上に portal 1 個配置
        if (!AutoPlacePathFinder.isTrack(level, clickedPos)) {
            if (level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("tsu.opa_tool.right_click_track")
                                .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;

        AutoPlaceExecutor.Result result = AutoPlaceExecutor.placeOne(level, clickedPos, stack, player);
        if (result.insufficient()) {
            player.displayClientMessage(
                    Component.translatable("tsu.opa_tool.insufficient_materials")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        if (result.placedCount() == 0) {
            player.displayClientMessage(
                    Component.translatable("tsu.opa_tool.place_fail_track")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }
        player.displayClientMessage(
                Component.translatable("tsu.opa_tool.place_done_fmt", result.placedCount())
                        .withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int toolMode = AutoPlaceConfig.getToolMode(stack);

        // GUI mode: 空中右クリックでも設定スクリーンを開く
        if (toolMode == AutoPlaceConfig.TOOL_MODE_GUI) {
            if (level.isClientSide) OverheadPoleAutoSettingsScreenOpener.open();
            return InteractionResultHolder.success(stack);
        }

        // SELECTION + GUIへ戻る: 空中右クリックでも GUI へ戻る
        int subMode = AutoPlaceConfig.getSubMode(stack);
        if (subMode == AutoPlaceConfig.SUB_MODE_GUI_RETURN) {
            if (!level.isClientSide) {
                AutoPlaceConfig.setToolMode(stack, AutoPlaceConfig.TOOL_MODE_GUI);
                AutoPlaceConfig.setSubMode(stack, AutoPlaceConfig.SUB_MODE_PLACE);
            }
            if (level.isClientSide) OverheadPoleAutoSettingsScreenOpener.open();
            return InteractionResultHolder.success(stack);
        }

        // SubMode_PLACE: 1) Create carve raycast → 2) 通常 block raycast の順で試行
        //                配置は ANGLE_8 (= 8 方向 snap)
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        AutoPlaceExecutor.Result result;
        var curveHit = com.trainsystemutilities.electrification.autoplace.TrackCurveRaycast
                .raycast(level, player);
        if (curveHit != null) {
            int scanRange = com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig.getScanRange(stack);
            int multiTrack = com.trainsystemutilities.electrification.autoplace.AutoPlaceConfig.getMultiTrackCount(stack);
            // axis を player の look 方向に揃える (= curve 接線の ±反転で portal が逆向きになる問題回避)
            net.minecraft.world.phys.Vec3 axis = curveHit.axis();
            net.minecraft.world.phys.Vec3 look = player.getLookAngle();
            net.minecraft.world.phys.Vec3 lookFlat = new net.minecraft.world.phys.Vec3(look.x, 0, look.z);
            if (lookFlat.lengthSqr() > 1.0E-6 && axis.x * lookFlat.x + axis.z * lookFlat.z < 0) {
                axis = new net.minecraft.world.phys.Vec3(-axis.x, axis.y, -axis.z);
            }
            // multiTrack=1 (= 単線) なら 並走 detection を無効化 (= 強制単線)
            java.util.List<net.minecraft.world.phys.Vec3> parallel = null;
            if (multiTrack >= 2) {
                parallel = com.trainsystemutilities.electrification.autoplace.TrackCurveRaycast
                        .findParallelCurveCenters(level, curveHit.worldPoint(), axis, scanRange);
                if (parallel.size() < 2) parallel = null;
            }
            result = AutoPlaceExecutor.placeOneAt(level, curveHit.worldPoint(), axis, stack, parallel, player);
        } else {
            BlockPos trackPos = rayTraceBlock(level, player);
            if (trackPos == null || !AutoPlacePathFinder.isTrack(level, trackPos)) {
                player.displayClientMessage(
                        Component.translatable("tsu.opa_tool.no_track_in_range")
                                .withStyle(ChatFormatting.RED), true);
                return InteractionResultHolder.fail(stack);
            }
            result = AutoPlaceExecutor.placeOne(level, trackPos, stack, player);
        }
        if (result.insufficient()) {
            player.displayClientMessage(
                    Component.translatable("tsu.opa_tool.insufficient_materials")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }
        if (result.placedCount() == 0) {
            player.displayClientMessage(
                    Component.translatable("tsu.opa_tool.place_fail_axis")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }
        player.displayClientMessage(
                Component.translatable("tsu.opa_tool.place_done_fmt", result.placedCount())
                        .withStyle(ChatFormatting.GREEN), true);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                  java.util.List<Component> tooltip,
                                  net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.translatable("tsu.opa_tool.tooltip_params_fmt",
                AutoPlaceConfig.getHeight(stack),
                AutoPlaceConfig.getClearance(stack),
                AutoPlaceConfig.getMultiTrackCount(stack)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tsu.opa_tool.tooltip_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
