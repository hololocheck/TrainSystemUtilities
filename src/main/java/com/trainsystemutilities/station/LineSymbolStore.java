package com.trainsystemutilities.station;

import com.trainsystemutilities.blockentity.LineSymbol;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 駅 → 割り当て路線記号 の権威マップ (= level data、 常時メモリ常駐)。
 *
 * <p>従来は管理用コンピューター ({@code ManagementComputerBlockEntity}) が block entity 間 push で
 * {@code RailwayManagementBlockEntity} に路線記号を配っていたが、 遠隔駅の chunk が unload されていると
 * {@code level.getBlockEntity()} が null を返して push が届かず、 「離れた駅のモニターに反映されない」
 * バグの原因になっていた。
 *
 * <p>本 store は chunk load に依存しない。 管理用コンピューターは割り当て / 記号編集のたびに
 * ここへ全駅ぶんを書き込み、 各 {@code RailwayManagementBlockEntity} は tick で自駅キーを引いて
 * 解決する (= computer が unload 中でも解決できる)。 server-side のみ。 overworld data storage に常駐。
 *
 * <p>格納する {@link LineSymbol} は必ず snapshot copy ({@code LineSymbol.load(symbol.save())})。
 * 記号編集は既存インスタンスを in-place で書き換えるため、 参照を共有すると変更検知 (setDirty /
 * RMBE 側の差分判定) がすり抜ける。
 *
 * <p><b>非公開の駅 (2026-10-01 ユーザー判断 ⑲)。</b> 非公開の {@code RailwayManagementBlockEntity} は、 読み込まれている間
 * 自分の駅のキーに持ち主を {@link #setGuard 登録}する。 登録のある駅の記号は、 その持ち主の名でしか変えられない — 書き込みの
 * 時点で断るので、 ブロック・乗換ターミナル・駅名サインのどの読み手にも他人の記号は届かない。 管理用コンピューターがブロックを
 * 見つけられない駅 (chunk が未ロード) でも効く。 書き込みは割り当てた player の名で来る (カードを抜いたときの取り下げも)。
 * 名前の無い書き込みは 2026-10-01 より前の割り当てで、 名前のある記号には触れない。
 */
public class LineSymbolStore extends SavedData {

    private static final String FILE_NAME = "tsu_line_symbols";

    /** stationKey (= {@code ManagementComputerBlockEntity.stationKey(name,pos)}) → 割り当てシンボル。 */
    private final Map<String, LineSymbol> byStation = new HashMap<>();

    /** stationKey → その割り当てを書いた player。 無い = システムか、 2026-10-01 より前の書き込み (認めたものとして扱う)。 */
    private final Map<String, UUID> actors = new HashMap<>();

    /** stationKey → その駅を守る非公開の鉄道管理ブロック (持ち主と、 どのブロックか)。 */
    private final Map<String, Guard> guards = new HashMap<>();

    /** 駅を守る非公開ブロックの登録。 {@code holder} はそのブロックの dimension と位置 (別のブロックの登録を消さないため)。 */
    private record Guard(UUID owner, String holder) {}

    /** 割り当てシンボルを取得。 未割り当てなら null。 返り値は store 内の snapshot (読み取り専用として扱う)。 */
    public LineSymbol getSymbol(String stationKey) {
        return stationKey == null ? null : byStation.get(stationKey);
    }

    /** 全割り当ての読み取り専用ビュー (stationKey → symbol)。 駅名サインが「同一路線の隣の番号」を
     *  逆引きするのに使う。 chunk load に依存せず全駅を見られるのはこの store だけ。 */
    public Map<String, LineSymbol> entries() {
        return java.util.Collections.unmodifiableMap(byStation);
    }

    /** 誰のものか分からない割り当て (symbol==null で解除)。 名前のある記号は変えない ({@link #setSymbol(String, LineSymbol, UUID)})。 */
    public void setSymbol(String stationKey, LineSymbol symbol) {
        setSymbol(stationKey, symbol, null);
    }

    /**
     * {@code actor} の割り当て (symbol==null で解除)。 駅を守る非公開ブロックの持ち主でない player なら何も変えず false。
     * {@code actor} が null の書き込み (誰のものか分からない — 2026-10-01 より前の割り当て) は、 名前のある記号を置き換えも
     * 消しもしない: 古い割り当てを持つコンピューターは 200 tick ごとに公開し直すので、 置き換えられると持ち主が後から入れた
     * 記号を他人の古い割り当てが上書きし続ける (二次読みの指摘)。 値か書いた player が変わったときだけ dirty 化する。
     */
    public boolean setSymbol(String stationKey, LineSymbol symbol, @Nullable UUID actor) {
        if (stationKey == null || stationKey.isEmpty()) return true;
        if (!admits(stationKey, actor)) return false;
        if (actor == null && actors.containsKey(stationKey)) return false;
        if (symbol == null) {
            if (byStation.remove(stationKey) != null) {
                actors.remove(stationKey);
                setDirty();
            }
            return true;
        }
        CompoundTag now = symbol.save();
        LineSymbol prev = byStation.get(stationKey);
        if (prev == null || !prev.save().equals(now) || !Objects.equals(actors.get(stationKey), actor)) {
            byStation.put(stationKey, LineSymbol.load(now)); // snapshot copy
            if (actor == null) actors.remove(stationKey);
            else actors.put(stationKey, actor);
            setDirty();
        }
        return true;
    }

    /**
     * 駅の守りとして {@code actor} を通すか: 守られていない駅、 名前の無い書き込み (null — 旧い割り当て。 名前のある記号に
     * 触れないことは {@link #setSymbol(String, LineSymbol, UUID)} が別に守る)、 または守るブロックの持ち主。
     */
    public boolean admits(String stationKey, @Nullable UUID actor) {
        Guard guard = stationKey == null ? null : guards.get(stationKey);
        return guard == null || actor == null || guard.owner().equals(actor);
    }

    /** この駅を守る非公開ブロックの持ち主。 守られていなければ null。 */
    @Nullable
    public UUID guardOf(String stationKey) {
        Guard guard = stationKey == null ? null : guards.get(stationKey);
        return guard == null ? null : guard.owner();
    }

    /**
     * 鉄道管理ブロック自身の登録: 非公開で持ち主のいる間 {@code owner}、 それ以外 (公開・持ち主なし・リンク解除・破壊) は null。
     * null で消すのは同じブロック ({@code holder}) の登録だけ。 新しく登録したとき、 持ち主以外の player が書いた割り当ては
     * 外す — 登録の無い間 (このブロックが更新後まだ読み込まれていなかった) に入ったもの。 持ち主・システム・旧版の書き込みは残す
     * (CLAUDE.md 5.3: 旧データは認めたものとして扱う)。
     */
    public void setGuard(String stationKey, @Nullable UUID owner, String holder) {
        if (stationKey == null || stationKey.isEmpty()) return;
        Guard current = guards.get(stationKey);
        if (owner == null) {
            if (current != null && current.holder().equals(holder)) {
                guards.remove(stationKey);
                setDirty();
            }
            return;
        }
        // 先に登録したブロックが、 手放すまで守る。 同じ駅に別のブロック (別の持ち主のものも同じ持ち主のものも) が来ても
        // 奪わない — 奪えると 2 つが毎 tick 登録を取り合い、 そのたびに相手の記号を外す (二次読みの指摘: 駅へのリンクは
        // 誰でもできるので、 他人が自分の非公開ブロックを置けば持ち主の割り当てを壊せた)。 同じブロックで持ち主が変わった
        // ときだけ持ち主を書き換える (記号は外さない)。
        if (current != null) {
            if (current.holder().equals(holder) && !current.owner().equals(owner)) {
                guards.put(stationKey, new Guard(owner, holder));
                setDirty();
            }
            return;
        }
        guards.put(stationKey, new Guard(owner, holder));
        UUID writer = actors.get(stationKey);
        if (writer != null && !writer.equals(owner)) {
            byStation.remove(stationKey);
            actors.remove(stationKey);
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var e : byStation.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Key", e.getKey());
            entry.put("Symbol", e.getValue().save());
            UUID actor = actors.get(e.getKey());
            if (actor != null) entry.putUUID("Actor", actor);
            list.add(entry);
        }
        tag.put("Assignments", list);
        ListTag guardList = new ListTag();
        for (var e : guards.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Key", e.getKey());
            entry.putUUID("Owner", e.getValue().owner());
            entry.putString("Holder", e.getValue().holder());
            guardList.add(entry);
        }
        tag.put("Guards", guardList);
        return tag;
    }

    public static LineSymbolStore load(CompoundTag tag, HolderLookup.Provider registries) {
        LineSymbolStore d = new LineSymbolStore();
        ListTag list = tag.getList("Assignments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String key = entry.getString("Key");
            LineSymbol sym = LineSymbol.load(entry.getCompound("Symbol"));
            if (!key.isEmpty() && sym != null) {
                d.byStation.put(key, sym);
                if (entry.hasUUID("Actor")) d.actors.put(key, entry.getUUID("Actor"));
            }
        }
        // 2026-10-01 より前の data には無い: 守られた駅は無く、 各ブロックが読み込まれた時に登録し直す。
        ListTag guardList = tag.getList("Guards", Tag.TAG_COMPOUND);
        for (int i = 0; i < guardList.size(); i++) {
            CompoundTag entry = guardList.getCompound(i);
            String key = entry.getString("Key");
            if (!key.isEmpty() && entry.hasUUID("Owner")) {
                d.guards.put(key, new Guard(entry.getUUID("Owner"), entry.getString("Holder")));
            }
        }
        return d;
    }

    public static final SavedData.Factory<LineSymbolStore> FACTORY =
            new SavedData.Factory<>(LineSymbolStore::new, LineSymbolStore::load, null);

    public static LineSymbolStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
