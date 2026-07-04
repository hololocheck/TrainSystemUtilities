package com.trainsystemutilities.electrification.item;

import com.trainsystemutilities.electrification.ElectrificationConstants;
import com.trainsystemutilities.electrification.block.InsulatorBlock;
import com.trainsystemutilities.electrification.blockentity.InsulatorBlockEntity;
import com.trainsystemutilities.electrification.wire.WireConnection;
import com.trainsystemutilities.electrification.wire.WireNetworkSavedData;
import com.trainsystemutilities.electrification.wire.WireSyncBroadcaster;
import com.trainsystemutilities.electrification.wire.WireType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 架線接続ツール (Wire Connector)。
 *
 * <p>使い方:
 * <ol>
 *   <li>碍子 A を右クリック → 「接続元」として記憶</li>
 *   <li>碍子 B を右クリック → A-B 間に架線接続を作成</li>
 *   <li>同じ碍子を 2 回クリック / Shift+右クリック空中 → 記憶クリア</li>
 * </ol>
 *
 * <p>Phase A3 のスコープ: 接続作成だけ。接続削除は別ツール (= 碍子 break で
 * 入射接続を全削除する InsulatorBlock.onRemove に委譲) または、後続フェーズで
 * 「接続を直接破壊する」ツールを別途用意する。
 */
public class WireConnectorItem extends Item {

    private static final String KEY_PENDING_POS = "pendingPos";
    private static final String KEY_WIRE_TYPE = "wireType";
    private static final String KEY_TOOL_MODE = "toolMode";
    /** SIMPLE 線専用「たるみモード」フラグ。true なら catenary 曲線で描画。 */
    private static final String KEY_WIRE_SAG = "wireSag";
    /** CUSTOM 線パラメータ。 */
    private static final String KEY_CUSTOM_THICKNESS = "customThk";
    private static final String KEY_CUSTOM_TROLLEY_OFFSET = "customTrolleyOff";
    private static final String KEY_CUSTOM_DROPPER_INTERVAL = "customDropperInt";
    private static final String KEY_CUSTOM_ROW_COUNT = "customRowCount";

    public static final int TOOL_MODE_SELECTION = 0;
    public static final int TOOL_MODE_GUI = 1;
    public static final int TOOL_MODE_COUNT = 2;

    /** 架線タンク残量 (m / block)。スプール装填で増え、設置で距離分消費する。 */
    private static final String KEY_WIRE_TANK = "wireTank";
    /** タンクへ装填できる最大スプール数。 */
    public static final int MAX_LOAD_SPOOLS = 64;
    /** タンク上限 (m) = スプール 1 個分 × 最大装填数 = 6400m。 */
    public static final int WIRE_TANK_MAX = WireSpoolItem.METERS_PER_SPOOL * MAX_LOAD_SPOOLS;

    public static String toolModeLabel(int mode) {
        return switch (mode) {
            case TOOL_MODE_GUI -> Component.translatable("tsu.wire_connector.toolmode_gui").getString();
            default -> Component.translatable("tsu.wire_connector.toolmode_selection").getString();
        };
    }

    public WireConnectorItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        // GUI mode: 架線設定 画面 (= デザイン選択 + ゲージ + 補充ボタン) を開く
        if (readToolMode(stack) == TOOL_MODE_GUI) {
            if (level.isClientSide()) {
                com.trainsystemutilities.client.gui.WireConnectorScreenOpener.open();
            }
            return InteractionResult.SUCCESS;
        }

        boolean isInsulator = level.getBlockState(clicked).getBlock() instanceof InsulatorBlock;
        if (level.isClientSide()) {
            return isInsulator ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server) || player == null) {
            return InteractionResult.PASS;
        }
        if (!isInsulator) {
            // 接続中で碍子以外をクリック → 何もしない (= 通常クリックを通す)
            return InteractionResult.PASS;
        }

        BlockPos pending = readPending(stack);

        // 1 回目: 接続元を記録
        if (pending == null) {
            writePending(stack, clicked);
            sendStatus(player, ChatFormatting.YELLOW,
                    Component.translatable("tsu.wire_connector.pending_set_fmt",
                            clicked.getX(), clicked.getY(), clicked.getZ()));
            return InteractionResult.SUCCESS;
        }

        // 同じ碍子をクリック → クリア
        if (pending.equals(clicked)) {
            clearPending(stack);
            sendStatus(player, ChatFormatting.GRAY, Component.translatable("tsu.wire_connector.pending_cleared"));
            return InteractionResult.SUCCESS;
        }

        // 接続作成
        BlockEntity beA = level.getBlockEntity(pending);
        BlockEntity beB = level.getBlockEntity(clicked);
        if (!(beA instanceof InsulatorBlockEntity insA) || !(beB instanceof InsulatorBlockEntity insB)) {
            // 接続元 BE が存在しない (= 既に破壊された) 場合
            clearPending(stack);
            sendStatus(player, ChatFormatting.RED, Component.translatable("tsu.wire_connector.pending_be_missing"));
            return InteractionResult.FAIL;
        }
        Vec3 attachA = insA.getAttachmentPos();
        Vec3 attachB = insB.getAttachmentPos();
        double dist = attachA.distanceTo(attachB);

        if (dist > ElectrificationConstants.MAX_WIRE_LENGTH) {
            sendStatus(player, ChatFormatting.RED,
                    Component.translatable("tsu.wire_connector.too_far_fmt",
                            String.format("%.1f", dist),
                            String.format("%.0f", ElectrificationConstants.MAX_WIRE_LENGTH)));
            return InteractionResult.FAIL;
        }
        if (dist < ElectrificationConstants.MIN_WIRE_LENGTH) {
            sendStatus(player, ChatFormatting.RED,
                    Component.translatable("tsu.wire_connector.too_close_fmt", String.format("%.2f", dist)));
            return InteractionResult.FAIL;
        }

        WireNetworkSavedData data = WireNetworkSavedData.get(server);
        if (data.exists(pending, clicked)) {
            clearPending(stack);
            sendStatus(player, ChatFormatting.YELLOW, Component.translatable("tsu.wire_connector.already_connected"));
            return InteractionResult.FAIL;
        }

        // 架線コスト = 距離 (m)。サバイバルではタンク残量から消費し、不足なら設置不可。
        // クリエイティブはタンク不要・消費なし。
        boolean creative = player.getAbilities().instabuild;
        int wireCost = (int) Math.ceil(dist);
        if (!creative && readWireTank(stack) < wireCost) {
            sendStatus(player, ChatFormatting.RED,
                    Component.translatable("tsu.wire_connector.insufficient_wire_fmt",
                            wireCost, readWireTank(stack)));
            return InteractionResult.FAIL;
        }

        WireType selectedType = readWireType(stack);
        boolean sag = readSag(stack) && selectedType == WireType.SIMPLE; // sag は SIMPLE 専用
        data.add(WireConnection.of(pending, clicked,
                level.getBlockState(pending).getValue(InsulatorBlock.FACING),
                level.getBlockState(clicked).getValue(InsulatorBlock.FACING),
                dist, selectedType, sag,
                readCustomThickness(stack), readCustomTrolleyOffset(stack),
                readCustomDropperInterval(stack), readCustomRowCount(stack)));
        WireSyncBroadcaster.broadcast(server);
        if (!creative) {
            consumeWireTank(stack, wireCost);
        }
        clearPending(stack);
        sendStatus(player, ChatFormatting.AQUA,
                Component.translatable("tsu.wire_connector.connected_fmt",
                        selectedType.displayName(), String.format("%.1f", dist)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // GUI mode で右クリック (空中) → 架線設定 画面を開く
        if (readToolMode(stack) == TOOL_MODE_GUI) {
            if (level.isClientSide()) {
                com.trainsystemutilities.client.gui.WireConnectorScreenOpener.open();
            }
            return InteractionResultHolder.success(stack);
        }

        if (player.isShiftKeyDown() && readPending(stack) != null) {
            if (!level.isClientSide()) {
                clearPending(stack);
                sendStatus(player, ChatFormatting.GRAY, Component.translatable("tsu.wire_connector.pending_cleared"));
            }
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltips, TooltipFlag flag) {
        int mode = readToolMode(stack);
        tooltips.add(Component.translatable("tsu.wire_connector.tt_mode_label")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(toolModeLabel(mode))
                        .withStyle(mode == TOOL_MODE_GUI ? ChatFormatting.GOLD : ChatFormatting.AQUA)));

        WireType type = readWireType(stack);
        tooltips.add(Component.translatable("tsu.wire_connector.tt_design_label")
                .withStyle(ChatFormatting.GRAY)
                .append(type.displayName().copy().withStyle(ChatFormatting.AQUA)));

        tooltips.add(Component.translatable("tsu.wire_connector.tt_tank_fmt",
                        readWireTank(stack), WIRE_TANK_MAX)
                .withStyle(ChatFormatting.GRAY));

        if (mode == TOOL_MODE_SELECTION) {
            BlockPos pending = readPending(stack);
            if (pending != null) {
                tooltips.add(Component.translatable("tsu.wire_connector.tt_pending_fmt",
                                pending.getX(), pending.getY(), pending.getZ())
                        .withStyle(ChatFormatting.AQUA));
            } else {
                tooltips.add(Component.translatable("tsu.wire_connector.tt_hint_select")
                        .withStyle(ChatFormatting.GRAY));
            }
        } else {
            tooltips.add(Component.translatable("tsu.wire_connector.tt_hint_gui")
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltips.add(Component.translatable("tsu.wire_connector.tt_alt_wheel")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltips.add(Component.translatable("tsu.wire_connector.tt_max_length_fmt",
                        (int) ElectrificationConstants.MAX_WIRE_LENGTH)
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    // ===== tool mode =====

    public static int readToolMode(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return TOOL_MODE_SELECTION;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(KEY_TOOL_MODE)) return TOOL_MODE_SELECTION;
        return tag.getInt(KEY_TOOL_MODE);
    }

    public static void writeToolMode(ItemStack stack, int mode) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putInt(KEY_TOOL_MODE, mode);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ===== 架線タンク (= スプール装填残量。 m / block 単位) =====

    public static int readWireTank(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return 0;
        CompoundTag tag = cd.copyTag();
        return tag.contains(KEY_WIRE_TANK) ? Math.max(0, tag.getInt(KEY_WIRE_TANK)) : 0;
    }

    public static void writeWireTank(ItemStack stack, int meters) {
        int clamped = Math.max(0, Math.min(WIRE_TANK_MAX, meters));
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putInt(KEY_WIRE_TANK, clamped);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /** タンクへ充填し、実際に入った量 (m) を返す (= 上限超過分は弾く)。 */
    public static int addWireTank(ItemStack stack, int meters) {
        int cur = readWireTank(stack);
        int next = Math.min(WIRE_TANK_MAX, cur + Math.max(0, meters));
        writeWireTank(stack, next);
        return next - cur;
    }

    /** タンクから消費する (= 0 未満にはならない)。 */
    public static void consumeWireTank(ItemStack stack, int meters) {
        writeWireTank(stack, readWireTank(stack) - Math.max(0, meters));
    }

    // ===== wire type 選択 =====

    public static WireType readWireType(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return WireType.TWO_TIER;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(KEY_WIRE_TYPE)) return WireType.TWO_TIER;
        return WireType.fromId(tag.getInt(KEY_WIRE_TYPE));
    }

    public static void writeWireType(ItemStack stack, WireType type) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putInt(KEY_WIRE_TYPE, type.id);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ===== SIMPLE 線のたるみモード =====

    public static boolean readSag(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return false;
        CompoundTag tag = cd.copyTag();
        return tag.contains(KEY_WIRE_SAG) && tag.getBoolean(KEY_WIRE_SAG);
    }

    public static void writeSag(ItemStack stack, boolean sag) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putBoolean(KEY_WIRE_SAG, sag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ===== CUSTOM 線パラメータ =====

    public static float readCustomThickness(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return WireConnection.DEFAULT_CUSTOM_THICKNESS;
        CompoundTag tag = cd.copyTag();
        return tag.contains(KEY_CUSTOM_THICKNESS) ? tag.getFloat(KEY_CUSTOM_THICKNESS)
                : WireConnection.DEFAULT_CUSTOM_THICKNESS;
    }

    public static void writeCustomThickness(ItemStack stack, float v) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putFloat(KEY_CUSTOM_THICKNESS, v);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static float readCustomTrolleyOffset(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return WireConnection.DEFAULT_CUSTOM_TROLLEY_OFFSET;
        CompoundTag tag = cd.copyTag();
        return tag.contains(KEY_CUSTOM_TROLLEY_OFFSET) ? tag.getFloat(KEY_CUSTOM_TROLLEY_OFFSET)
                : WireConnection.DEFAULT_CUSTOM_TROLLEY_OFFSET;
    }

    public static void writeCustomTrolleyOffset(ItemStack stack, float v) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putFloat(KEY_CUSTOM_TROLLEY_OFFSET, v);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static float readCustomDropperInterval(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return WireConnection.DEFAULT_CUSTOM_DROPPER_INTERVAL;
        CompoundTag tag = cd.copyTag();
        return tag.contains(KEY_CUSTOM_DROPPER_INTERVAL) ? tag.getFloat(KEY_CUSTOM_DROPPER_INTERVAL)
                : WireConnection.DEFAULT_CUSTOM_DROPPER_INTERVAL;
    }

    public static void writeCustomDropperInterval(ItemStack stack, float v) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putFloat(KEY_CUSTOM_DROPPER_INTERVAL, v);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static int readCustomRowCount(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return WireConnection.DEFAULT_CUSTOM_ROW_COUNT;
        CompoundTag tag = cd.copyTag();
        return tag.contains(KEY_CUSTOM_ROW_COUNT) ? Math.max(1, Math.min(2, tag.getInt(KEY_CUSTOM_ROW_COUNT)))
                : WireConnection.DEFAULT_CUSTOM_ROW_COUNT;
    }

    public static void writeCustomRowCount(ItemStack stack, int v) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putInt(KEY_CUSTOM_ROW_COUNT, Math.max(1, Math.min(2, v)));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ===== カスタム架線プリセット =====
    private static final String KEY_PRESETS = "wirePresets";

    /** 1 プリセット = 名前 + 4 パラメータ (= CUSTOM タイプ専用)。 */
    public record Preset(String name, float thickness, float trolleyOffset,
                          float dropperInterval, int rowCount) {
        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("n", name);
            t.putFloat("t", thickness);
            t.putFloat("o", trolleyOffset);
            t.putFloat("d", dropperInterval);
            t.putByte("r", (byte) rowCount);
            return t;
        }
        public static Preset fromTag(CompoundTag t) {
            return new Preset(
                    t.getString("n"),
                    t.contains("t") ? t.getFloat("t") : WireConnection.DEFAULT_CUSTOM_THICKNESS,
                    t.contains("o") ? t.getFloat("o") : WireConnection.DEFAULT_CUSTOM_TROLLEY_OFFSET,
                    t.contains("d") ? t.getFloat("d") : WireConnection.DEFAULT_CUSTOM_DROPPER_INTERVAL,
                    t.contains("r") ? Math.max(1, Math.min(2, t.getByte("r"))) : WireConnection.DEFAULT_CUSTOM_ROW_COUNT);
        }
    }

    public static java.util.List<Preset> readPresets(ItemStack stack) {
        java.util.List<Preset> out = new java.util.ArrayList<>();
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return out;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(KEY_PRESETS)) return out;
        net.minecraft.nbt.ListTag list = tag.getList(KEY_PRESETS, net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            out.add(Preset.fromTag(list.getCompound(i)));
        }
        return out;
    }

    public static void writePresets(ItemStack stack, java.util.List<Preset> presets) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Preset p : presets) list.add(p.toTag());
        tag.put(KEY_PRESETS, list);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    // ===== pending pos の永続化 (= ItemStack の CustomData 経由) =====

    private static BlockPos readPending(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag tag = cd.copyTag();
        if (!tag.contains(KEY_PENDING_POS)) return null;
        return BlockPos.of(tag.getLong(KEY_PENDING_POS));
    }

    private static void writePending(ItemStack stack, BlockPos pos) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = cd != null ? cd.copyTag() : new CompoundTag();
        tag.putLong(KEY_PENDING_POS, pos.asLong());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearPending(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return;
        CompoundTag tag = cd.copyTag();
        tag.remove(KEY_PENDING_POS);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    private static void sendStatus(Player player, ChatFormatting color, MutableComponent msg) {
        player.displayClientMessage(msg.withStyle(color), true);
    }
}
