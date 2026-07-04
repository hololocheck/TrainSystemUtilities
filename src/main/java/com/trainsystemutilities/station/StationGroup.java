package com.trainsystemutilities.station;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「駅グループ」= 範囲で囲まれた建築物 + 複数の Create 駅ブロックをまとめた論理駅。
 *
 * <p>路線記号は管理用コンピューター側で各 Create 駅ブロックに割り当てるため、
 * このグループには持たない (グループは範囲 + 名前 + 駅順 + 番線命名テンプレート のみ)。
 *
 * @param id              一意 ID
 * @param name            駅グループ表示名 (例: "東京")
 * @param dimensionId     ResourceLocation 文字列 ("minecraft:overworld" 等)
 * @param minPos          範囲 min 座標 (両端含む AABB)
 * @param maxPos          範囲 max 座標
 * @param stationBlockIds 範囲内に存在する Create 駅ブロックの GlobalStation UUID
 * @param stationBlockPositions 並び順 = (x, z, y) 昇順 → 番線番号 = index + 1
 * @param namingTemplate  番線命名 String.format テンプレ ("%s %d番線" or "%s Platform %d" 等)。
 *                        作成者のクライアント言語に基づいて決定。
 */
public record StationGroup(
        UUID id,
        String name,
        String dimensionId,
        BlockPos minPos,
        BlockPos maxPos,
        List<UUID> stationBlockIds,
        List<BlockPos> stationBlockPositions,
        String namingTemplate,
        UUID ownerUUID   // nullable; null = legacy / public group (誰でも rename/delete 可)
) {
    /** 日本語環境のデフォルトテンプレ。 */
    public static final String NAMING_TEMPLATE_JP = "%s %d番線";
    /** 英語 (その他) 環境のデフォルトテンプレ。 */
    public static final String NAMING_TEMPLATE_EN = "%s Platform %d";

    /** クライアント言語コード ("ja_jp" 等) からテンプレを選択。 */
    public static String pickTemplate(String langCode) {
        if (langCode != null && langCode.toLowerCase().startsWith("ja")) {
            return NAMING_TEMPLATE_JP;
        }
        return NAMING_TEMPLATE_EN;
    }

    /** 指定 platform 番号でフォーマット済み駅名を生成。 */
    public String formatStationName(int platformNum) {
        String tpl = (namingTemplate == null || namingTemplate.isEmpty())
                ? NAMING_TEMPLATE_JP : namingTemplate;
        try {
            return String.format(tpl, name, platformNum);
        } catch (Exception e) {
            return name + " " + platformNum;
        }
    }

    public boolean contains(String dim, BlockPos p) {
        if (!dimensionId.equals(dim)) return false;
        return p.getX() >= minPos.getX() && p.getX() <= maxPos.getX()
            && p.getY() >= minPos.getY() && p.getY() <= maxPos.getY()
            && p.getZ() >= minPos.getZ() && p.getZ() <= maxPos.getZ();
    }

    /** position の番線番号 (1-based)。stationBlockPositions に含まれないなら 0。 */
    public int platformNumberFor(BlockPos pos) {
        for (int i = 0; i < stationBlockPositions.size(); i++) {
            if (stationBlockPositions.get(i).equals(pos)) return i + 1;
        }
        return 0;
    }

    /** Create GlobalStation UUID から番線番号 (1-based)。一致しなければ 0。 */
    public int platformNumberForId(UUID stationId) {
        if (stationId == null) return 0;
        for (int i = 0; i < stationBlockIds.size(); i++) {
            if (stationId.equals(stationBlockIds.get(i))) return i + 1;
        }
        return 0;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Name", name);
        t.putString("Dim", dimensionId);
        t.putString("NamingTpl", namingTemplate == null ? "" : namingTemplate);
        if (ownerUUID != null) t.putUUID("Owner", ownerUUID);
        t.putLong("MinPacked", minPos.asLong());
        t.putLong("MaxPacked", maxPos.asLong());
        ListTag idsList = new ListTag();
        for (UUID u : stationBlockIds) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("U", u);
            idsList.add(entry);
        }
        t.put("StationIds", idsList);
        long[] packed = new long[stationBlockPositions.size()];
        for (int i = 0; i < stationBlockPositions.size(); i++) {
            packed[i] = stationBlockPositions.get(i).asLong();
        }
        t.put("StationPosPacked", new LongArrayTag(packed));
        return t;
    }

    public static StationGroup load(CompoundTag t) {
        UUID id = t.getUUID("Id");
        String name = t.getString("Name");
        String dim = t.getString("Dim");
        BlockPos minP = BlockPos.of(t.getLong("MinPacked"));
        BlockPos maxP = BlockPos.of(t.getLong("MaxPacked"));
        ListTag idsList = t.getList("StationIds", Tag.TAG_COMPOUND);
        List<UUID> ids = new ArrayList<>(idsList.size());
        for (int i = 0; i < idsList.size(); i++) {
            ids.add(idsList.getCompound(i).getUUID("U"));
        }
        long[] packed = t.getLongArray("StationPosPacked");
        List<BlockPos> positions = new ArrayList<>(packed.length);
        for (long p : packed) positions.add(BlockPos.of(p));
        String tpl = t.contains("NamingTpl") ? t.getString("NamingTpl") : NAMING_TEMPLATE_JP;
        if (tpl.isEmpty()) tpl = NAMING_TEMPLATE_JP;
        UUID owner = t.hasUUID("Owner") ? t.getUUID("Owner") : null;
        return new StationGroup(id, name, dim, minP, maxP, ids, positions, tpl, owner);
    }
}
