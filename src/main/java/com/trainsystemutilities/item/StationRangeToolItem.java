package com.trainsystemutilities.item;

import com.trainsystemutilities.client.gui.StationGroupSaveScreenOpener;
import com.trainsystemutilities.registry.ModDataComponents;
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

import java.util.List;

/**
 * 駅範囲指定ツール。{@link com.trainsystemutilities.item.TrainPresetToolItem} と
 * 同じ範囲指定 UX を流用 (RANGE_POS1 / RANGE_POS2 / RANGE_EDIT_MODE 共有)。
 *
 * <p>UX:
 * <ul>
 *   <li>右クリック (ブロック): editMode に応じて pos1/pos2 を設定。両方確定済 +
 *       editMode == 0 なら GUI を開く。</li>
 *   <li>shift + 右クリック: editMode 1 = pos1 のみクリア / 2 = pos2 のみ / 0 = 両方クリア</li>
 *   <li>alt + ホイール: editMode を 0 (両方) ↔ 1 (pos1) ↔ 2 (pos2) で循環。
 *       editMode != 0 のとき再クリックで個別編集できる。</li>
 * </ul>
 */
public class StationRangeToolItem extends Item {

    /** vanilla の reach 距離を超えてもブロックを指定できるよう、ロングレンジで ray cast する。 */
    private static final double MAX_RANGE = 64.0;

    /** ツールの動作モード。alt+ホイールで循環。 */
    public static final int TOOL_MODE_GUI = 0;        // 管理 GUI を開く
    public static final int TOOL_MODE_SELECTION = 1;  // 範囲選択 (current default)
    public static final int TOOL_MODE_VIEW = 2;       // 既存グループの枠を全て表示

    /** 番線連番方向モード。ctrl+ホイール押し込みで循環。 */
    public static final int NUM_DIR_AUTO = 0;   // creator pos の近い端を 1 番線 (デフォルト)
    public static final int NUM_DIR_LEFT = 1;   // 主軸 min 端を 1 番線
    public static final int NUM_DIR_RIGHT = 2;  // 主軸 max 端を 1 番線
    public static final int NUM_DIR_COUNT = 3;

    public StationRangeToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static BlockPos getPos1(ItemStack stack) {
        return stack.get(ModDataComponents.RANGE_POS1.get());
    }
    public static BlockPos getPos2(ItemStack stack) {
        return stack.get(ModDataComponents.RANGE_POS2.get());
    }
    public static int getEditMode(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.RANGE_EDIT_MODE.get());
        return m == null ? 0 : m;
    }
    public static void setEditMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.RANGE_EDIT_MODE.get(), ((mode % 3) + 3) % 3);
    }
    public static int getToolMode(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.TOOL_MODE.get());
        return m == null ? TOOL_MODE_SELECTION : m;
    }
    public static void setToolMode(ItemStack stack, int mode) {
        stack.set(ModDataComponents.TOOL_MODE.get(), ((mode % 3) + 3) % 3);
    }

    public static int getNumberingDir(ItemStack stack) {
        Integer m = stack.get(ModDataComponents.PLATFORM_NUMBERING_DIR.get());
        return m == null ? NUM_DIR_AUTO : m;
    }
    public static void setNumberingDir(ItemStack stack, int dir) {
        stack.set(ModDataComponents.PLATFORM_NUMBERING_DIR.get(),
                ((dir % NUM_DIR_COUNT) + NUM_DIR_COUNT) % NUM_DIR_COUNT);
    }
    public static boolean hasRange(ItemStack stack) {
        return getPos1(stack) != null && getPos2(stack) != null;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        Level level = ctx.getLevel();
        ItemStack stack = ctx.getItemInHand();
        int mode = getToolMode(stack);

        // GUI mode: 管理 GUI を開く (位置情報不要)
        if (mode == TOOL_MODE_GUI) {
            if (level.isClientSide()) {
                StationGroupSaveScreenOpener.openManageScreen();
            }
            return InteractionResult.SUCCESS;
        }
        // VIEW mode: クリックは何もしない (renderer が outline を表示)
        if (mode == TOOL_MODE_VIEW) {
            return InteractionResult.SUCCESS;
        }

        // SELECTION mode (default)
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) clearByEditMode(stack, player);
            return InteractionResult.SUCCESS;
        }
        if (hasRange(stack) && getEditMode(stack) == 0) {
            if (level.isClientSide()) {
                StationGroupSaveScreenOpener.open(getPos1(stack), getPos2(stack));
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide()) {
            setPosition(stack, player, ctx.getClickedPos());
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int mode = getToolMode(stack);

        if (mode == TOOL_MODE_GUI) {
            if (level.isClientSide()) {
                StationGroupSaveScreenOpener.openManageScreen();
            }
            return InteractionResultHolder.success(stack);
        }
        if (mode == TOOL_MODE_VIEW) {
            return InteractionResultHolder.success(stack);
        }

        // SELECTION mode
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) clearByEditMode(stack, player);
            return InteractionResultHolder.success(stack);
        }
        if (hasRange(stack) && getEditMode(stack) == 0) {
            if (level.isClientSide()) {
                StationGroupSaveScreenOpener.open(getPos1(stack), getPos2(stack));
            }
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

    /** プレイヤーの視線先のブロックを最大 {@link #MAX_RANGE} 距離まで ray cast。 */
    private static BlockPos getLookTargetBlock(Player player, Level level) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(MAX_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx,
                                List<Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
        int toolMode = getToolMode(stack);
        String modeKey = switch (toolMode) {
            case TOOL_MODE_GUI -> "tsu.station_tool.mode_gui";
            case TOOL_MODE_VIEW -> "tsu.station_tool.mode_view";
            default -> "tsu.station_tool.mode_selection";
        };
        tooltip.add(Component.translatable(modeKey).withStyle(ChatFormatting.LIGHT_PURPLE));

        BlockPos p1 = getPos1(stack);
        BlockPos p2 = getPos2(stack);
        int editMode = getEditMode(stack);
        if (p1 != null) {
            tooltip.add(Component.literal("Pos1: " + p1.getX() + ", " + p1.getY() + ", " + p1.getZ())
                    .withStyle(ChatFormatting.AQUA));
        }
        if (p2 != null) {
            tooltip.add(Component.literal("Pos2: " + p2.getX() + ", " + p2.getY() + ", " + p2.getZ())
                    .withStyle(ChatFormatting.AQUA));
        }
        if (p1 == null && p2 == null) {
            tooltip.add(Component.translatable("tsu.station_tool.tooltip_idle")
                    .withStyle(ChatFormatting.GRAY));
        } else if (editMode == 0 && p1 != null && p2 != null) {
            tooltip.add(Component.translatable("tsu.station_tool.tooltip_ready")
                    .withStyle(ChatFormatting.GREEN));
        }
        if (editMode == 1) {
            tooltip.add(Component.translatable("tsu.station_tool.editing_pos1")
                    .withStyle(ChatFormatting.YELLOW));
        } else if (editMode == 2) {
            tooltip.add(Component.translatable("tsu.station_tool.editing_pos2")
                    .withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("tsu.station_tool.alt_wheel_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** editMode に応じて該当 pos のみ (or 両方) をクリア。 */
    private static void clearByEditMode(ItemStack stack, Player player) {
        int editMode = getEditMode(stack);
        if (editMode == 1) {
            stack.remove(ModDataComponents.RANGE_POS1.get());
            player.displayClientMessage(Component.translatable("tsu.station_tool.pos1_cleared")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else if (editMode == 2) {
            stack.remove(ModDataComponents.RANGE_POS2.get());
            player.displayClientMessage(Component.translatable("tsu.station_tool.pos2_cleared")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else {
            stack.remove(ModDataComponents.RANGE_POS1.get());
            stack.remove(ModDataComponents.RANGE_POS2.get());
            player.displayClientMessage(Component.translatable("tsu.station_tool.range_cleared")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }

    /** editMode に従って pos1/pos2 を設定。editMode 0 なら未設定の方に詰める。 */
    private static void setPosition(ItemStack stack, Player player, BlockPos pos) {
        int editMode = getEditMode(stack);
        if (editMode == 1) {
            stack.set(ModDataComponents.RANGE_POS1.get(), pos.immutable());
            notifyPos(player, "tsu.station_tool.pos1_set_fmt", pos);
        } else if (editMode == 2) {
            stack.set(ModDataComponents.RANGE_POS2.get(), pos.immutable());
            notifyPos(player, "tsu.station_tool.pos2_set_fmt", pos);
        } else {
            BlockPos pos1 = getPos1(stack);
            if (pos1 == null) {
                stack.set(ModDataComponents.RANGE_POS1.get(), pos.immutable());
                notifyPos(player, "tsu.station_tool.pos1_set_fmt", pos);
            } else {
                stack.set(ModDataComponents.RANGE_POS2.get(), pos.immutable());
                notifyPos(player, "tsu.station_tool.pos2_set_fmt", pos);
            }
        }
    }

    private static void notifyPos(Player player, String key, BlockPos pos) {
        player.displayClientMessage(
                Component.translatable(key, pos.getX(), pos.getY(), pos.getZ())
                        .withStyle(ChatFormatting.GREEN), true);
    }
}
