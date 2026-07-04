package com.trainsystemutilities.client.electrification;

import com.trainsystemutilities.electrification.blockentity.InsulatorBlockEntity;
import com.trainsystemutilities.electrification.wire.WireType;
import com.trainsystemutilities.network.WireSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * クライアント側の架線キャッシュ。{@link WireSyncPayload} を受け取って差し替える。
 *
 * <p>{@link com.trainsystemutilities.client.electrification.CatenaryRenderer} が
 * 毎フレームこの list を読んで描画する。同期は CopyOnWrite で読み取り時無 lock。
 */
public final class ClientWireStore {

    public record RenderWire(BlockPos a, BlockPos b,
                              Direction facingA, Direction facingB,
                              Vec3 attachA, Vec3 attachB,
                              boolean energized, WireType type, boolean sag,
                              float customThickness, float customTrolleyOffset,
                              float customDropperInterval, int customRowCount) {}

    private static final List<RenderWire> WIRES = new CopyOnWriteArrayList<>();

    private ClientWireStore() {}

    public static void set(List<WireSyncPayload.WireData> data) {
        List<RenderWire> built = new ArrayList<>(data.size());
        for (WireSyncPayload.WireData w : data) {
            Vec3 aA = InsulatorBlockEntity.attachmentOf(w.a(), w.facingA());
            Vec3 aB = InsulatorBlockEntity.attachmentOf(w.b(), w.facingB());
            built.add(new RenderWire(w.a(), w.b(), w.facingA(), w.facingB(), aA, aB,
                    w.energized(), w.type(), w.sag(),
                    w.customThickness(), w.customTrolleyOffset(),
                    w.customDropperInterval(), w.customRowCount()));
        }
        WIRES.clear();
        WIRES.addAll(built);
    }

    public static List<RenderWire> all() {
        return WIRES;
    }

    public static int size() { return WIRES.size(); }

    public static void clear() { WIRES.clear(); }
}
