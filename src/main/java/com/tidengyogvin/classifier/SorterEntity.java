package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SorterEntity extends PathfinderMob implements MenuProvider {

    private static final EntityDataAccessor<Boolean> DATA_WORKING =
            SynchedEntityData.defineId(SorterEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<String> DATA_SKIN_PLAYER =
            SynchedEntityData.defineId(SorterEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Optional<BlockPos>> DATA_SORTER_BOX =
            SynchedEntityData.defineId(SorterEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    private static final EntityDataAccessor<Boolean> DATA_MANUAL_PAUSE =
            SynchedEntityData.defineId(SorterEntity.class, EntityDataSerializers.BOOLEAN);

    public static final TicketType<SorterEntity> SORTER_TICKET =
            TicketType.create("sorter_ticket", (a, b) -> 0);

    private int wakeUpTimer = 0;
    private ItemStack carriedItem = ItemStack.EMPTY;
    private ChunkPos sorterTicketChunk = null;
    /** 分类箱所在区块的 ticket（工作区较大时分类者与箱子可能不在同一区块） */
    private ChunkPos boxTicketChunk = null;
    /** 绑定的工作区 UUID；工作区本体存在 {@link WorkspaceTable}，这里只存引用 */
    @Nullable
    private UUID workspaceId;
    /**
     * 旧存档（工作区对象化之前）把坐标直接写在实体上。读档时暂存到这里，
     * 首个服务端 tick 迁移成工作区对象；迁移完成前保存世界要把旧字段原样写回，
     * 避免「区块没加载过 → 没迁移 → 旧数据被覆盖掉」。
     */
    @Nullable
    private BlockPos legacyWorkspaceMin;
    @Nullable
    private BlockPos legacyWorkspaceMax;
    @Nullable
    private ResourceKey<Level> legacyWorkspaceDimension;
    /** 分类者归属的玩家（UUID + 名字快照），管理器保存工作区时只同步给归属者名下的分类者 */
    @Nullable
    private UUID ownerUuid;
    private String ownerName = "";
    /** 分类箱绑定时的维度；实体被搬去别的维度后据此判定“跨维度绑定”并自动解绑 */
    @Nullable
    private ResourceKey<Level> boundDimension;
    /** 箱子坐标 → 存放类型（tag:/mod:/item: 前缀，语义见 CategoryRules），随实体 NBT 持久化 */
    private final Map<BlockPos, String> boxCategories = new HashMap<>();
    /** 箱子坐标 → 被选为首选目标的次数，用于雷达排序（会话内统计） */
    private final Map<BlockPos, Integer> boxUsage = new HashMap<>();
    /** 雷达同步节流 */
    private int syncTickCounter = 0;
    private int lastRadarSignature = 0;
    /** 当前正在执行的整理任务；同一分类者同一时间只允许一个 */
    @Nullable
    private WarehouseSorterTask activeSortTask;
    /** 读档后的首个服务端 tick：旧存档状态不可信，先释放所有 ticket 且不新增 */
    private boolean pendingLoadTicketReset = false;

    public SorterEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level.isClientSide && this.level instanceof ServerLevel serverLevel) {
            // 旧存档的工作区坐标在这里迁移成工作区对象；放在 ticket 重置之前，
            // 否则迁移要等下一个 tick，区块未加载的实体就会一直拖着旧字段
            this.migrateLegacyWorkspace(serverLevel);

            // 读档后的第一个 tick 只做释放：旧存档可能带着崩溃遗留的强制加载区块，
            // 不让新建的实体在上线瞬间再申请 ticket
            if (this.pendingLoadTicketReset) {
                this.pendingLoadTicketReset = false;
                this.releaseAllTickets(serverLevel);
                this.validateBindingDimension();
                return;
            }

            ChunkPos chunkPos = new ChunkPos(this.blockPosition());

            // 只有在“工作中”且绑定了分类箱时，才强加载周围 1 个区块（即 3x3）
            if (this.isWorking() && this.getSorterBoxPos() != null) {
                // 实体移动后先释放旧区块，避免旧 ticket 永久残留
                if (this.sorterTicketChunk == null || !this.sorterTicketChunk.equals(chunkPos)) {
                    if (this.sorterTicketChunk != null) {
                        serverLevel.getChunkSource().removeRegionTicket(SORTER_TICKET, this.sorterTicketChunk, 1, this);
                    }
                    serverLevel.getChunkSource().addRegionTicket(SORTER_TICKET, chunkPos, 1, this);
                    this.sorterTicketChunk = chunkPos;
                }
                // 分类箱可能与分类者不在同一区块（工作区较大时），单独为它所在区块加 ticket，
                // 否则玩家离开后箱子区块被卸载，AI 会取不到容器
                this.updateBoxTicket(serverLevel);
            } else {
                // 如果切回休闲，释放区块加载
                if (this.sorterTicketChunk != null) {
                    serverLevel.getChunkSource().removeRegionTicket(SORTER_TICKET, this.sorterTicketChunk, 1, this);
                    this.sorterTicketChunk = null;
                }
                // 休闲状态下一律不持有箱子 ticket（setWorking 已释放，这里兜底）
                this.releaseBoxTicket(serverLevel);
            }

            // 跨维度防呆：绑定箱子的维度与实体当前维度不一致时自动解绑休眠
            this.validateBindingDimension();
            // 仓储雷达：箱子数据变化后节流推送给手持管理器的同维度玩家
            this.tickRadarSync(serverLevel);
        }
    }

    /** 分类箱所在区块的 ticket；工作状态下保持，休闲时释放 */
    private void updateBoxTicket(ServerLevel serverLevel) {
        BlockPos boxPos = this.isWorking() ? this.getSorterBoxPos() : null;
        ChunkPos desired = boxPos == null ? null : new ChunkPos(boxPos);
        if (desired == null) {
            this.releaseBoxTicket(serverLevel);
            return;
        }
        if (!desired.equals(this.boxTicketChunk)) {
            if (this.boxTicketChunk != null) {
                serverLevel.getChunkSource().removeRegionTicket(SORTER_TICKET, this.boxTicketChunk, 1, this);
            }
            serverLevel.getChunkSource().addRegionTicket(SORTER_TICKET, desired, 1, this);
            this.boxTicketChunk = desired;
        }
    }

    private void releaseBoxTicket(ServerLevel serverLevel) {
        if (this.boxTicketChunk != null) {
            serverLevel.getChunkSource().removeRegionTicket(SORTER_TICKET, this.boxTicketChunk, 1, this);
            this.boxTicketChunk = null;
        }
    }

    private void releaseSorterTicket(ServerLevel serverLevel) {
        if (this.sorterTicketChunk != null) {
            serverLevel.getChunkSource().removeRegionTicket(SORTER_TICKET, this.sorterTicketChunk, 1, this);
            this.sorterTicketChunk = null;
        }
    }

    /** 释放本实体持有的全部区块 ticket；读档上线与卸载时调用 */
    private void releaseAllTickets(ServerLevel serverLevel) {
        this.releaseSorterTicket(serverLevel);
        this.releaseBoxTicket(serverLevel);
    }

    /** 雷达同步间隔（tick） */
    private static final int RADAR_SYNC_INTERVAL = 40;
    /** 每个分类者在雷达里最多收录的箱子数量，避免包过大 */
    public static final int MAX_RADAR_ENTRIES = 512;
    /** 单次扫描 boxCategories 的条目上限；旧存档可能带几万个坐标，全量遍历会卡主线程 */
    public static final int MAX_SCAN_PER_CALL = 1000;
    /** 读档时超过该条目数视为异常数据，直接清空以保证存档可进入 */
    public static final int MAX_PERSISTED_CATEGORIES = 10000;
    /** 工作区最大体积（格数）；超限会让寻箱扫描和巨型线框把游戏拖死 */
    public static final int MAX_WORKSPACE_VOLUME = 32768;
    /** 世界坐标硬边界，与客户端输入框、服务端校验保持一致 */
    public static final int MAX_WORLD_COORD = 30_000_000;
    /** 游标渐进清理的单次检查量；只在字典超过 MAX_SCAN_PER_CALL 时启用 */
    private static final int MAX_CLEANUP_PER_CALL = 1000;
    /** 游标：只用于超大字典的渐进清理，不再影响 getManagedBoxes 的返回值 */
    private int scanCursor = 0;

    /** 工作区体积（格数），全程用 long 计算防溢出；任一向超限直接返回 Long.MAX_VALUE */
    public static long workspaceVolume(@Nullable BlockPos a, @Nullable BlockPos b) {
        if (a == null || b == null) {
            return 0L;
        }
        long dx = Math.abs((long) a.getX() - b.getX()) + 1L;
        long dy = Math.abs((long) a.getY() - b.getY()) + 1L;
        long dz = Math.abs((long) a.getZ() - b.getZ()) + 1L;
        if (dx > MAX_WORKSPACE_VOLUME || dy > MAX_WORKSPACE_VOLUME || dz > MAX_WORKSPACE_VOLUME) {
            return Long.MAX_VALUE;
        }
        return dx * dy * dz;
    }

    public static boolean isWorkspaceVolumeValid(@Nullable BlockPos a, @Nullable BlockPos b) {
        return workspaceVolume(a, b) <= MAX_WORKSPACE_VOLUME;
    }

    private void tickRadarSync(ServerLevel serverLevel) {
        if (++this.syncTickCounter < RADAR_SYNC_INTERVAL) {
            return;
        }
        this.syncTickCounter = 0;
        List<ManagedBox> boxes = this.getManagedBoxes();
        int signature = 17;
        signature = 31 * signature + boxes.size();
        for (ManagedBox box : boxes) {
            signature = 31 * signature + box.pos().hashCode();
            signature = 31 * signature + (box.category() == null ? 0 : box.category().hashCode());
        }
        if (signature == this.lastRadarSignature) {
            return; // 状态未变化，不重复发包
        }
        this.lastRadarSignature = signature;
        RadarSync.pushToManagerHolders(serverLevel, this);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // 搬运 AI：工作中且已绑定分类箱时执行
        this.goalSelector.addGoal(2, new SorterWorkGoal(this));
        // 休闲游荡 AI：不工作时随机走动
        // this.goalSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 0.8D));
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.validateBindingDimension();
        if (!this.isWorking() && !this.isManualPause() && this.getSorterBoxPos() != null) {
            this.wakeUpTimer++;
            if (this.wakeUpTimer >= 20) { // 每 1 秒检查一次
                this.wakeUpTimer = 0;
                this.refreshWorkingStateFromSorterBox();
            }
        } else {
            this.wakeUpTimer = 0;
        }
    }

    public void refreshWorkingStateFromSorterBox() {
        BlockPos boxPos = this.getSorterBoxPos();
        if (boxPos == null) {
            this.setWorking(false);
            return;
        }

        IItemHandler inv = this.getItemHandler(boxPos);
        boolean hasItems = false;
        if (inv != null) {
            for (int i = 0; i < inv.getSlots(); i++) {
                if (!inv.getStackInSlot(i).isEmpty()) {
                    hasItems = true;
                    break;
                }
            }
        }
        this.setWorking(hasItems);
    }

    public void triggerSort() {
        if (this.level.isClientSide || !(this.level instanceof ServerLevel)) {
            return;
        }
        if (this.getSorterBoxPos() == null) {
            this.level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, this.getBoundingBox().inflate(16.0D))
                    .forEach(p -> p.sendMessage(new net.minecraft.network.chat.TextComponent("§c分类者：尚未绑定分类箱"), p.getUUID()));
            return;
        }
        // 同一时间只允许一个整理任务，避免两次 /sort 同时清空仓库导致物品丢失
        if (this.activeSortTask != null && this.activeSortTask.isRunning()) {
            return;
        }
        this.activeSortTask = new WarehouseSorterTask(this);
        this.activeSortTask.start();
    }

    public boolean isSorting() {
        return this.activeSortTask != null && this.activeSortTask.isRunning();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_WORKING, false);
        this.entityData.define(DATA_SKIN_PLAYER, "");
        this.entityData.define(DATA_SORTER_BOX, Optional.empty());
        this.entityData.define(DATA_MANUAL_PAUSE, false);
    }

    public boolean isWorking() {
        return this.entityData.get(DATA_WORKING);
    }

    public void setWorking(boolean working) {
        this.entityData.set(DATA_WORKING, working);
        if (this.level.isClientSide) {
            return;
        }
        // 状态变化会让“分类箱”进出雷达，清零签名让下一次节流同步立即生效
        this.lastRadarSignature = 0;
        if (!working && this.level instanceof ServerLevel serverLevel) {
            this.releaseAllTickets(serverLevel);
        }
    }

    public boolean isManualPause() {
        return this.entityData.get(DATA_MANUAL_PAUSE);
    }

    public void setManualPause(boolean pause) {
        this.entityData.set(DATA_MANUAL_PAUSE, pause);
    }

    public void toggleWorking() {
        if (this.isWorking()) {
            this.setWorking(false);
            this.setManualPause(true); // 手动关闭，上锁
        } else {
            this.setWorking(true);
            this.setManualPause(false); // 手动开启，解锁
        }
        // 强制同步数据给客户端
        this.entityData.set(DATA_WORKING, this.isWorking());
    }

    public String getSkinPlayer() {
        return this.entityData.get(DATA_SKIN_PLAYER);
    }

    public void setSkinPlayer(String name) {
        this.entityData.set(DATA_SKIN_PLAYER, name);
    }

    @Nullable
    public BlockPos getSorterBoxPos() {
        return this.entityData.get(DATA_SORTER_BOX).orElse(null);
    }

    public void setSorterBoxPos(@Nullable BlockPos pos) {
        // 双箱兜底归一化：无论哪一半被选中，存入的永远是主半锚点。
        // 区块未加载时不做转换（getChestAnchor 会强制加载区块），读取时再归一化
        BlockPos normalized = pos;
        if (pos != null && this.level.hasChunkAt(pos)) {
            normalized = ContainerUtil.getChestAnchor(this.level, pos);
        }
        this.entityData.set(DATA_SORTER_BOX, Optional.ofNullable(normalized));
        if (normalized == null) {
            this.boundDimension = null;
        } else {
            this.boundDimension = this.level.dimension();
        }
        // 重新绑定分类箱不清空历史分类记录：雷达要保留之前搬过的箱子。
        // 被拆掉 / 清空的箱子由 getManagedBoxes() 扫描时顺带清理。
        this.invalidateRadarSignature();
    }

    /**
     * 校验分类箱绑定的维度。实体被传送 / 换维度后，旧坐标在当前世界没有意义，
     * 此时自动解绑并进入休闲状态，避免分类者对着空气空转。
     */
    public void validateBindingDimension() {
        if (this.level.isClientSide || this.boundDimension == null || this.getSorterBoxPos() == null) {
            return;
        }
        if (this.boundDimension.equals(this.level.dimension())) {
            return;
        }
        this.setSorterBoxPos(null);
        this.setWorking(false);
        this.setManualPause(false);
        this.level.getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(16.0D))
                .forEach(p -> p.sendMessage(new TextComponent(
                        "§c分类者 " + this.getName().getString() + " 的分类箱不在当前维度，已自动解绑"),
                        p.getUUID()));
        Classifier.LOGGER.debug("[Classifier] {} 跨维度绑定，已自动解绑", this.getName().getString());
    }

    /** 分类箱绑定时的维度；占用判定用它，不能用 level.dimension() 代替（实体搬维度后两者不一致） */
    @Nullable
    public ResourceKey<Level> getBoundDimension() {
        return this.boundDimension;
    }

    @Nullable
    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public String getOwnerName() {
        return this.ownerName;
    }

    public void setOwner(@Nullable UUID uuid, @Nullable String name) {
        this.ownerUuid = uuid;
        this.ownerName = name == null ? "" : name;
    }

    public boolean isOwnedBy(@Nullable UUID playerId) {
        return playerId != null && playerId.equals(this.ownerUuid);
    }

    /** 分类箱所在坐标（归一化到主半），雷达用它统计“分类箱”自身 */
    @Nullable
    public BlockPos getSorterBoxAnchor() {
        BlockPos pos = this.getSorterBoxPos();
        return pos == null ? null : ContainerUtil.getChestAnchor(this.level, pos);
    }

    // ===== 箱子存放类别 =====

    /** 雷达列表里的一行：箱子坐标、类别、是否为空箱、作为首选目标的次数；分类箱自身的 category 为 null */
    public record ManagedBox(BlockPos pos, @Nullable String category, boolean empty, int priority) {
    }

    @Nullable
    public String getBoxCategory(BlockPos pos) {
        return this.boxCategories.get(pos);
    }

    public void setBoxCategory(BlockPos pos, @Nullable String category) {
        if (category == null) {
            this.boxCategories.remove(pos);
        } else {
            this.boxCategories.put(pos, category);
        }
        this.invalidateRadarSignature();
    }

    /**
     * 首物自适应推断：纯净空箱收到第一件物品时，按标签 / 模组 / 物品推断箱子类别。
     * 返回推断出的类别（可能为 null）。
     */
    @Nullable
    public String assignBoxCategory(BlockPos pos, ItemStack firstItem) {
        if (firstItem.isEmpty()) {
            return null;
        }
        BlockPos anchor = ContainerUtil.getChestAnchor(this.level, pos);
        String category = CategoryRules.inferCategory(firstItem);
        if (!category.equals(this.boxCategories.get(anchor))) {
            this.boxCategories.put(anchor, category);
            this.invalidateRadarSignature();
            Classifier.LOGGER.debug("[Classifier] 自动开仓: {} -> {} ({})", anchor, CategoryRules.displayName(category),
                    ForgeRegistries.ITEMS.getKey(firstItem.getItem()));
        }
        return category;
    }

    /** 箱子里已没有自己类别的物品（被搬空或换了内容），撤销自动聚类，让它重新参与开仓 */
    public void releaseBoxCategory(BlockPos pos, @Nullable String category) {
        if (category == null) {
            return;
        }
        BlockPos anchor = ContainerUtil.getChestAnchor(this.level, pos);
        if (category.equals(this.boxCategories.get(anchor))) {
            this.boxCategories.remove(anchor);
            this.invalidateRadarSignature();
        }
    }

    /** 记录一次“被选为首选目标”，雷达按使用频率排序时使用 */
    public void bumpBoxUsage(BlockPos pos) {
        BlockPos anchor = ContainerUtil.getChestAnchor(this.level, pos);
        this.boxUsage.merge(anchor, 1, Integer::sum);
    }

    private void invalidateRadarSignature() {
        if (!this.level.isClientSide) {
            this.lastRadarSignature = 0;
        }
    }

    /**
     * 收集当前维度所有“已分配存放类型”的箱子：被记录过类别的箱子，
     * 加上当前非空的分类箱（分类箱里的物品通常来自混合来源，单独标注）。
     * 未加载区块、已消失或类型会被箱子自己改变的坐标会被顺带清理。
     */
    public List<ManagedBox> getManagedBoxes() {
        List<ManagedBox> result = new java.util.ArrayList<>();
        if (this.level.isClientSide) {
            return result;
        }
        int limit = MAX_RADAR_ENTRIES;
        // 只在工作中、且分类箱所在区块已加载时统计分类箱本身，避免强制加载区块
        BlockPos sourceAnchor = null;
        BlockPos sorterBox = this.getSorterBoxPos();
        if (sorterBox != null && this.isWorking() && this.level.hasChunkAt(sorterBox)) {
            sourceAnchor = ContainerUtil.getChestAnchor(this.level, sorterBox);
        }

        if (sourceAnchor != null) {
            ContainerUtil.ContainerSummary summary = ContainerUtil.summarize(this.level, sourceAnchor);
            if (summary != null && !summary.empty()) {
                result.add(new ManagedBox(sourceAnchor, null, false, 0));
            }
        }

        // 每次调用都从头遍历，保证返回的是当前维度完整列表；单次最多检查 MAX_SCAN_PER_CALL 条，
        // 旧存档里几万个坐标也不会一次全量压在主线程上
        int scanned = 0;
        var iterator = this.boxCategories.entrySet().iterator();
        while (iterator.hasNext() && scanned < MAX_SCAN_PER_CALL) {
            Map.Entry<BlockPos, String> entry = iterator.next();
            scanned++;
            BlockPos pos = entry.getKey();
            if (pos == null) {
                iterator.remove();
                continue;
            }
            if (!this.level.hasChunkAt(pos)) {
                continue; // 未加载不算消失，保留记录
            }
            // 双箱只记录主半锚点，避免左右半各占一条雷达
            BlockPos anchor = ContainerUtil.getChestAnchor(this.level, pos);
            ContainerUtil.ContainerSummary summary = ContainerUtil.summarize(this.level, anchor);
            if (summary == null) {
                iterator.remove(); // 箱子被拆掉，清理记录
                continue;
            }
            // 自动建立的“物品箱”在箱子被清空后不再算已分配类型
            String category = entry.getValue() == null ? "" : entry.getValue();
            if (category.startsWith(CategoryRules.PREFIX_ITEM) && summary.empty()) {
                iterator.remove();
                continue;
            }
            if (result.size() >= limit) {
                continue; // 已达雷达上限，但仍继续扫描以便顺带清理失效记录
            }
            result.add(new ManagedBox(anchor, category, summary.empty(), this.boxUsage.getOrDefault(anchor, 0)));
        }
        // 字典超过单次上限时，窗口外的条目由游标分帧清理，保持旧存档的防卡死意图
        if (this.boxCategories.size() > MAX_SCAN_PER_CALL) {
            this.cleanupScanWindow();
        } else {
            this.scanCursor = 0;
        }
        return result;
    }

    /** 游标分帧清理：每次检查 MAX_CLEANUP_PER_CALL 条，轮转覆盖单次扫描窗口之外的坐标 */
    private void cleanupScanWindow() {
        int index = 0;
        int checked = 0;
        // 本次主循环已从头部扫过 MAX_SCAN_PER_CALL 条，清理窗口从这里开始，避免重复读取容器
        int skip = Math.max(this.scanCursor, MAX_SCAN_PER_CALL);
        var iterator = this.boxCategories.entrySet().iterator();
        while (iterator.hasNext() && checked < MAX_CLEANUP_PER_CALL) {
            Map.Entry<BlockPos, String> entry = iterator.next();
            if (index++ < skip) {
                continue;
            }
            checked++;
            BlockPos pos = entry.getKey();
            if (pos == null) {
                iterator.remove();
                continue;
            }
            if (!this.level.hasChunkAt(pos)) {
                continue;
            }
            ContainerUtil.ContainerSummary summary = ContainerUtil.summarize(this.level, pos);
            if (summary == null) {
                iterator.remove();
                continue;
            }
            String category = entry.getValue() == null ? "" : entry.getValue();
            if (category.startsWith(CategoryRules.PREFIX_ITEM) && summary.empty()) {
                iterator.remove();
            }
        }
        this.scanCursor = index;
        // 本轮扫到尾部则从头部重新开始；清理会改变 size，按剩余量归一化防止游标越界
        if (!iterator.hasNext() || this.scanCursor >= this.boxCategories.size()) {
            this.scanCursor = 0;
        }
    }

    @Nullable
    public BlockPos getWorkspaceMin() {
        WorkspaceTable.Workspace workspace = getValidWorkspace();
        return workspace == null ? null : workspace.min();
    }

    @Nullable
    public BlockPos getWorkspaceMax() {
        WorkspaceTable.Workspace workspace = getValidWorkspace();
        return workspace == null ? null : workspace.max();
    }

    /** 绑定的工作区 UUID；未绑定时为 null */
    @Nullable
    public UUID getWorkspaceId() {
        return this.workspaceId;
    }

    /** 绑定的工作区对象；未绑定、跨维度、体积超限或读不到存档时返回 null */
    @Nullable
    public WorkspaceTable.Workspace getValidWorkspace() {
        if (this.workspaceId == null || this.level.isClientSide
                || !(this.level instanceof ServerLevel serverLevel)) {
            return null;
        }
        WorkspaceTable table = WorkspaceTable.get(serverLevel.getServer());
        WorkspaceTable.Workspace workspace = table.get(this.workspaceId);
        if (workspace == null) {
            return null;
        }
        // 工作区只在划定时的维度内有效：实体被搬走后必须重新划定才能使用
        if (!workspace.dimension().equals(serverLevel.dimension().location())) {
            return null;
        }
        return isWorkspaceVolumeValid(workspace.min(), workspace.max()) ? workspace : null;
    }

    /** 直接绑定到已有的工作区对象；只校验存在性，占用由调用方负责 */
    public boolean bindWorkspace(@Nullable UUID id) {
        if (id == null) {
            this.workspaceId = null;
            return true;
        }
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (WorkspaceTable.get(serverLevel.getServer()).get(id) == null) {
            return false;
        }
        this.workspaceId = id;
        return true;
    }

    /**
     * 划定 / 修改工作区。工作区对象化之后这里只做两件事：
     * 已经绑定过工作区时原地改它的坐标（保持 UUID 与名字，滚轮连续微调不会刷出一堆工作区），
     * 否则新建一个工作区对象再绑上去。体积超限直接拒绝。返回是否生效。
     */
    public boolean setWorkspace(@Nullable BlockPos min, @Nullable BlockPos max) {
        if (this.level.isClientSide || !(this.level instanceof ServerLevel serverLevel)) {
            // 客户端不持有工作区表，划定动作只在服务端执行
            return true;
        }
        WorkspaceTable table = WorkspaceTable.get(serverLevel.getServer());
        if (min == null || max == null) {
            this.workspaceId = null;
            return true;
        }
        if (!isWorkspaceVolumeValid(min, max)) {
            Classifier.LOGGER.warn("[Classifier] 工作区体积超过 {} 格，已拒绝: {} -> {}",
                    MAX_WORKSPACE_VOLUME, min, max);
            this.workspaceId = null;
            return false;
        }
        if (this.workspaceId != null && table.updateBounds(this.workspaceId,
                serverLevel.dimension().location(), min, max)) {
            return true;
        }
        WorkspaceTable.Workspace created = table.create(serverLevel.dimension().location(), min, max, null);
        this.workspaceId = created.id();
        return true;
    }

    /** 已存储的工作区是否因体积超限而被禁用（getWorkspaceMin/Max 此时返回 null，需要单独判定来发警告） */
    public boolean isWorkspaceOversized() {
        if (this.legacyWorkspaceMin != null && this.legacyWorkspaceMax != null) {
            return !isWorkspaceVolumeValid(this.legacyWorkspaceMin, this.legacyWorkspaceMax);
        }
        if (this.workspaceId == null || this.level.isClientSide
                || !(this.level instanceof ServerLevel serverLevel)) {
            return false;
        }
        WorkspaceTable.Workspace workspace = WorkspaceTable.get(serverLevel.getServer()).get(this.workspaceId);
        return workspace != null && !isWorkspaceVolumeValid(workspace.min(), workspace.max());
    }

    /**
     * 旧存档迁移：把实体上直接存的坐标转成一个工作区对象，然后清掉旧字段。
     *
     * 幂等性靠确定性 UUID：同一个分类者迁移出来的工作区 UUID 永远相同，
     * 所以即便迁移后没来得及存档、下次进世界再迁一次，也只会命中同一条记录。
     */
    public void migrateLegacyWorkspace(ServerLevel serverLevel) {
        if (this.legacyWorkspaceMin == null || this.legacyWorkspaceMax == null) {
            return;
        }
        BlockPos min = this.legacyWorkspaceMin;
        BlockPos max = this.legacyWorkspaceMax;
        this.legacyWorkspaceMin = null;
        this.legacyWorkspaceMax = null;
        ResourceKey<Level> legacyDimension = this.legacyWorkspaceDimension;
        this.legacyWorkspaceDimension = null;

        if (min.equals(max) || !isWorkspaceVolumeValid(min, max)) {
            Classifier.LOGGER.warn("[Classifier] {} 的旧工作区数据不可用（重合或超限），已丢弃",
                    this.getName().getString());
            return;
        }
        WorkspaceTable table = WorkspaceTable.get(serverLevel.getServer());
        UUID deterministic = UUID.nameUUIDFromBytes(
                ("classifier:sorter-workspace:" + this.getUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // 已经迁移过就原样保留，不会产生第二条
        table.putIfAbsent(new WorkspaceTable.Workspace(deterministic, null,
                (legacyDimension == null ? serverLevel.dimension() : legacyDimension).location(), min, max));
        if (this.workspaceId == null) {
            this.workspaceId = deterministic;
        }
        Classifier.LOGGER.debug("[Classifier] {} 的旧工作区已迁移为工作区对象 {}",
                this.getName().getString(), this.workspaceId);
    }

    public ItemStack getCarriedItem() {
        return this.carriedItem;
    }

    public void setCarriedItem(ItemStack stack) {
        this.carriedItem = stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.carriedItem.isEmpty()) {
            this.spawnAtLocation(this.carriedItem);
            this.carriedItem = ItemStack.EMPTY;
        }
        super.die(source);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (this.activeSortTask != null) {
            this.activeSortTask.cancel();
            this.activeSortTask = null;
        }
        if (!this.level.isClientSide && this.level instanceof ServerLevel serverLevel) {
            this.releaseAllTickets(serverLevel);
        }
        if (!this.carriedItem.isEmpty()) {
            this.spawnAtLocation(this.carriedItem);
            this.carriedItem = ItemStack.EMPTY;
        }
        super.remove(reason);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Shift+右键并手持管理器时由 ClassifierManagerItem 负责绑定，这里必须让路：
        // Player.interactOn 先调实体的 mobInteract，返回 SUCCESS 后物品的 interactLivingEntity 不会再执行
        if (player.isShiftKeyDown()
                && player.getItemInHand(hand).is(ItemInit.CLASSIFIER_MANAGER.get())) {
            return InteractionResult.PASS;
        }
        if (!this.level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openGui(serverPlayer, this, buf -> buf.writeInt(this.getId()));
        }
        return InteractionResult.sidedSuccess(this.level.isClientSide);
    }

    @Override
    public Component getDisplayName() {
        return this.getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new SorterMenu(windowId, inv, this.getId());
    }

    private IItemHandler getItemHandler(BlockPos pos) {
        var be = this.level.getBlockEntity(pos);
        // 双箱：通过 ChestBlock.getContainer 获取 54 格合并容器
        if (be instanceof ChestBlockEntity) {
            var state = this.level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock chestBlock) {
                var container = ChestBlock.getContainer(chestBlock, state, this.level, pos, true);
                if (container != null) {
                    return new InvWrapper(container);
                }
            }
        }
        // 木桶、潜影盒等
        return be != null ? be.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null).orElse(null) : null;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("SkinPlayer", this.getSkinPlayer());
        tag.putBoolean("Working", this.isWorking());
        tag.putBoolean("ManualPause", this.isManualPause());
        BlockPos boxPos = this.getSorterBoxPos();
        if (boxPos != null) {
            tag.putLong("SorterBoxPos", boxPos.asLong());
        }
        if (this.workspaceId != null) {
            tag.putString("WorkspaceId", this.workspaceId.toString());
        }
        // 还没迁移的旧数据原样写回：区块可能一直没加载，不能在这一步把坐标弄丢
        if (this.workspaceId == null && this.legacyWorkspaceMin != null && this.legacyWorkspaceMax != null) {
            tag.putLong("WorkspaceMin", this.legacyWorkspaceMin.asLong());
            tag.putLong("WorkspaceMax", this.legacyWorkspaceMax.asLong());
            if (this.legacyWorkspaceDimension != null) {
                tag.putString("WorkspaceDimension", this.legacyWorkspaceDimension.location().toString());
            }
        }
        if (this.boundDimension != null) {
            tag.putString("BoundDimension", this.boundDimension.location().toString());
        }
        if (this.ownerUuid != null) {
            tag.putString("Owner", this.ownerUuid.toString());
            tag.putString("OwnerName", this.ownerName);
        }
        ListTag categories = new ListTag();
        for (Map.Entry<BlockPos, String> entry : this.boxCategories.entrySet()) {
            CompoundTag boxTag = new CompoundTag();
            boxTag.putLong("Pos", entry.getKey().asLong());
            boxTag.putString("Category", entry.getValue());
            categories.add(boxTag);
        }
        tag.put("BoxCategories", categories);
        if (!this.carriedItem.isEmpty()) {
            tag.put("CarriedItem", this.carriedItem.save(new CompoundTag()));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        // 读档路径绝不能抛异常：旧存档可能已被其它模组写坏，任何一个字段解析失败
        // 都只该退化成默认值，否则服务端线程会直接崩溃、存档永远卡在加载
        this.pendingLoadTicketReset = true;
        try {
            if (tag.contains("SkinPlayer", Tag.TAG_STRING)) {
                this.setSkinPlayer(tag.getString("SkinPlayer"));
            }
            if (tag.contains("Working", Tag.TAG_BYTE)) {
                this.setWorking(tag.getBoolean("Working"));
            }
            if (tag.contains("ManualPause", Tag.TAG_BYTE)) {
                this.setManualPause(tag.getBoolean("ManualPause"));
            }
            this.setSorterBoxPos(readPos(tag, "SorterBoxPos"));

            this.boundDimension = readDimension(tag, "BoundDimension");

            this.ownerUuid = null;
            this.ownerName = "";
            if (tag.contains("Owner", Tag.TAG_STRING)) {
                try {
                    this.setOwner(UUID.fromString(tag.getString("Owner")),
                            tag.contains("OwnerName", Tag.TAG_STRING) ? tag.getString("OwnerName") : "");
                } catch (IllegalArgumentException ignored) {
                    // 损坏的 UUID 直接丢弃
                }
            }

            this.boxCategories.clear();
            this.scanCursor = 0;
            if (tag.contains("BoxCategories", Tag.TAG_LIST)) {
                ListTag categories = tag.getList("BoxCategories", Tag.TAG_COMPOUND);
                if (categories.size() > MAX_PERSISTED_CATEGORIES) {
                    Classifier.LOGGER.warn("[Classifier] {} 的 BoxCategories 条目异常({})，已清空以保证存档可进入",
                            this.getName().getString(), categories.size());
                } else {
                    for (int i = 0; i < categories.size(); i++) {
                        CompoundTag boxTag = categories.getCompound(i);
                        BlockPos pos = readPos(boxTag, "Pos");
                        if (pos == null) {
                            continue;
                        }
                        String category = boxTag.contains("Category", Tag.TAG_STRING)
                                ? boxTag.getString("Category") : null;
                        if (category == null || category.isEmpty()) {
                            continue;
                        }
                        this.boxCategories.put(pos, category);
                    }
                }
            }

            this.workspaceId = null;
            this.legacyWorkspaceMin = null;
            this.legacyWorkspaceMax = null;
            this.legacyWorkspaceDimension = null;
            if (tag.contains("WorkspaceId", Tag.TAG_STRING)) {
                try {
                    this.workspaceId = UUID.fromString(tag.getString("WorkspaceId"));
                } catch (IllegalArgumentException ignored) {
                    // 损坏的 UUID 直接丢弃，后续由旧字段迁移补齐
                }
            }
            BlockPos min = readPos(tag, "WorkspaceMin");
            BlockPos max = readPos(tag, "WorkspaceMax");
            if (this.workspaceId == null && min != null && max != null) {
                // 先暂存，首个服务端 tick 再迁移成工作区对象（迁移要拿到服务端存档层）
                this.legacyWorkspaceMin = min;
                this.legacyWorkspaceMax = max;
                this.legacyWorkspaceDimension = readDimension(tag, "WorkspaceDimension");
                if (!isWorkspaceVolumeValid(min, max)) {
                    Classifier.LOGGER.warn("[Classifier] {} 的旧工作区超过 {} 格，迁移时会丢弃",
                            this.getName().getString(), MAX_WORKSPACE_VOLUME);
                }
            }

            this.setCarriedItem(tag.contains("CarriedItem", Tag.TAG_COMPOUND)
                    ? ItemStack.of(tag.getCompound("CarriedItem")) : ItemStack.EMPTY);
        } catch (Exception e) {
            // 已读取的字段保持不变，未读取的停留在默认值；仅记录，不上抛
            Classifier.LOGGER.error("[Classifier] {} 读取存档数据失败，已使用安全默认值",
                    this.getName().getString(), e);
        }
    }

    /** 安全读取 BlockPos；缺失、越界或解析异常一律返回 null */
    @Nullable
    private static BlockPos readPos(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            return null;
        }
        long packed = tag.getLong(key);
        int x = BlockPos.getX(packed);
        int y = BlockPos.getY(packed);
        int z = BlockPos.getZ(packed);
        // BlockPos.of 会把 y 截断到 ±(1<<25)；越界坐标是坏数据，直接丢弃
        if (y < -2048 || y > 2047 || Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L) {
            return null;
        }
        try {
            return BlockPos.of(packed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 安全读取维度 key；缺失或格式非法返回 null（null 表示不做维度限制） */
    @Nullable
    private static ResourceKey<Level> readDimension(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return null;
        }
        String raw = tag.getString(key);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            return id == null ? null : ResourceKey.create(Registry.DIMENSION_REGISTRY, id);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
