package com.trainsystemutilities.electrification.contraption;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 列車の電化状態スナップショット (S2C で送る共通型)。
 *
 * <p>1 列車につき 1 つ。{@code cars} は車両単位の詳細 (= carriage インデックス順)。
 * GUI 表示用なので最小限の情報だけ含む。
 */
public final class TrainElectrificationView {

    public final UUID trainId;
    public final String trainName;
    public final List<Car> cars;

    public TrainElectrificationView(UUID trainId, String trainName, List<Car> cars) {
        this.trainId = trainId;
        this.trainName = trainName;
        this.cars = cars;
    }

    /** 機能版または装飾版のインバータがどこかの車両にあるか。
     *  UI ゲート (= 電化情報パネル表示) に使われる。 */
    public boolean hasAnyInverter() {
        for (Car c : cars) if (c.hasInverter || c.dummyInverterCount > 0) return true;
        return false;
    }

    /** 機能版インバータ (FE バッファあり) がどこかの車両にあるか。
     *  装飾版は除外。FE 集電/駆動判定ロジックで参照。 */
    public boolean hasAnyRealInverter() {
        for (Car c : cars) if (c.hasInverter) return true;
        return false;
    }

    public boolean hasAnyPantograph() {
        for (Car c : cars) if (!c.pantographs.isEmpty()) return true;
        return false;
    }

    /** 列車単位の電化判定: パンタとインバータ (実 / 装飾どちらでも) の両方を含む列車。
     *  装飾版でも UI からパンタ操作できるよう、ゲートは緩い側で判定する。 */
    public boolean isElectrifiedTrain() {
        return hasAnyPantograph() && hasAnyInverter();
    }

    /** 機能版だけで成立する電化列車。FE 集電ロジックの判定用。 */
    public boolean isFullyElectrifiedTrain() {
        return hasAnyPantograph() && hasAnyRealInverter();
    }

    public int totalPantographs() {
        int n = 0;
        for (Car c : cars) n += c.pantographs.size();
        return n;
    }

    public int totalDeployedPantographs() {
        int n = 0;
        for (Car c : cars) for (PantoEntry p : c.pantographs) if (p.deployed) n++;
        return n;
    }

    /** 1 車両 (= 1 carriage) の電化情報。 */
    public static final class Car {
        public final int carriageIndex;
        /** 機能版 FE インバータあり (= FE バッファ動作する)。 */
        public final boolean hasInverter;
        /** 機能版インバータ数。 */
        public final int inverterCount;
        /** 装飾版 FE インバータ数 (FE 機能なし、UI 表示のためだけに数える)。 */
        public final int dummyInverterCount;
        public final int storedEnergy;
        public final int capacity;
        public final boolean inContact;
        public final List<PantoEntry> pantographs;

        public Car(int carriageIndex, boolean hasInverter, int inverterCount,
                    int dummyInverterCount, int storedEnergy, int capacity,
                    boolean inContact, List<PantoEntry> pantographs) {
            this.carriageIndex = carriageIndex;
            this.hasInverter = hasInverter;
            this.inverterCount = inverterCount;
            this.dummyInverterCount = dummyInverterCount;
            this.storedEnergy = storedEnergy;
            this.capacity = capacity;
            this.inContact = inContact;
            this.pantographs = pantographs;
        }

        /** 旧 7-arg シグネチャ互換: dummyInverterCount=0 で委譲。 */
        public Car(int carriageIndex, boolean hasInverter, int inverterCount,
                    int storedEnergy, int capacity, boolean inContact,
                    List<PantoEntry> pantographs) {
            this(carriageIndex, hasInverter, inverterCount, 0,
                    storedEnergy, capacity, inContact, pantographs);
        }

        /** 旧 6-arg シグネチャ互換。 */
        public Car(int carriageIndex, boolean hasInverter, int storedEnergy, int capacity,
                    boolean inContact, List<PantoEntry> pantographs) {
            this(carriageIndex, hasInverter, hasInverter ? 1 : 0, 0,
                    storedEnergy, capacity, inContact, pantographs);
        }

        /** UI 上「インバータ系統あり」と扱える車両か。 */
        public boolean hasAnyInverter() {
            return hasInverter || dummyInverterCount > 0;
        }

        public static Car empty(int idx) {
            return new Car(idx, false, 0, 0, 0, 0, false, new ArrayList<>());
        }
    }

    /** パンタグラフ 1 基の状態。{@code pos} は contraption ローカル座標。 */
    public static final class PantoEntry {
        public final BlockPos pos;
        public final boolean deployed;
        /** S2: 個別接触フラグ。 */
        public final boolean inContact;
        /** S2: バー Y オフセット (= 架線によってバーが押される量、メートル単位)。 */
        public final float barOffsetY;

        public PantoEntry(BlockPos pos, boolean deployed) {
            this(pos, deployed, false, 0f);
        }

        public PantoEntry(BlockPos pos, boolean deployed, boolean inContact, float barOffsetY) {
            this.pos = pos;
            this.deployed = deployed;
            this.inContact = inContact;
            this.barOffsetY = barOffsetY;
        }
    }
}
