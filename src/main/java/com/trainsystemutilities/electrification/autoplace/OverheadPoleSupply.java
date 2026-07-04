package com.trainsystemutilities.electrification.autoplace;

import com.trainsystemutilities.preset.TrainPresetSupply;
import com.trainsystemutilities.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 架線柱配置ツールの資材供給 (= リンク済み chest + ME 倉庫)。
 *
 * <p>列車プリセットツールの {@link TrainPresetSupply} / AE2 統合をそのまま流用する。
 * chest pos / WAP pos は preset ツールと同じ DataComponent
 * ({@code PLACE_LINKED_CHEST_POS} / {@code PLACE_LINKED_WAP_POS}) を共用する
 * (= 1 つの ItemStack が両ツールのデータを同時に持つことは無いため安全)。
 *
 * <p>chest+ME 結合は「chest が賄えない分 (= chestMissing) を ME 必要量とする」 split 方式で、
 * 既存の all-or-nothing メソッドのみで実現する。
 */
public final class OverheadPoleSupply {

    private OverheadPoleSupply() {}

    /** 在庫カウントの上限 probe (= getChestMissing/getWapMissing で利用可能量を逆算するため)。 */
    private static final int PROBE = 1_000_000;

    private static boolean meAvailable(ItemStack tool) {
        return ModList.get().isLoaded("ae2")
                && tool.get(ModDataComponents.PLACE_LINKED_WAP_POS.get()) != null;
    }

    /** 単一 item の利用可能数 (= chest + ME 合算)。 HUD 在庫表示用。 */
    public static int available(ServerPlayer player, ItemStack tool, ServerLevel level, Item item) {
        Map<Item, Integer> probe = new LinkedHashMap<>();
        probe.put(item, PROBE);
        int total = 0;
        BlockPos chestPos = tool.get(ModDataComponents.PLACE_LINKED_CHEST_POS.get());
        if (chestPos != null) {
            int missing = TrainPresetSupply.getChestMissing(level, chestPos, probe).getOrDefault(item, PROBE);
            total += PROBE - missing;
        }
        if (meAvailable(tool)) {
            int missing = TrainPresetSupply.getWapMissing(player, tool, probe).getOrDefault(item, PROBE);
            total += PROBE - missing;
        }
        return Math.max(0, total);
    }

    /** chest+ME を合わせても不足する分を返す (= 空なら設置可能)。 */
    public static LinkedHashMap<Item, Integer> getMissing(ServerPlayer player, ItemStack tool,
                                                          ServerLevel level, Map<Item, Integer> req) {
        BlockPos chestPos = tool.get(ModDataComponents.PLACE_LINKED_CHEST_POS.get());
        LinkedHashMap<Item, Integer> chestMissing = chestPos != null
                ? TrainPresetSupply.getChestMissing(level, chestPos, req)
                : new LinkedHashMap<>(req);
        if (chestMissing.isEmpty()) return chestMissing;
        // chest で賄えない分 (= chestMissing) を ME が賄えるか
        if (meAvailable(tool)) {
            return TrainPresetSupply.getWapMissing(player, tool, chestMissing);
        }
        return chestMissing;
    }

    /** chest → ME の順で消費する。 全量消費できたら true。 */
    public static boolean consume(ServerPlayer player, ItemStack tool, ServerLevel level, Map<Item, Integer> req) {
        if (req.isEmpty()) return true;
        if (!getMissing(player, tool, level, req).isEmpty()) return false;
        BlockPos chestPos = tool.get(ModDataComponents.PLACE_LINKED_CHEST_POS.get());
        LinkedHashMap<Item, Integer> chestMissing = chestPos != null
                ? TrainPresetSupply.getChestMissing(level, chestPos, req)
                : new LinkedHashMap<>(req);
        // chestReq = req - chestMissing (= chest が賄える分)
        LinkedHashMap<Item, Integer> chestReq = new LinkedHashMap<>();
        for (var e : req.entrySet()) {
            int take = e.getValue() - chestMissing.getOrDefault(e.getKey(), 0);
            if (take > 0) chestReq.put(e.getKey(), take);
        }
        boolean ok = true;
        if (!chestReq.isEmpty()) ok &= TrainPresetSupply.consumeFromChest(level, chestPos, chestReq);
        if (!chestMissing.isEmpty()) ok &= TrainPresetSupply.consumeFromWap(player, tool, chestMissing);
        return ok;
    }

    /** chest / ME のいずれかがリンクされているか (= HUD 補助表示用)。 */
    public static boolean hasAnySource(ItemStack tool) {
        return tool.get(ModDataComponents.PLACE_LINKED_CHEST_POS.get()) != null || meAvailable(tool);
    }
}
