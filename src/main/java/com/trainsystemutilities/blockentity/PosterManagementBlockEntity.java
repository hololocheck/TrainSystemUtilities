package com.trainsystemutilities.blockentity;
import belugalab.mcss3.anim.Animation;

import com.trainsystemutilities.item.MonitorLinkCardItem;
import com.trainsystemutilities.registry.ModBlockEntities;
import com.trainsystemutilities.registry.ModItems;
import com.trainsystemutilities.gui.PosterManagementMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * ポスター管理ブロック：リンクしたモニターにPNG/JPG画像を表示する。
 * 複数画像のスライドショーとアニメーション切替に対応。
 */
public class PosterManagementBlockEntity extends BlockEntity implements Container, MenuProvider {

    // モニター連携カード（スロット0）
    private ItemStack monitorLinkCard = ItemStack.EMPTY;
    private boolean monitorEnabled = true;

    // 画像リスト（サーバーワールドデータのUUID）
    private final List<UUID> imageIds = new ArrayList<>();
    private final List<String> imageNames = new ArrayList<>(); // 表示用ファイル名
    private final List<Boolean> imageEnabled = new ArrayList<>(); // 画像の有効/無効
    private int currentImageIndex = 0;

    // フィットモード（true=モニターに合わせる/FIT、false=カバー/COVER）
    private boolean fitToMonitor = false;
    // 1枚のみでもアニメーション
    private boolean animateSingle = false;

    // 管理者モード
    private UUID ownerUUID = null;
    private String ownerName = "";
    private boolean privateMode = false; // false=パブリック, true=プライベート

    // アニメーション設定
    private AnimationType animationType = AnimationType.SLIDE_LEFT;
    private float animationDuration = 0.5f; // 秒
    private float slideInterval = 5.0f; // 秒（次の画像に切り替わるまで）

    // モニターグループ情報（鉄道管理ブロックと同じ構造）
    private int linkedMonitorGroupCount = 0;
    private final List<MonitorGroupInfo> monitorGroups = new ArrayList<>();
    private boolean batchApply = true;
    private int globalTrackNumber = 0;
    private int globalTrackFontSize = 0;
    private int globalTrackPosition = 0;
    private int globalBackTrackNumber = 0;
    private int globalBackTrackFontSize = 0;
    private int globalBackTrackPosition = 0;
    private int globalClockVisible = 1;
    private int globalClockFontSize = 0;
    private int globalBackClockVisible = 1;
    private int globalBackClockFontSize = 0;

    public record MonitorGroupInfo(BlockPos masterPos, int width, int height, boolean doubleSided,
                                    int trackNumber, int trackFontSize, int trackPosition,
                                    int backTrackNumber, int backTrackFontSize, int backTrackPosition,
                                    int clockVisible, int clockFontSize, int backClockVisible, int backClockFontSize) {}

    // スキャンクールダウン
    private int scanCooldown = 0;
    private static final int SCAN_INTERVAL = 20;

    public enum AnimationType {
        SLIDE_LEFT("slide_left", "tsu.poster.anim_slide_left"),
        SLIDE_RIGHT("slide_right", "tsu.poster.anim_slide_right"),
        SLIDE_UP("slide_up", "tsu.poster.anim_slide_up"),
        SLIDE_DOWN("slide_down", "tsu.poster.anim_slide_down"),
        FADE("fade", "tsu.poster.anim_fade"),
        FLIP("flip", "tsu.poster.anim_flip"),
        ZOOM_IN("zoom_in", "tsu.poster.anim_zoom_in"),
        SLIDE_LEFT_FADE("slide_left_fade", "tsu.poster.anim_slide_left_fade"),
        SLIDE_UP_FADE("slide_up_fade", "tsu.poster.anim_slide_up_fade"),
        ZOOM_FADE("zoom_fade", "tsu.poster.anim_zoom_fade"),
        NONE("none", "tsu.poster.anim_none");

        private final String id;
        private final String translationKey;

        AnimationType(String id, String translationKey) {
            this.id = id;
            this.translationKey = translationKey;
        }

        public String getId() { return id; }
        public String getTranslationKey() { return translationKey; }
        public String getDisplayName() {
            return net.minecraft.network.chat.Component.translatable(translationKey).getString();
        }

        public static AnimationType fromId(String id) {
            for (AnimationType type : values()) {
                if (type.id.equals(id)) return type;
            }
            return SLIDE_LEFT;
        }
    }

    public PosterManagementBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POSTER_MANAGEMENT.get(), pos, state);
    }

    // --- Container ---

    @Override public int getContainerSize() { return 1; }
    @Override public boolean isEmpty() { return monitorLinkCard.isEmpty(); }
    @Override public ItemStack getItem(int slot) { return slot == 0 ? monitorLinkCard : ItemStack.EMPTY; }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || monitorLinkCard.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = monitorLinkCard.split(amount);
        if (monitorLinkCard.isEmpty()) monitorLinkCard = ItemStack.EMPTY;
        updateMonitorLinks();
        setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack result = monitorLinkCard;
        monitorLinkCard = ItemStack.EMPTY;
        return result;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) {
            monitorLinkCard = stack;
            updateMonitorLinks();
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == 0 && stack.getItem() instanceof MonitorLinkCardItem;
    }

    @Override public boolean stillValid(Player player) {
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64;
    }
    @Override public void clearContent() { monitorLinkCard = ItemStack.EMPTY; }

    // --- MenuProvider ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.trainsystemutilities.poster_management_block");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new PosterManagementMenu(containerId, inventory, this);
    }

    // --- Tick ---

    public static void tick(Level level, BlockPos pos, BlockState state, PosterManagementBlockEntity be) {
        if (level.isClientSide()) return;
        be.scanCooldown--;
        if (be.scanCooldown <= 0) {
            be.scanCooldown = SCAN_INTERVAL;
            be.updateMonitorLinks();
        }
    }

    // --- Monitor Link ---

    public void updateMonitorLinks() {
        if (level == null || level.isClientSide()) return;
        monitorGroups.clear();
        if (!monitorLinkCard.isEmpty()) {
            var positions = MonitorLinkCardItem.getRegisteredPositions(monitorLinkCard);
            linkedMonitorGroupCount = MonitorLinkCardItem.countMonitorGroups(level, positions);
            java.util.Set<BlockPos> visited = new java.util.HashSet<>();
            for (BlockPos mPos : positions) {
                BlockEntity be = level.getBlockEntity(mPos);
                if (be instanceof MonitorBlockEntity monitor) {
                    BlockPos master = monitor.getMasterPos() != null ? monitor.getMasterPos() : mPos;
                    if (visited.add(master)) {
                        boolean doubleSided = false;
                        for (BlockPos connPos : monitor.findConnectedMonitors()) {
                            if (com.trainsystemutilities.block.MonitorBlock.isDoubleSidedMonitor(level.getBlockState(connPos))) {
                                doubleSided = true; break;
                            }
                        }
                        monitorGroups.add(new MonitorGroupInfo(master,
                                monitor.getMultiBlockWidth(), monitor.getMultiBlockHeight(),
                                doubleSided, monitor.getTrackNumber(), monitor.getTrackFontSize(), monitor.getTrackPosition(),
                                monitor.getBackTrackNumber(), monitor.getBackTrackFontSize(), monitor.getBackTrackPosition(),
                                monitor.getClockVisible(), monitor.getClockFontSize(),
                                monitor.getBackClockVisible(), monitor.getBackClockFontSize()));
                    }
                }
            }
        } else {
            linkedMonitorGroupCount = 0;
        }
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // --- Image Management ---

    public List<UUID> getImageIds() { return imageIds; }
    public List<String> getImageNames() { return imageNames; }
    public int getCurrentImageIndex() { return currentImageIndex; }
    public void setCurrentImageIndex(int idx) { this.currentImageIndex = idx; setChanged(); }

    public void addImageId(UUID id, String fileName) {
        imageIds.add(id);
        imageNames.add(fileName);
        imageEnabled.add(true);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void moveImageUp(int index) {
        if (index > 0 && index < imageIds.size()) {
            java.util.Collections.swap(imageIds, index, index - 1);
            if (index < imageNames.size() && index - 1 < imageNames.size())
                java.util.Collections.swap(imageNames, index, index - 1);
            if (index < imageEnabled.size() && index - 1 < imageEnabled.size())
                java.util.Collections.swap(imageEnabled, index, index - 1);
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void moveImageDown(int index) {
        if (index >= 0 && index < imageIds.size() - 1) {
            java.util.Collections.swap(imageIds, index, index + 1);
            if (index < imageNames.size() && index + 1 < imageNames.size())
                java.util.Collections.swap(imageNames, index, index + 1);
            if (index < imageEnabled.size() && index + 1 < imageEnabled.size())
                java.util.Collections.swap(imageEnabled, index, index + 1);
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void removeImage(int index) {
        if (index >= 0 && index < imageIds.size()) {
            imageIds.remove(index);
            if (index < imageNames.size()) imageNames.remove(index);
            if (index < imageEnabled.size()) imageEnabled.remove(index);
            if (currentImageIndex >= imageIds.size()) currentImageIndex = 0;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // --- Animation Settings ---

    public List<Boolean> getImageEnabled() { return imageEnabled; }
    public boolean isImageEnabled(int index) {
        return index >= 0 && index < imageEnabled.size() ? imageEnabled.get(index) : true;
    }
    public void toggleImageEnabled(int index) {
        if (index >= 0 && index < imageEnabled.size()) {
            imageEnabled.set(index, !imageEnabled.get(index));
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    /** モニターに表示する有効画像IDのリストを返す */
    public List<UUID> getActiveImageIds() {
        List<UUID> active = new ArrayList<>();
        for (int i = 0; i < imageIds.size(); i++) {
            if (isImageEnabled(i)) active.add(imageIds.get(i));
        }
        return active;
    }

    public boolean isAnimateSingle() { return animateSingle; }
    public void toggleAnimateSingle() {
        animateSingle = !animateSingle; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // 管理者モード
    public UUID getOwnerUUID() { return ownerUUID; }
    public String getOwnerName() { return ownerName; }
    public boolean isPrivateMode() { return privateMode; }
    public void setOwner(UUID uuid, String name) {
        this.ownerUUID = uuid; this.ownerName = name; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public void togglePrivateMode() {
        privateMode = !privateMode; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean canAccess(net.minecraft.world.entity.player.Player player) {
        if (!privateMode) return true;
        if (ownerUUID == null) return true;
        return ownerUUID.equals(player.getUUID());
    }

    public boolean isFitToMonitor() { return fitToMonitor; }
    public void toggleFitToMonitor() {
        fitToMonitor = !fitToMonitor; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public AnimationType getAnimationType() { return animationType; }
    public void setAnimationType(AnimationType type) {
        this.animationType = type; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public float getAnimationDuration() { return animationDuration; }
    public void setAnimationDuration(float duration) {
        this.animationDuration = duration; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public float getSlideInterval() { return slideInterval; }
    public void setSlideInterval(float interval) {
        this.slideInterval = interval; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public int getLinkedMonitorGroupCount() { return linkedMonitorGroupCount; }
    public List<MonitorGroupInfo> getMonitorGroups() { return monitorGroups; }
    public boolean isMonitorEnabled() { return monitorEnabled; }
    public void toggleMonitorEnabled() {
        monitorEnabled = !monitorEnabled; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    public boolean isBatchApply() { return batchApply; }
    public void toggleBatchApply() {
        batchApply = !batchApply; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // --- Global Track Settings ---
    public int getGlobalTrackNumber() { return globalTrackNumber; }
    public void setGlobalTrackNumber(int num) {
        globalTrackNumber = num;
        if (batchApply) applyToAllMonitors(m -> m.setTrackNumberForGroup(num));
        updateMonitorLinks();
    }
    public int getGlobalTrackFontSize() { return globalTrackFontSize; }
    public void setGlobalTrackFontSize(int size) {
        globalTrackFontSize = size;
        if (batchApply) applyToAllMonitors(m -> m.setTrackFontSizeForGroup(size));
        updateMonitorLinks();
    }
    public int getGlobalTrackPosition() { return globalTrackPosition; }
    public void setGlobalTrackPosition(int pos) {
        globalTrackPosition = pos;
        if (batchApply) applyToAllMonitors(m -> m.setTrackPositionForGroup(pos));
        updateMonitorLinks();
    }
    public int getGlobalClockVisible() { return globalClockVisible; }
    public void setGlobalClockVisible(int val) {
        globalClockVisible = val;
        if (batchApply) applyToAllMonitors(m -> m.setClockVisibleForGroup(val));
        updateMonitorLinks();
    }
    public int getGlobalClockFontSize() { return globalClockFontSize; }
    public void setGlobalClockFontSize(int size) {
        globalClockFontSize = size;
        if (batchApply) applyToAllMonitors(m -> m.setClockFontSizeForGroup(size));
        updateMonitorLinks();
    }
    // --- Global Back Track Settings ---
    public int getGlobalBackTrackNumber() { return globalBackTrackNumber; }
    public void setGlobalBackTrackNumber(int num) {
        globalBackTrackNumber = num;
        if (batchApply) applyToAllMonitors(m -> m.setBackTrackNumberForGroup(num));
        updateMonitorLinks();
    }
    public int getGlobalBackTrackFontSize() { return globalBackTrackFontSize; }
    public void setGlobalBackTrackFontSize(int size) {
        globalBackTrackFontSize = size;
        if (batchApply) applyToAllMonitors(m -> m.setBackTrackFontSizeForGroup(size));
        updateMonitorLinks();
    }
    public int getGlobalBackTrackPosition() { return globalBackTrackPosition; }
    public void setGlobalBackTrackPosition(int pos) {
        globalBackTrackPosition = pos;
        if (batchApply) applyToAllMonitors(m -> m.setBackTrackPositionForGroup(pos));
        updateMonitorLinks();
    }
    public int getGlobalBackClockVisible() { return globalBackClockVisible; }
    public void setGlobalBackClockVisible(int val) {
        globalBackClockVisible = val;
        if (batchApply) applyToAllMonitors(m -> m.setBackClockVisibleForGroup(val));
        updateMonitorLinks();
    }
    public int getGlobalBackClockFontSize() { return globalBackClockFontSize; }
    public void setGlobalBackClockFontSize(int size) {
        globalBackClockFontSize = size;
        if (batchApply) applyToAllMonitors(m -> m.setBackClockFontSizeForGroup(size));
        updateMonitorLinks();
    }
    // --- Per-group setters ---
    public void setTrackNumberForGroup(int gi, int num) { applyToGroup(gi, m -> m.setTrackNumberForGroup(num)); }
    public void setTrackFontSizeForGroup(int gi, int size) { applyToGroup(gi, m -> m.setTrackFontSizeForGroup(size)); }
    public void setTrackPositionForGroup(int gi, int pos) { applyToGroup(gi, m -> m.setTrackPositionForGroup(pos)); }
    public void setClockVisibleForGroup(int gi, int val) { applyToGroup(gi, m -> m.setClockVisibleForGroup(val)); }
    public void setClockFontSizeForGroup(int gi, int size) { applyToGroup(gi, m -> m.setClockFontSizeForGroup(size)); }
    public void setBackTrackNumberForGroup(int gi, int num) { applyToGroup(gi, m -> m.setBackTrackNumberForGroup(num)); }
    public void setBackTrackFontSizeForGroup(int gi, int size) { applyToGroup(gi, m -> m.setBackTrackFontSizeForGroup(size)); }
    public void setBackTrackPositionForGroup(int gi, int pos) { applyToGroup(gi, m -> m.setBackTrackPositionForGroup(pos)); }
    public void setBackClockVisibleForGroup(int gi, int val) { applyToGroup(gi, m -> m.setBackClockVisibleForGroup(val)); }
    public void setBackClockFontSizeForGroup(int gi, int size) { applyToGroup(gi, m -> m.setBackClockFontSizeForGroup(size)); }

    private void applyToAllMonitors(java.util.function.Consumer<MonitorBlockEntity> action) {
        if (level == null || level.isClientSide()) return;
        for (MonitorGroupInfo g : monitorGroups) {
            BlockEntity be = level.getBlockEntity(g.masterPos());
            if (be instanceof MonitorBlockEntity m) action.accept(m);
        }
    }
    private void applyToGroup(int gi, java.util.function.Consumer<MonitorBlockEntity> action) {
        if (level == null || level.isClientSide() || gi < 0 || gi >= monitorGroups.size()) return;
        BlockEntity be = level.getBlockEntity(monitorGroups.get(gi).masterPos());
        if (be instanceof MonitorBlockEntity m) action.accept(m);
        updateMonitorLinks();
    }

    // --- NBT ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!monitorLinkCard.isEmpty()) {
            tag.put("LinkCard", monitorLinkCard.save(registries));
        }

        ListTag imageList = new ListTag();
        for (int i = 0; i < imageIds.size(); i++) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", imageIds.get(i));
            entry.putString("Name", i < imageNames.size() ? imageNames.get(i) : "");
            entry.putBoolean("Enabled", i < imageEnabled.size() ? imageEnabled.get(i) : true);
            imageList.add(entry);
        }
        tag.put("Images", imageList);
        tag.putInt("CurrentImage", currentImageIndex);
        tag.putBoolean("FitToMonitor", fitToMonitor);
        tag.putBoolean("AnimateSingle", animateSingle);
        if (ownerUUID != null) tag.putUUID("OwnerUUID", ownerUUID);
        tag.putString("OwnerName", ownerName);
        tag.putBoolean("PrivateMode", privateMode);
        tag.putString("AnimType", animationType.getId());
        tag.putFloat("AnimDuration", animationDuration);
        tag.putFloat("SlideInterval", slideInterval);
        tag.putInt("MonitorGroups", linkedMonitorGroupCount);
        tag.putBoolean("MonitorEnabled", monitorEnabled);
        tag.putBoolean("BatchApply", batchApply);
        tag.putInt("GlobalTrackNumber", globalTrackNumber);
        tag.putInt("GlobalTrackFontSize", globalTrackFontSize);
        tag.putInt("GlobalTrackPosition", globalTrackPosition);
        tag.putInt("GlobalBackTrackNumber", globalBackTrackNumber);
        tag.putInt("GlobalBackTrackFontSize", globalBackTrackFontSize);
        tag.putInt("GlobalBackTrackPosition", globalBackTrackPosition);
        tag.putInt("GlobalClockVisible", globalClockVisible);
        tag.putInt("GlobalClockFontSize", globalClockFontSize);
        tag.putInt("GlobalBackClockVisible", globalBackClockVisible);
        tag.putInt("GlobalBackClockFontSize", globalBackClockFontSize);
        ListTag groupList = new ListTag();
        for (MonitorGroupInfo g : monitorGroups) {
            CompoundTag gt = new CompoundTag();
            gt.putLong("MasterPos", g.masterPos().asLong());
            gt.putInt("W", g.width()); gt.putInt("H", g.height());
            gt.putBoolean("Double", g.doubleSided());
            gt.putInt("Track", g.trackNumber()); gt.putInt("FontSize", g.trackFontSize()); gt.putInt("TrackPos", g.trackPosition());
            gt.putInt("BackTrack", g.backTrackNumber()); gt.putInt("BackFontSize", g.backTrackFontSize()); gt.putInt("BackTrackPos", g.backTrackPosition());
            gt.putInt("ClockVis", g.clockVisible()); gt.putInt("ClockFS", g.clockFontSize());
            gt.putInt("BackClockVis", g.backClockVisible()); gt.putInt("BackClockFS", g.backClockFontSize());
            groupList.add(gt);
        }
        tag.put("MonitorGroupList", groupList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        monitorLinkCard = tag.contains("LinkCard")
                ? ItemStack.parse(registries, tag.getCompound("LinkCard")).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;

        imageIds.clear();
        imageNames.clear();
        imageEnabled.clear();
        ListTag imageList = tag.getList("Images", Tag.TAG_COMPOUND);
        for (int i = 0; i < imageList.size(); i++) {
            CompoundTag entry = imageList.getCompound(i);
            imageIds.add(entry.getUUID("Id"));
            imageNames.add(entry.getString("Name"));
            imageEnabled.add(!entry.contains("Enabled") || entry.getBoolean("Enabled"));
        }
        currentImageIndex = tag.getInt("CurrentImage");
        fitToMonitor = tag.contains("FitToMonitor") && tag.getBoolean("FitToMonitor");
        animateSingle = tag.contains("AnimateSingle") && tag.getBoolean("AnimateSingle");
        ownerUUID = tag.contains("OwnerUUID") ? tag.getUUID("OwnerUUID") : null;
        ownerName = tag.getString("OwnerName");
        privateMode = tag.contains("PrivateMode") && tag.getBoolean("PrivateMode");
        animationType = AnimationType.fromId(tag.getString("AnimType"));
        animationDuration = tag.contains("AnimDuration") ? tag.getFloat("AnimDuration") : 0.5f;
        slideInterval = tag.contains("SlideInterval") ? tag.getFloat("SlideInterval") : 5.0f;
        linkedMonitorGroupCount = tag.getInt("MonitorGroups");
        monitorEnabled = !tag.contains("MonitorEnabled") || tag.getBoolean("MonitorEnabled");
        batchApply = !tag.contains("BatchApply") || tag.getBoolean("BatchApply");
        globalTrackNumber = tag.getInt("GlobalTrackNumber");
        globalTrackFontSize = tag.getInt("GlobalTrackFontSize");
        globalTrackPosition = tag.getInt("GlobalTrackPosition");
        globalBackTrackNumber = tag.getInt("GlobalBackTrackNumber");
        globalBackTrackFontSize = tag.getInt("GlobalBackTrackFontSize");
        globalBackTrackPosition = tag.getInt("GlobalBackTrackPosition");
        globalClockVisible = tag.contains("GlobalClockVisible") ? tag.getInt("GlobalClockVisible") : 1;
        globalClockFontSize = tag.getInt("GlobalClockFontSize");
        globalBackClockVisible = tag.contains("GlobalBackClockVisible") ? tag.getInt("GlobalBackClockVisible") : 1;
        globalBackClockFontSize = tag.getInt("GlobalBackClockFontSize");
        monitorGroups.clear();
        ListTag groupList = tag.getList("MonitorGroupList", Tag.TAG_COMPOUND);
        for (int i = 0; i < groupList.size(); i++) {
            CompoundTag gt = groupList.getCompound(i);
            monitorGroups.add(new MonitorGroupInfo(
                    BlockPos.of(gt.getLong("MasterPos")), gt.getInt("W"), gt.getInt("H"),
                    gt.getBoolean("Double"), gt.getInt("Track"), gt.getInt("FontSize"), gt.getInt("TrackPos"),
                    gt.getInt("BackTrack"), gt.getInt("BackFontSize"), gt.getInt("BackTrackPos"),
                    gt.contains("ClockVis") ? gt.getInt("ClockVis") : 1, gt.getInt("ClockFS"),
                    gt.contains("BackClockVis") ? gt.getInt("BackClockVis") : 1, gt.getInt("BackClockFS")));
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }
}
