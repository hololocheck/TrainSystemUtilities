package com.trainsystemutilities.electrification.contraption;

import belugalab.mcss3.util.concurrent.MutableIntCell;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 各 contraption (= からくり列車) の電化状態を保持するレジストリ。
 *
 * <p>D2: 組立時に Create の {@code MovementBehaviour.startMoving} 経由で
 * インバータ / パンタグラフブロックが自分自身を登録。両方が揃った contraption が
 * 「電化列車」として扱われる。
 *
 * <p>{@link WeakHashMap} を使うことで Create 側の Contraption 解放時に自動で entry が GC される。
 */
public final class ContraptionElectrificationState {

    public static final class Info {
        /** contraption 内ローカル座標で登録された FE インバータ位置 (= 機能版)。 */
        public final Set<BlockPos> inverters = ConcurrentHashMap.newKeySet();
        /** contraption 内ローカル座標で登録された装飾版 FE インバータ位置。
         *  機能版と違い FE バッファとして動作せず、UI 上で「インバータあり」扱いするためだけに使う。
         *  これによりパンタ + 装飾インバータの構成でも管理 UI からパンタ展開/折畳が可能になる。 */
        public final Set<BlockPos> dummyInverters = ConcurrentHashMap.newKeySet();
        /** contraption 内ローカル座標で登録されたパンタグラフ位置。 */
        public final Set<BlockPos> pantographs = ConcurrentHashMap.newKeySet();
        /** 現在「展開中」のパンタ位置。S1: assemble 直後は空 (= 全パンタ折りたたみ)。
         *  管理 GUI または同期 payload でトグル。集電は deployed 時のみ有効。 */
        public final Set<BlockPos> deployedPantographs = ConcurrentHashMap.newKeySet();
        /** S2: パンタごとの「集電バー Y オフセット」(ワールド座標、メートル単位)。
         *  正値=バーが架線に押されて下がっている (= 接触あり)、0=非接触で既定位置。
         *  GUI の可視化およびモデル側のボーン操作 (将来) で参照。 */
        public final ConcurrentMap<BlockPos, Float> pantographBarOffsetY = new ConcurrentHashMap<>();
        /** S2: パンタごとの個別接触フラグ。 */
        public final Set<BlockPos> contactingPantographs = ConcurrentHashMap.newKeySet();

        public boolean isPantographDeployed(BlockPos localPos) {
            return localPos != null && deployedPantographs.contains(localPos);
        }

        public boolean isPantographInContact(BlockPos localPos) {
            return localPos != null && contactingPantographs.contains(localPos);
        }

        public float getPantographBarOffsetY(BlockPos localPos) {
            if (localPos == null) return 0f;
            Float f = pantographBarOffsetY.get(localPos);
            return f == null ? 0f : f;
        }

        /** S2: パンタの接触状態を更新。barOffset は wire の Y - 既定の集電バー Y。 */
        public void updatePantographContact(BlockPos localPos, boolean contacting, float barOffset) {
            if (localPos == null) return;
            if (contacting) {
                contactingPantographs.add(localPos);
                pantographBarOffsetY.put(localPos, barOffset);
            } else {
                contactingPantographs.remove(localPos);
                pantographBarOffsetY.remove(localPos);
            }
        }

        /** パンタの展開状態を切替。戻り値は反転後の状態。 */
        public boolean togglePantograph(BlockPos localPos) {
            if (localPos == null || !pantographs.contains(localPos)) return false;
            if (deployedPantographs.contains(localPos)) {
                deployedPantographs.remove(localPos);
                return false;
            }
            deployedPantographs.add(localPos);
            return true;
        }

        /** 全パンタを一括展開/折畳。 */
        public void setAllPantographsDeployed(boolean deploy) {
            if (deploy) deployedPantographs.addAll(pantographs);
            else deployedPantographs.clear();
        }

        /** 車載 FE バッファ容量 (= 50k FE)。 */
        public static final int CAPACITY = 50_000;
        /** 車載 FE 残量 (atomic int セル)。 Pantograph tick で増え、 Inverter tick で減る。
         *  ※ 直接 mutation 不可。 {@link #receive}/{@link #drain}/{@link #setStoredEnergy}/
         *  {@link #bindToCarriage} を介してのみ更新する。 P0-1 #5 で
         *  HOTFIX 時の {@code synchronized(this)} guard を atomic CAS に升格 (lock-free)。 */
        private final MutableIntCell storedEnergy = new MutableIntCell(0);

        /** Read accessor — render / HUD / sync で頻繁に読まれる hot path。 */
        public int getStoredEnergy() {
            return storedEnergy.get();
        }

        /** scanContraption / 旧 NBT load 用の seed setter。 通常は使わない (= receive/drain 推奨)。 */
        void setStoredEnergy(int value) {
            storedEnergy.set(Math.max(0, Math.min(CAPACITY, value)));
        }
        /** 直近 tick でいずれかのパンタが架線にコンタクトしていたか。 */
        public volatile boolean inContact = false;

        /** (trainId, carriageIndex) に対応する永続キー。0 なら未バインド (= legacy 動作)。
         *  Contraption インスタンスは entity unload/reload で再生成されるため、
         *  Info を Contraption で keying すると storedEnergy が毎回 0 にリセットされる。
         *  これを防ぐため (trainId, carriageIndex) ペアの永続キーで PERSISTENT_ENERGY map と
         *  紐付ける。 */
        private volatile long persistentKey = 0L;

        public boolean isElectrified() {
            return !inverters.isEmpty() && !pantographs.isEmpty();
        }

        /** UI ゲート判定: 機能版 / 装飾版いずれかのインバータがあるか。 */
        public boolean hasAnyInverterType() {
            return !inverters.isEmpty() || !dummyInverters.isEmpty();
        }

        /** インバータが駆動可能か = FE > 0。 */
        public boolean canDrive() {
            return storedEnergy.get() > 0;
        }

        /** {@link #PERSISTENT_ENERGY} から storedEnergy を復元し、以降の変化を map に書き戻す。
         *  通常 {@link ContraptionElectrificationState#getOrComputeInfo} 内で 1 度だけ呼ばれる。 */
        public void bindToCarriage(long key) {
            this.persistentKey = key;
            Integer stored = PERSISTENT_ENERGY.get(key);
            if (stored != null) setStoredEnergy(stored);
        }

        public int receive(int amount) {
            // P0-1 #5: addSaturating で atomic CAS。 lost update を lock-free に排除。
            // accepted は実際に受入できた量 (0 〜 amount)。 capacity 飽和なら 0。
            if (amount <= 0) return 0;
            int accepted = storedEnergy.addSaturating(amount, 0, CAPACITY);
            if (accepted > 0 && persistentKey != 0L) {
                PERSISTENT_ENERGY.put(persistentKey, storedEnergy.get());
            }
            return accepted;
        }

        public int drain(int amount) {
            if (amount <= 0) return 0;
            // addSaturating(-amount) は applied (= 負の delta) を返す。 |applied| が taken。
            int applied = storedEnergy.addSaturating(-amount, 0, CAPACITY);
            int taken = -applied;
            if (taken > 0 && persistentKey != 0L) {
                PERSISTENT_ENERGY.put(persistentKey, storedEnergy.get());
            }
            return taken;
        }

        /** Canonical first inverter = 同 contraption 内で {@code pos.asLong()} が最小のもの。
         *  persist 経路と load 経路で「同じ inverter」を参照することを保証し、
         *  Math.max-across-inverters による duplication と
         *  {@code iterator().next()} の non-deterministic 順序を排除する (= HOTFIX N+0.5 #1)。 */
        public BlockPos firstInverterPos() {
            BlockPos best = null;
            for (BlockPos p : inverters) {
                if (best == null || p.asLong() < best.asLong()) best = p;
            }
            return best;
        }
    }

    /** (trainId, carriageIndex) → storedEnergy の永続マップ。
     *  Contraption インスタンスの再生成 (= entity unload/reload) にまたがって FE を保持する。
     *  Train が解体された場合は {@link #pruneStaleEntries} が定期的に GC する。
     *  ※ サーバ再起動で in-memory state は失われるため、将来 SavedData 化を検討。 */
    private static final Map<Long, Integer> PERSISTENT_ENERGY = new ConcurrentHashMap<>();

    /** Train UUID と carriageIndex から安定した 64bit キーを生成。
     *  UUID 衝突は理論的に存在するが、UUID 空間 + carriageIndex (~10) で実用上問題なし。 */
    public static long carriageKey(UUID trainId, int carriageIndex) {
        if (trainId == null) return 0L;
        long a = trainId.getMostSignificantBits();
        long b = trainId.getLeastSignificantBits();
        // FNV っぽい混合 + carriageIndex 折込
        return (a ^ (b * 0x9E3779B97F4A7C15L)) + ((long) carriageIndex * 0xBF58476D1CE4E5B9L);
    }

    /** Contraption から (trainId, carriageIndex) キーを抽出。Carriage 由来の Contraption
     *  でなければ 0 を返す (= 通常の Create 列車以外、または entity 未ロード時)。 */
    public static long carriageKeyOf(Contraption c) {
        if (c == null || c.entity == null) return 0L;
        if (c.entity instanceof CarriageContraptionEntity cce) {
            return carriageKey(cce.trainId, cce.carriageIndex);
        }
        return 0L;
    }

    /** 有効な全 (trainId, carriageIndex) ペアから期待される key set を作成し、
     *  PERSISTENT_ENERGY から含まれない key を除去する。
     *  ContraptionElectrificationTicker から定期的 (~10 秒) に呼ばれる想定。 */
    public static void pruneToActiveKeys(Set<Long> activeKeys) {
        if (PERSISTENT_ENERGY.isEmpty()) return;
        PERSISTENT_ENERGY.keySet().retainAll(activeKeys);
    }

    /** デバッグ用: 現在の PERSISTENT_ENERGY エントリ数。 */
    public static int persistentEnergyCount() { return PERSISTENT_ENERGY.size(); }

    /** P0-5 #2: SavedData → 起動時 hydrate。 caller は {@link com.trainsystemutilities.electrification.TsuPersistentEnergyData}
     *  から {@link Map#entrySet()} を渡す。 既存 in-memory entry は保持しつつ map 内容を上書き。 */
    public static void hydrateFromSavedData(java.util.Map<Long, Integer> loadedEntries) {
        if (loadedEntries == null || loadedEntries.isEmpty()) return;
        PERSISTENT_ENERGY.putAll(loadedEntries);
    }

    /** P0-5 #2: SavedData ← shutdown / 定期 save 時の snapshot 取得。 caller は
     *  {@link com.trainsystemutilities.electrification.TsuPersistentEnergyData} に流す。 */
    public static java.util.Map<Long, Integer> persistentEnergySnapshot() {
        return new java.util.HashMap<>(PERSISTENT_ENERGY);
    }

    private static final Map<Contraption, Info> STATE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ContraptionElectrificationState() {}

    public static void registerInverter(Contraption c, BlockPos localPos) {
        if (c == null || localPos == null) return;
        getOrCreate(c).inverters.add(localPos.immutable());
    }

    public static void unregisterInverter(Contraption c, BlockPos localPos) {
        if (c == null || localPos == null) return;
        Info info = STATE.get(c);
        if (info == null) return;
        info.inverters.remove(localPos);
        cleanupIfEmpty(c, info);
    }

    /** 装飾版インバータの登録。{@link #registerInverter} と違い FE 集電/維持の対象外。 */
    public static void registerDummyInverter(Contraption c, BlockPos localPos) {
        if (c == null || localPos == null) return;
        getOrCreate(c).dummyInverters.add(localPos.immutable());
    }

    public static void unregisterDummyInverter(Contraption c, BlockPos localPos) {
        if (c == null || localPos == null) return;
        Info info = STATE.get(c);
        if (info == null) return;
        info.dummyInverters.remove(localPos);
        cleanupIfEmpty(c, info);
    }

    public static void registerPantograph(Contraption c, BlockPos localPos) {
        if (c == null || localPos == null) return;
        getOrCreate(c).pantographs.add(localPos.immutable());
    }

    public static void unregisterPantograph(Contraption c, BlockPos localPos) {
        if (c == null || localPos == null) return;
        Info info = STATE.get(c);
        if (info == null) return;
        info.pantographs.remove(localPos);
        info.deployedPantographs.remove(localPos);
        info.contactingPantographs.remove(localPos);
        info.pantographBarOffsetY.remove(localPos);
        cleanupIfEmpty(c, info);
    }

    public static boolean isElectrified(Contraption c) {
        Info info = getOrComputeInfo(c);
        return info != null && info.isElectrified();
    }

    public static Info getInfo(Contraption c) {
        return getOrComputeInfo(c);
    }

    /**
     * Contraption の Info を取得。STATE になければ contraption の blocks を走査して構築。
     *
     * <p>World reload や Contraption インスタンス再生成で startMoving が再呼び出しされない
     * ケースに対応する。スキャン結果は STATE にキャッシュされる。
     *
     * <p>BE NBT の {@code Deployed} フィールドを読み取って初期 {@code deployedPantographs}
     * を復元するため、組立前にパンタを折りたたんで保存していた状態は維持される。
     */
    public static Info getOrComputeInfo(Contraption c) {
        if (c == null) return null;
        Info existing = STATE.get(c);
        if (existing != null) return existing;
        Info info = scanContraption(c);
        // (trainId, carriageIndex) で永続 storedEnergy を復元
        // Contraption が unload→reload で再生成されても FE がリセットされない。
        long key = carriageKeyOf(c);
        if (key != 0L) info.bindToCarriage(key);
        synchronized (STATE) {
            // 念のため二重チェック
            Info other = STATE.get(c);
            if (other != null) return other;
            STATE.put(c, info);
        }
        return info;
    }

    private static Info scanContraption(Contraption c) {
        Info info = new Info();
        Block inverterBlock = ModBlocks.FE_INVERTER.get();
        Block dummyInverterBlock = ModBlocks.FE_INVERTER_DUMMY.get();
        Block pantographBlock = ModBlocks.PANTOGRAPH.get();
        // HOTFIX N+0.5 #1: 旧コードは Math.max-across-inverters で seed していたが、
        // persist 経路 ({@link #persistEnergyToInverterNbt}) は 1 つの inverter NBT にしか
        // 書き戻さないため、 secondary inverter NBT に過去の高値が残ると Math.max が
        // それを採用して duplication 発生。 canonical first (= pos.asLong 最小) のみから
        // seed することで persist/load の対称性を確保。
        BlockPos firstInverterPos = null;
        int firstInverterEnergy = 0;
        try {
            for (var entry : c.getBlocks().entrySet()) {
                BlockPos pos = entry.getKey();
                var blockInfo = entry.getValue();
                if (blockInfo == null || blockInfo.state() == null) continue;
                Block block = blockInfo.state().getBlock();
                if (block == inverterBlock) {
                    BlockPos immut = pos.immutable();
                    info.inverters.add(immut);
                    if (firstInverterPos == null || immut.asLong() < firstInverterPos.asLong()) {
                        firstInverterPos = immut;
                        var nbt = blockInfo.nbt();
                        firstInverterEnergy = (nbt != null && nbt.contains("Energy"))
                                ? Math.max(0, Math.min(Info.CAPACITY, nbt.getInt("Energy"))) : 0;
                    }
                } else if (block == dummyInverterBlock) {
                    info.dummyInverters.add(pos.immutable());
                } else if (block == pantographBlock) {
                    info.pantographs.add(pos.immutable());
                    // BE NBT の Deployed フィールドから初期状態を復元
                    var nbt = blockInfo.nbt();
                    if (nbt != null && nbt.contains("Deployed") && nbt.getBoolean("Deployed")) {
                        info.deployedPantographs.add(pos.immutable());
                    }
                }
            }
        } catch (Exception ex) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Electrify] contraption scan failed", ex);
            // 走査失敗時は空 Info を返す (= 電化なしとして扱う)
        }
        info.setStoredEnergy(firstInverterEnergy);
        return info;
    }

    /** Contraption の最初のインバータ BE NBT に storedEnergy を書き戻す。
     *  サーバ再起動を超えた永続化のため (Create が contraption NBT を保存するので)。
     *  ※ {@link #PERSISTENT_ENERGY} は in-memory で再起動時には失われるためのフォールバック。 */
    public static void persistEnergyToInverterNbt(Contraption c, int energy) {
        if (c == null) return;
        Info info = STATE.get(c);
        if (info == null) return;
        // HOTFIX N+0.5 #1: 旧コードは ConcurrentHashMap.newKeySet の iterator().next()
        // で非決定的 order だったが、 canonical first (= pos.asLong 最小) に統一して
        // load 経路 (scanContraption) と対称化。
        BlockPos invPos = info.firstInverterPos();
        if (invPos == null) return;
        var sbi = c.getBlocks().get(invPos);
        if (sbi != null && sbi.nbt() != null) {
            sbi.nbt().putInt("Energy", energy);
        }
    }

    public static int trackedCount() {
        return STATE.size();
    }

    /** WeakHashMap の安全なスナップショット (= イテレート用)。 */
    public static Map<Contraption, Info> snapshot() {
        synchronized (STATE) {
            return new HashMap<>(STATE);
        }
    }

    private static Info getOrCreate(Contraption c) {
        synchronized (STATE) {
            return STATE.computeIfAbsent(c, k -> {
                Info i = new Info();
                long key = carriageKeyOf(k);
                if (key != 0L) i.bindToCarriage(key);
                return i;
            });
        }
    }

    private static void cleanupIfEmpty(Contraption c, Info info) {
        if (info.inverters.isEmpty() && info.dummyInverters.isEmpty() && info.pantographs.isEmpty()) {
            STATE.remove(c);
        }
    }
}
