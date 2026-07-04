package com.trainsystemutilities.schedule;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.trainsystemutilities.TrainSystemUtilities;
import net.createmod.catnip.data.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 連結・切り離しのスケジュール条件。
 * Createの時刻表の条件エディタに「連結」「切り離し」モードとして表示される。
 */
public class CouplingCondition extends ScheduleWaitCondition {

    public CouplingCondition() {
        super();
        data.putInt("Mode", 0); // 0=連結, 1=切り離し
        data.putInt("WaitTime", 5); // 切り離し後の待機時間（秒）デフォルト5秒
    }

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "coupling");
    }

    @Override
    public Pair<ItemStack, Component> getSummary() {
        return Pair.of(getIcon(), getModeText());
    }

    @Override
    public ItemStack getSecondLineIcon() {
        return getIcon();
    }

    @Override
    public List<Component> getTitleAs(String type) {
        return ImmutableList.of(
                Component.literal(getModeText().getString())
                        .withStyle(ChatFormatting.GOLD)
        );
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initConfigurationWidgets(ModularGuiLineBuilder builder) {
        builder.addSelectionScrollInput(0, 70, (si, l) -> {
            si.forOptions(List.of(
                    Component.translatable("tsu.coupling.couple_label"),
                    Component.translatable("tsu.coupling.decouple_label")
            )).titled(Component.translatable("tsu.coupling.mode_field_title"));
        }, "Mode");

        // 切り離し後の待機時間（秒）
        builder.addSelectionScrollInput(72, 50, (si, l) -> {
            List<Component> options = new java.util.ArrayList<>();
            for (int s = 1; s <= 30; s++) {
                options.add(Component.translatable("tsu.mc.time_unit_sec_fmt", s));
            }
            si.forOptions(options).titled(Component.translatable("tsu.coupling.wait_time_field_title"));
        }, "WaitTime");
    }

    @Override
    public List<Component> getSecondLineTooltip(int slot) {
        return ImmutableList.of(
                Component.translatable("tsu.coupling.mode_select_title"),
                Component.translatable("tsu.coupling.couple_desc").withStyle(ChatFormatting.GRAY),
                Component.translatable("tsu.coupling.decouple_desc").withStyle(ChatFormatting.GRAY)
        );
    }

    @Override
    public boolean tickCompletion(Level level, Train train, CompoundTag context) {
        if (level.getGameTime() % 20 == 0) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[CouplingCondition] tickCompletion called: train=" + train.name.getString()
                    + " mode=" + (isCouple() ? "COUPLE" : "DECOUPLE")
                    + " atStation=" + (train.getCurrentStation() != null ? train.getCurrentStation().name : "null"));
        }
        if (isCouple()) {
            return tickCoupleCompletion(level, train, context);
        } else {
            return tickDecoupleCompletion(level, train, context);
        }
    }

    /**
     * 連結モードのtick:
     * 1. 駅に停車していなければ待機
     * 2. 駅に停車したら連結待ちを登録
     * 3. 相手列車が到着するまで待機
     * 4. 相手が到着したら連結実行→完了
     */
    private boolean tickCoupleCompletion(Level level, Train train, CompoundTag context) {
        var station = train.getCurrentStation();
        if (station == null) {
            // 駅から離れた → 登録解除
            TrainCouplingManager.completeCoupling(train.id);
            return false;
        }

        String stationName = station.name;

        // 毎回: 連結待ちが登録されていなければ登録（再起動後も対応）
        if (!TrainCouplingManager.isStationWaitingForCoupling(stationName)) {
            TrainCouplingManager.registerCouplingWait(train.id, stationName);
            requestStatusToUpdate(context);
        }

        // 連結完了チェック
        if (TrainCouplingManager.checkPartnerArrived(train.id, stationName)) {
            TrainCouplingManager.completeCoupling(train.id);
            return true;
        }

        return false;
    }

    /**
     * 切り離しモードのtick:
     * 1. 駅に停車していなければ待機
     * 2. 駅に停車したら切り離し実行
     * 3. 完了したら次へ
     */
    private boolean tickDecoupleCompletion(Level level, Train train, CompoundTag context) {
        var station = train.getCurrentStation();
        if (station == null) return false;

        String stationName = station.name;

        if (!context.getBoolean("DecouplingRegistered")) {
            int waitSeconds = getWaitTimeSeconds();
            TrainCouplingManager.registerDecouplingOrder(train.id, stationName, waitSeconds);
            context.putBoolean("DecouplingRegistered", true);
            context.putString("StationName", stationName);
            requestStatusToUpdate(context);
        }

        var order = TrainCouplingManager.getDecouplingOrder(train.id);
        if (order != null && order.phase() == TrainCouplingManager.DecouplingPhase.COMPLETED) {
            context.remove("DecouplingRegistered");
            context.remove("StationName");
            return true;
        }

        return false;
    }

    @Override
    public MutableComponent getWaitingStatus(Level level, Train train, CompoundTag tag) {
        if (isCouple()) {
            var phase = TrainCouplingManager.getCouplingPhase(train.id);
            if (phase != null) {
                return switch (phase) {
                    case WAITING_AT_STATION -> Component.translatable("tsu.coupling.state_wait_partner");
                    case PARTNER_APPROACHING -> Component.translatable("tsu.coupling.state_partner_approaching");
                    case COUPLING_EXECUTING -> Component.translatable("tsu.coupling.state_coupling");
                    case COMPLETED -> Component.translatable("tsu.coupling.state_couple_done");
                };
            }
            return Component.translatable("tsu.coupling.title_couple_waiting");
        } else {
            var order = TrainCouplingManager.getDecouplingOrder(train.id);
            if (order != null) {
                return switch (order.phase()) {
                    case ARRIVING -> Component.translatable("tsu.coupling.state_arriving_decouple_station");
                    case DECOUPLING -> Component.translatable("tsu.coupling.state_decoupling");
                    case FRONT_DEPARTING -> Component.translatable("tsu.coupling.state_front_departing");
                    case WAITING_SIGNAL -> Component.translatable("tsu.coupling.state_signal_wait");
                    case REAR_STARTING -> Component.translatable("tsu.coupling.state_rear_departing");
                    case COMPLETED -> Component.translatable("tsu.coupling.state_decouple_done");
                };
            }
            return Component.translatable("tsu.coupling.title_decouple_progress");
        }
    }

    // ===== ヘルパー =====

    public int getMode() {
        return intData("Mode");
    }

    public boolean isCouple() {
        return getMode() == 0;
    }

    public boolean isDecouple() {
        return getMode() == 1;
    }

    public int getWaitTimeSeconds() {
        int val = intData("WaitTime");
        return val <= 0 ? 5 : val + 1; // ScrollInputは0始まりなので+1
    }

    private MutableComponent getModeText() {
        return isCouple()
                ? Component.translatable("tsu.coupling.couple_label")
                : Component.translatable("tsu.coupling.decouple_label");
    }

    private ItemStack getIcon() {
        return isCouple()
                ? new ItemStack(Items.CHAIN)
                : new ItemStack(Items.SHEARS);
    }
}
