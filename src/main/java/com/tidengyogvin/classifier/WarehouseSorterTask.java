package com.tidengyogvin.classifier;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仓库纠错与一键整理任务。
 *
 * 流程：分批扫描分类箱周围（或玩家设定的工作区）的容器 → 一次性收集全部物品并清空原箱 →
 * 按“同类/同 Tag”把同一物品重新堆叠 → 每种物品先计算所有目标箱的剩余容量，放得下的部分按
 * 已有同类 > 同 Tag > 空箱 > 任意有空位的顺序分配 → 放不下的回分类箱，分类箱也满则掉落在分类者脚下，
 * 并给附近玩家发红字警告。
 *
 * 性能：扫描与分配都通过 TickTask 每 tick 推进一小批（最多 2 个容器 / 32 次物品搬运动作），
 * 不会在单个 tick 内遍历或重排整个仓库。
 */
public class WarehouseSorterTask {

    /** 每 tick 扫描的容器数量 */
    private static final int CHESTS_PER_TICK = 2;
    /** 每 tick 的物品搬运动作上限（一次放满一个槽位算一次） */
    private static final int MOVES_PER_TICK = 32;
    private static final int DEFAULT_RADIUS = 16;
    private static final int DEFAULT_Y_RADIUS = 4;
    private static final String WARNING = "§c分类者：存在不合法物品或空间不足，请扩充箱子";

    private final SorterEntity sorter;

    // 0=扫描中，1=分配中，2=结束
    private int phase = 0;
    private boolean cancelled = false;

    private Iterator<BlockPos> scanIterator = null;
    private final List<BlockPos> containers = new ArrayList<>();
    private final Set<BlockPos> visited = new HashSet<>();

    private final List<ItemStack> inventory = new ArrayList<>();
    private Iterator<ItemStack> stackIterator = null;
    /** 因单 tick 搬运预算耗尽而挂起、下一批继续处理的物品堆 */
    @Nullable
    private ItemStack pendingStack = null;
    private final Map<String, BlockPos> preferred = new HashMap<>();
    private int movesThisTick = 0;
    private boolean overflow = false;

    public WarehouseSorterTask(SorterEntity sorter) {
        this.sorter = sorter;
    }

    public void start() {
        if (!(this.sorter.level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos source = this.sorter.getSorterBoxPos();
        if (source == null) {
            notifyPlayers("§c分类者：尚未绑定分类箱");
            this.phase = 2;
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            this.phase = 2;
            return;
        }

        BlockPos min;
        BlockPos max;
        if (this.sorter.isWorkspaceOversized()) {
            // 每次迭代起点都重新校验：旧存档里的超大工作区直接退化为默认 16 格范围
            notifyPlayers("§c工作区过大（上限 " + SorterEntity.MAX_WORKSPACE_VOLUME
                    + " 格），已退化为默认 16 格范围");
        }
        BlockPos workspaceMin = this.sorter.getWorkspaceMin();
        BlockPos workspaceMax = this.sorter.getWorkspaceMax();
        if (workspaceMin != null && workspaceMax != null) {
            min = workspaceMin;
            max = workspaceMax;
        } else {
            min = source.offset(-DEFAULT_RADIUS, -DEFAULT_Y_RADIUS, -DEFAULT_RADIUS);
            max = source.offset(DEFAULT_RADIUS, DEFAULT_Y_RADIUS, DEFAULT_RADIUS);
        }
        this.scanIterator = BlockPos.betweenClosed(min, max).iterator();
        this.phase = 0;
        Classifier.LOGGER.debug("[Classifier] 整理任务开始，范围 {} -> {}", min, max);
        scheduleNext(server);
    }

    public boolean isRunning() {
        return this.phase < 2 && !this.cancelled;
    }

    /** 分类者被移除等场景下放弃剩余写入 */
    public void cancel() {
        this.cancelled = true;
        this.phase = 2;
    }

    private void scheduleNext(MinecraftServer server) {
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, this::tick));
    }

    private void tick() {
        if (this.cancelled || this.phase >= 2) {
            return;
        }
        if (!(this.sorter.level instanceof ServerLevel serverLevel)) {
            this.phase = 2;
            return;
        }
        MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }
        this.movesThisTick = 0;

        boolean more;
        if (this.phase == 0) {
            more = scanStep(serverLevel);
        } else {
            more = assignStep(serverLevel);
        }
        if (more) {
            scheduleNext(server);
        }
    }

    // ===== 阶段 0：分批扫描并收集物品 =====

    private boolean scanStep(ServerLevel level) {
        BlockPos source = this.sorter.getSorterBoxPos();
        if (source == null) {
            notifyPlayers("§c分类者：分类箱已被移除，整理中止");
            this.phase = 2;
            return false;
        }
        BlockPos sourceAnchor = ContainerUtil.getChestAnchor(level, source);

        int processed = 0;
        while (this.scanIterator.hasNext() && processed < CHESTS_PER_TICK) {
            // 每次迭代前重新校验工作区：边界可能在任务运行中被改小/改坏
            if (!this.isWorkspaceStillValid()) {
                notifyPlayers("§c工作区体积异常，整理任务已中止");
                this.phase = 2;
                return false;
            }
            BlockPos pos = this.scanIterator.next();
            processed++;
            BlockPos anchor = ContainerUtil.getChestAnchor(level, pos);
            if (!this.visited.add(anchor) || anchor.equals(sourceAnchor)) {
                continue;
            }
            if (!isSortableContainer(level, anchor)) {
                continue;
            }
            this.containers.add(anchor);
        }

        if (this.scanIterator.hasNext()) {
            return true;
        }

        // 扫描完成：收集并清空所有目标箱
        for (BlockPos pos : this.containers) {
            Container container = ContainerUtil.getContainer(level, pos);
            if (container == null) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    this.inventory.add(stack.copy());
                    container.setItem(slot, ItemStack.EMPTY);
                }
            }
            container.setChanged();
        }
        this.scanIterator = null;
        this.stackIterator = this.inventory.iterator();
        this.phase = 1;
        Classifier.LOGGER.debug("[Classifier] 整理扫描完成，{} 个容器，{} 个物品堆", this.containers.size(), this.inventory.size());
        return true;
    }

    /** 整理任务运行期间工作区是否仍然合法（未设置时按默认范围，始终合法） */
    private boolean isWorkspaceStillValid() {
        BlockPos min = this.sorter.getWorkspaceMin();
        BlockPos max = this.sorter.getWorkspaceMax();
        return min == null || max == null || SorterEntity.isWorkspaceVolumeValid(min, max);
    }

    private boolean isSortableContainer(ServerLevel level, BlockPos pos) {        BlockState state = level.getBlockState(pos);
        return (state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof ShulkerBoxBlock)
                && level.getBlockEntity(pos) instanceof Container;
    }

    // ===== 阶段 1：分批分配 =====

    private boolean assignStep(ServerLevel level) {
        while (this.movesThisTick < MOVES_PER_TICK) {
            ItemStack stack = nextStack();
            if (stack == null) {
                break;
            }
            if (stack.isEmpty()) {
                continue;
            }
            if (getTotalCapacity(level, stack) <= 0) {
                // 没有任何容器接纳这种物品
                this.overflow |= routeToSource(level, stack);
                continue;
            }
            while (!stack.isEmpty() && this.movesThisTick < MOVES_PER_TICK) {
                BlockPos target = chooseTarget(level, stack);
                if (target == null) {
                    break;
                }
                placeInto(level, target, stack);
            }
            if (!stack.isEmpty()) {
                if (this.movesThisTick >= MOVES_PER_TICK) {
                    // 本 tick 预算用完：剩余部分留到下一批继续处理
                    this.pendingStack = stack;
                    break;
                }
                // 已经找不到目标（所有容器被放满）→ 剩余部分回分类箱
                this.overflow |= routeToSource(level, stack);
            }
        }

        boolean more = this.pendingStack != null || this.stackIterator.hasNext();
        if (more) {
            return true;
        }
        finish(level);
        return false;
    }

    /** 取下一个待处理堆叠：优先处理上次因预算耗尽而挂起的那一堆 */
    private ItemStack nextStack() {
        if (this.pendingStack != null) {
            ItemStack stack = this.pendingStack;
            this.pendingStack = null;
            return stack;
        }
        return this.stackIterator.hasNext() ? this.stackIterator.next() : null;
    }

    /** 所有目标箱对该物品的剩余容量总和 */
    private int getTotalCapacity(ServerLevel level, ItemStack sample) {
        int total = 0;
        for (BlockPos pos : this.containers) {
            total += getCapacity(level, pos, sample);
        }
        return total;
    }

    /**
     * 单个箱子的既兼容又有空位的容量：已有同类物品剩余空间 + 可放置的空槽上限。
     * 箱子里一旦存在“不同类且不同 Tag”的杂质，就视为不兼容，返回 0。
     */
    private int getCapacity(ServerLevel level, BlockPos pos, ItemStack sample) {
        Container container = ContainerUtil.getContainer(level, pos);
        if (container == null) {
            return 0;
        }
        int max = sample.getMaxStackSize();
        int capacity = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                if (container.canPlaceItem(slot, sample)) {
                    capacity += max;
                }
            } else if (ItemStack.isSameItemSameTags(existing, sample)) {
                capacity += Math.max(0, max - existing.getCount());
            } else if (!isCompatible(existing, sample)) {
                return 0;
            }
        }
        return capacity;
    }

    /** 同 Tag 或同类食物视为兼容 */
    private boolean isCompatible(ItemStack existing, ItemStack sample) {
        if (existing.getItem().isEdible() && sample.getItem().isEdible()) {
            return true;
        }
        return sample.getTags().anyMatch(tag -> existing.getTags().anyMatch(t -> t.equals(tag)));
    }

    /**
     * 选择目标箱：上次命中的箱子 → 已有同类 → 同 Tag → 空箱 → 其他有空位的箱子。
     * 只选择当前能放下至少一个的箱子；放满后下次调用会重新评估。
     */
    @Nullable
    private BlockPos chooseTarget(ServerLevel level, ItemStack sample) {
        BlockPos cached = this.preferred.get(keyOf(sample));
        if (cached != null && getCapacity(level, cached, sample) > 0) {
            return cached;
        }
        BlockPos sameItem = null;
        BlockPos sameTag = null;
        BlockPos empty = null;
        BlockPos anyRoom = null;
        for (BlockPos pos : this.containers) {
            int capacity = getCapacity(level, pos, sample);
            if (capacity <= 0) {
                continue;
            }
            Container container = ContainerUtil.getContainer(level, pos);
            if (container == null) {
                continue;
            }
            boolean hasSameItem = false;
            boolean hasTag = false;
            boolean isEmpty = true;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()) {
                    continue;
                }
                isEmpty = false;
                if (ItemStack.isSameItemSameTags(existing, sample)) {
                    hasSameItem = true;
                } else if (isCompatible(existing, sample)) {
                    hasTag = true;
                }
            }
            if (hasSameItem && sameItem == null) {
                sameItem = pos;
            } else if (hasTag && sameTag == null) {
                sameTag = pos;
            } else if (isEmpty && empty == null) {
                empty = pos;
            } else if (anyRoom == null) {
                anyRoom = pos;
            }
        }
        BlockPos result = sameItem != null ? sameItem
                : sameTag != null ? sameTag
                : empty != null ? empty
                : anyRoom;
        if (result != null) {
            this.preferred.put(keyOf(sample), result);
        }
        return result;
    }

    /** 放入目标箱，返回实际放入数量 */
    private int placeInto(ServerLevel level, BlockPos pos, ItemStack stack) {
        Container container = ContainerUtil.getContainer(level, pos);
        if (container == null) {
            return 0;
        }
        int max = stack.getMaxStackSize();
        int placed = 0;
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                if (!container.canPlaceItem(slot, stack)) {
                    continue;
                }
                int amount = Math.min(max, stack.getCount());
                ItemStack placedStack = stack.copy();
                placedStack.setCount(amount);
                container.setItem(slot, placedStack);
                stack.setCount(stack.getCount() - amount);
                placed += amount;
                this.movesThisTick++;
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                int amount = Math.min(max - existing.getCount(), stack.getCount());
                if (amount > 0) {
                    existing.grow(amount);
                    container.setItem(slot, existing);
                    stack.setCount(stack.getCount() - amount);
                    placed += amount;
                    this.movesThisTick++;
                }
            }
        }
        container.setChanged();
        return placed;
    }

    /** 兜底：先塞回分类箱，塞不下则掉落到分类者位置。返回是否触发了“放不下” */
    private boolean routeToSource(ServerLevel level, ItemStack stack) {
        boolean overflowed = false;
        BlockPos source = this.sorter.getSorterBoxPos();
        if (source != null) {
            Container container = ContainerUtil.getContainer(level, source);
            if (container != null) {
                int max = stack.getMaxStackSize();
                for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                    ItemStack existing = container.getItem(slot);
                    if (existing.isEmpty()) {
                        int amount = Math.min(max, stack.getCount());
                        ItemStack placedStack = stack.copy();
                        placedStack.setCount(amount);
                        container.setItem(slot, placedStack);
                        stack.setCount(stack.getCount() - amount);
                    } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                        int amount = Math.min(max - existing.getCount(), stack.getCount());
                        if (amount > 0) {
                            existing.grow(amount);
                            container.setItem(slot, existing);
                            stack.setCount(stack.getCount() - amount);
                        }
                    }
                }
                container.setChanged();
            }
        }
        if (!stack.isEmpty()) {
            this.sorter.spawnAtLocation(stack.copy());
            stack.setCount(0);
            overflowed = true;
        }
        return overflowed;
    }

    private String keyOf(ItemStack stack) {
        // 只用于“上次命中的箱子”缓存，用物品 + NBT 内容做键即可
        return stack.getItem().getRegistryName() + "|" + stack.getTag();
    }

    private void finish(ServerLevel level) {
        this.phase = 2;
        if (this.overflow) {
            notifyPlayers(WARNING);
        } else {
            notifyPlayers("§a分类者：整理完成，共整理 " + this.containers.size() + " 个容器");
        }
        BlockPos source = this.sorter.getSorterBoxPos();
        if (source != null) {
            level.playSound(null, source, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 0.9F);
        }
        this.inventory.clear();
        Classifier.LOGGER.debug("[Classifier] 整理任务结束");
    }

    private void notifyPlayers(String message) {
        Component component = new TextComponent(message).withStyle(ChatFormatting.WHITE);
        this.sorter.level.getEntitiesOfClass(
                ServerPlayer.class,
                this.sorter.getBoundingBox().inflate(32.0D),
                EntitySelector.NO_SPECTATORS
        ).forEach(player -> player.sendMessage(component, player.getUUID()));
    }
}
