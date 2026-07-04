package com.trainsystemutilities.blockentity;

import com.trainsystemutilities.block.MonitorBlock;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * モニターのBlockEntity。
 * 隣接するモニター同士で連結して巨大モニターを形成する。
 * 管理用コンピューターからのデータを受信して路線図を表示する。
 */
public class MonitorBlockEntity extends BlockEntity {

    private BlockPos controllerPos = null;  // このモニターがリンクされた管理用コンピューターの位置
    private boolean isMaster = false;       // マルチブロック構造のマスターかどうか
    private BlockPos masterPos = null;      // マスターモニターの位置
    private int multiBlockWidth = 1;
    private int multiBlockHeight = 1;
    private int trackNumber = 0; // 番線番号 (0 = 未設定)
    private int trackFontSize = 0; // 番線フォントサイズ (0 = 自動)
    private int trackPosition = 0; // 番線表示位置 (0 = 左, 1 = 右)
    private int backTrackNumber = 0;
    private int backTrackFontSize = 0;
    private int backTrackPosition = 0;
    private int clockVisible = 1; // 1=visible, 0=hidden
    private int clockFontSize = 0; // 0=auto
    private int backClockVisible = 1;
    private int backClockFontSize = 0;

    public MonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MONITOR.get(), pos, state);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        // 描画側のグループキャッシュ (CSSWorldRenderer / アニメ状態) を破棄してメモリリーク防止。
        if (level != null && level.isClientSide()) {
            try {
                com.trainsystemutilities.client.renderer.MonitorWorldRenderer
                        .invalidateMonitorGroup(worldPosition);
            } catch (Throwable e) { com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[Monitor] group cache invalidate failed", e); }
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state,
                            MonitorBlockEntity blockEntity) {
        if (level.isClientSide()) return;
        // Periodic updates for display content refresh
    }

    public void updateMultiBlockStructure() {
        if (level == null || level.isClientSide()) return;

        List<BlockPos> connected = findConnectedMonitors();

        // Find bounds of connected monitors
        Direction facing = getBlockState().getValue(MonitorBlock.FACING);
        Direction right = facing.getClockWise();

        // Use the first connected block as reference origin
        BlockPos origin = connected.isEmpty() ? worldPosition : connected.get(0);

        int minX = 0, maxX = 0, minY = 0, maxY = 0;

        for (BlockPos connPos : connected) {
            BlockPos diff = connPos.subtract(origin);
            int horizontal = diff.getX() * right.getStepX() + diff.getZ() * right.getStepZ();
            int vertical = diff.getY();

            minX = Math.min(minX, horizontal);
            maxX = Math.max(maxX, horizontal);
            minY = Math.min(minY, vertical);
            maxY = Math.max(maxY, vertical);
        }

        int width = maxX - minX + 1;
        int height = maxY - minY + 1;

        // The top-left monitor (from front view) is the master
        BlockPos masterCandidate = origin.offset(
                right.getStepX() * minX, maxY, right.getStepZ() * minX
        );

        // Update ALL connected monitors with the same structure info
        for (BlockPos connPos : connected) {
            BlockEntity be = level.getBlockEntity(connPos);
            if (be instanceof MonitorBlockEntity monitor) {
                monitor.masterPos = masterCandidate;
                monitor.isMaster = connPos.equals(masterCandidate);
                monitor.multiBlockWidth = width;
                monitor.multiBlockHeight = height;
                monitor.setChanged();
                level.sendBlockUpdated(connPos, monitor.getBlockState(), monitor.getBlockState(), 3);
            }
        }
    }

    public List<BlockPos> findConnectedMonitors() {
        List<BlockPos> connected = new ArrayList<>();
        List<BlockPos> toCheck = new ArrayList<>();
        toCheck.add(worldPosition);

        Direction facing = getBlockState().getValue(MonitorBlock.FACING);

        while (!toCheck.isEmpty()) {
            BlockPos current = toCheck.remove(0);
            if (connected.contains(current)) continue;

            if (level != null) {
                BlockState state = level.getBlockState(current);
                if (MonitorBlock.isMonitorBlock(state)
                        && state.getValue(MonitorBlock.FACING) == facing) {
                    connected.add(current);

                    // Check neighbors on the same plane (up, down, left, right relative to facing)
                    Direction right = facing.getClockWise();
                    toCheck.add(current.above());
                    toCheck.add(current.below());
                    toCheck.add(current.relative(right));
                    toCheck.add(current.relative(right.getOpposite()));
                }
            }
        }

        return connected;
    }

    public void setControllerPos(BlockPos pos) {
        this.controllerPos = pos;
        setChanged();
        // Phase 5d: client BE 側の renderer が controllerPos を読むため、明示的に broadcast
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public BlockPos getControllerPos() {
        return controllerPos;
    }

    private BlockPos linkedRailwayManagerPos = null;

    public void setLinkedRailwayManager(BlockPos pos) {
        this.linkedRailwayManagerPos = pos;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            // Propagate to all connected monitors
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity monitor) {
                        monitor.linkedRailwayManagerPos = pos;
                        monitor.setChanged();
                        level.sendBlockUpdated(connPos, monitor.getBlockState(), monitor.getBlockState(), 3);
                    }
                }
            }
        }
    }

    public BlockPos getLinkedRailwayManagerPos() {
        return linkedRailwayManagerPos;
    }

    public boolean isMaster() {
        return isMaster;
    }

    public BlockPos getMasterPos() {
        return masterPos;
    }

    public int getMultiBlockWidth() {
        return multiBlockWidth;
    }

    public int getMultiBlockHeight() {
        return multiBlockHeight;
    }

    public int getTrackNumber() { return trackNumber; }
    public int getTrackFontSize() { return trackFontSize; }
    public int getTrackPosition() { return trackPosition; }
    public int getBackTrackNumber() { return backTrackNumber; }
    public int getBackTrackFontSize() { return backTrackFontSize; }
    public int getBackTrackPosition() { return backTrackPosition; }
    public int getClockVisible() { return clockVisible; }
    public int getClockFontSize() { return clockFontSize; }
    public int getBackClockVisible() { return backClockVisible; }
    public int getBackClockFontSize() { return backClockFontSize; }
    public void setTrackNumber(int num) {
        this.trackNumber = num;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setTrackFontSize(int size) {
        this.trackFontSize = size;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setTrackPosition(int pos) {
        this.trackPosition = pos;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setTrackNumberForGroup(int num) {
        setTrackNumber(num);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.trackNumber = num;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setTrackFontSizeForGroup(int size) {
        setTrackFontSize(size);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.trackFontSize = size;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setTrackPositionForGroup(int pos) {
        setTrackPosition(pos);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.trackPosition = pos;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setBackTrackNumber(int num) {
        this.backTrackNumber = num;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setBackTrackFontSize(int size) {
        this.backTrackFontSize = size;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setBackTrackPosition(int pos) {
        this.backTrackPosition = pos;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setBackTrackNumberForGroup(int num) {
        setBackTrackNumber(num);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.backTrackNumber = num;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setBackTrackFontSizeForGroup(int size) {
        setBackTrackFontSize(size);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.backTrackFontSize = size;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setBackTrackPositionForGroup(int pos) {
        setBackTrackPosition(pos);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.backTrackPosition = pos;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setClockVisible(int val) {
        this.clockVisible = val;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setClockFontSize(int size) {
        this.clockFontSize = size;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setBackClockVisible(int val) {
        this.backClockVisible = val;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setBackClockFontSize(int size) {
        this.backClockFontSize = size;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void setClockVisibleForGroup(int val) {
        setClockVisible(val);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.clockVisible = val;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setClockFontSizeForGroup(int size) {
        setClockFontSize(size);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.clockFontSize = size;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setBackClockVisibleForGroup(int val) {
        setBackClockVisible(val);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.backClockVisible = val;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }
    public void setBackClockFontSizeForGroup(int size) {
        setBackClockFontSize(size);
        if (level != null) {
            for (BlockPos connPos : findConnectedMonitors()) {
                if (!connPos.equals(worldPosition)) {
                    BlockEntity be = level.getBlockEntity(connPos);
                    if (be instanceof MonitorBlockEntity m) {
                        m.backClockFontSize = size;
                        m.setChanged();
                        level.sendBlockUpdated(connPos, m.getBlockState(), m.getBlockState(), 3);
                    }
                }
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        if (controllerPos != null) {
            tag.putInt("ControllerX", controllerPos.getX());
            tag.putInt("ControllerY", controllerPos.getY());
            tag.putInt("ControllerZ", controllerPos.getZ());
        }

        tag.putBoolean("IsMaster", isMaster);
        if (masterPos != null) {
            tag.putInt("MasterX", masterPos.getX());
            tag.putInt("MasterY", masterPos.getY());
            tag.putInt("MasterZ", masterPos.getZ());
        }

        tag.putInt("MultiBlockWidth", multiBlockWidth);
        tag.putInt("MultiBlockHeight", multiBlockHeight);
        tag.putInt("TrackNumber", trackNumber);
        tag.putInt("TrackFontSize", trackFontSize);
        tag.putInt("TrackPosition", trackPosition);
        tag.putInt("BackTrackNumber", backTrackNumber);
        tag.putInt("BackTrackFontSize", backTrackFontSize);
        tag.putInt("BackTrackPosition", backTrackPosition);
        tag.putInt("ClockVisible", clockVisible);
        tag.putInt("ClockFontSize", clockFontSize);
        tag.putInt("BackClockVisible", backClockVisible);
        tag.putInt("BackClockFontSize", backClockFontSize);

        if (linkedRailwayManagerPos != null) {
            tag.putLong("LinkedRMPos", linkedRailwayManagerPos.asLong());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        if (tag.contains("ControllerX")) {
            controllerPos = new BlockPos(
                    tag.getInt("ControllerX"),
                    tag.getInt("ControllerY"),
                    tag.getInt("ControllerZ")
            );
        }

        isMaster = tag.getBoolean("IsMaster");
        if (tag.contains("MasterX")) {
            masterPos = new BlockPos(
                    tag.getInt("MasterX"),
                    tag.getInt("MasterY"),
                    tag.getInt("MasterZ")
            );
        }

        multiBlockWidth = tag.contains("MultiBlockWidth") ? Math.max(1, tag.getInt("MultiBlockWidth")) : 1;
        multiBlockHeight = tag.contains("MultiBlockHeight") ? Math.max(1, tag.getInt("MultiBlockHeight")) : 1;
        trackNumber = tag.getInt("TrackNumber");
        trackFontSize = tag.getInt("TrackFontSize");
        trackPosition = tag.getInt("TrackPosition");
        backTrackNumber = tag.getInt("BackTrackNumber");
        backTrackFontSize = tag.getInt("BackTrackFontSize");
        backTrackPosition = tag.getInt("BackTrackPosition");
        clockVisible = tag.contains("ClockVisible") ? tag.getInt("ClockVisible") : 1;
        clockFontSize = tag.getInt("ClockFontSize");
        backClockVisible = tag.contains("BackClockVisible") ? tag.getInt("BackClockVisible") : 1;
        backClockFontSize = tag.getInt("BackClockFontSize");

        linkedRailwayManagerPos = tag.contains("LinkedRMPos") ? BlockPos.of(tag.getLong("LinkedRMPos")) : null;
    }

    // --- Client sync ---

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
