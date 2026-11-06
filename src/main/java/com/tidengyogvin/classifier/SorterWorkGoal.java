package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.EnumSet;

public class SorterWorkGoal extends Goal {
    private final SorterEntity sorter;
    private int state = 0; // 0=待机, 1=去分类箱拿, 2=去目标箱放, 3=冷却
    private int cooldown = 0;
    private long lastWarningTick = 0;
    /** 工作区超限警告的节流 tick */
    private long lastWorkspaceWarnTick = 0;
    private BlockPos targetBoxPos = null;
    // 开盖调度：记录“应保持开盖”的绝对 tick，0 表示该箱子当前不应保持开盖
    private BlockPos openBoxPos = null;
    private long openHoldUntil = 0;

    public SorterWorkGoal(SorterEntity sorter) {
        this.sorter = sorter;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public boolean canUse() {
        return this.sorter.isWorking() && this.sorter.getSorterBoxPos() != null && !this.sorter.isManualPause();
    }

    @Override
    public void start() { 
        this.state = 0; 
        this.cooldown = 0; 
        Classifier.LOGGER.debug("[Classifier] Goal Start! 当前坐标: {}", this.sorter.blockPosition());
    }

    @Override
    public void stop() {
        this.sorter.getNavigation().stop();
        closeOpenChestNow();
        this.returnCarriedItem();
        this.state = 0;
        this.targetBoxPos = null;
        Classifier.LOGGER.debug("[Classifier] Goal Stop!");
    }

    @Override
    public void tick() {
        closeOpenChestIfDue();

        if (this.cooldown > 0) {
            this.cooldown--;
            return;
        }

        BlockPos sorterBoxPos = this.sorter.getSorterBoxPos();
        if (sorterBoxPos == null) return;

        // 状态 0：先找目标箱
        if (this.state == 0) {
            IItemHandler sourceInv = getItemHandler(sorterBoxPos);
            if (sourceInv == null) {
                Classifier.LOGGER.warn("[Classifier] 状态0: 无法获取分类箱的 IItemHandler! 坐标: {}", sorterBoxPos);
                this.cooldown = 40;
                return;
            }

            boolean sourceEmpty = true;
            for (int i = 0; i < sourceInv.getSlots(); i++) {
                if (!sourceInv.getStackInSlot(i).isEmpty()) {
                    sourceEmpty = false;
                    break;
                }
            }

            if (sourceEmpty) {
                if (this.sorter.getCarriedItem().isEmpty()) {
                    this.sorter.setWorking(false); // 确保手里没东西才休眠
                }
                this.cooldown = 40;
                return;
            }

            if (this.sorter.getCarriedItem().isEmpty()) {
                for (int i = 0; i < sourceInv.getSlots(); i++) {
                    ItemStack stack = sourceInv.extractItem(i, 64, true);
                    if (!stack.isEmpty()) {
                        // 找到了物品，去找目标箱
                        this.targetBoxPos = findTargetChest(sorterBoxPos, stack);
                        if (this.targetBoxPos != null) {
                            // 找到了，取出物品，进入状态1
                            this.sorter.setCarriedItem(sourceInv.extractItem(i, 64, false));
                            this.sorter.bumpBoxUsage(this.targetBoxPos);
                            Classifier.LOGGER.debug("[Classifier] 状态0->1: 拿到物品 {}, 找到目标箱 {}, 准备走向分类箱 {}", stack.getItem(), this.targetBoxPos, sorterBoxPos);
                            this.state = 1;
                            this.sorter.getNavigation().moveTo(sorterBoxPos.getX() + 0.5, sorterBoxPos.getY(), sorterBoxPos.getZ() + 0.5, 1.0D);
                        } else {
                            Classifier.LOGGER.debug("[Classifier] 状态0: 发现物品 {}, 但在附近找不到任何有空位的容器！进入冷却", stack.getItem());
                            this.cooldown = 40;
                            warnNeedMoreChests(sorterBoxPos);
                        }
                        break;
                    }
                }
            }
        }
        // 状态 1：走到分类箱面前，假装拿东西，然后去目标箱
        else if (this.state == 1) {
            double distToSource = this.sorter.distanceToSqr(sorterBoxPos.getX() + 0.5, sorterBoxPos.getY() + 0.5, sorterBoxPos.getZ() + 0.5);
            
            // 如果离得很近，或者寻路卡住（isDone 为 true），直接交互
            if (distToSource <= 6.25D || this.sorter.getNavigation().isDone()) {
                this.sorter.getNavigation().stop();
                // 模拟动画：开盖 15 tick 后由 tick() 自动合上
                openChestFor(sorterBoxPos, 15);

                Classifier.LOGGER.debug("[Classifier] 状态1->2: 到达(或放弃)分类箱, 准备走向目标箱 {}", this.targetBoxPos);
                this.state = 2;
                this.sorter.getNavigation().moveTo(this.targetBoxPos.getX() + 0.5, this.targetBoxPos.getY(), this.targetBoxPos.getZ() + 0.5, 1.0D);
            }
        }
        // 状态 2：走到目标箱面前，放东西
        else if (this.state == 2) {
            if (this.targetBoxPos == null) { this.state = 0; return; }
            double distToTarget = this.sorter.distanceToSqr(this.targetBoxPos.getX() + 0.5, this.targetBoxPos.getY() + 0.5, this.targetBoxPos.getZ() + 0.5);
            
            if (distToTarget <= 6.25D || this.sorter.getNavigation().isDone()) {
                this.sorter.getNavigation().stop();
                // 模拟动画：开盖 15 tick 后由 tick() 自动合上
                openChestFor(this.targetBoxPos, 15);

                IItemHandler targetInv = getItemHandler(this.targetBoxPos);
                if (targetInv != null) {
                    ItemStack carried = this.sorter.getCarriedItem();
                    // 纯净空箱：放入前先根据首件物品推断箱子类别，之后同类物品自动优先进入
                    if (!carried.isEmpty() && isContainerEmpty(targetInv)) {
                        this.sorter.assignBoxCategory(this.targetBoxPos, carried);
                    }
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(targetInv, carried, false);
                    this.sorter.setCarriedItem(remainder);
                    String category = this.sorter.getBoxCategory(this.targetBoxPos);
                    if (category != null && category.startsWith(CategoryRules.PREFIX_ITEM)) {
                        // 自动建立的“普通物品箱”被搬空后撤销记录，允许下次重新开仓
                        ContainerUtil.ContainerSummary summary = ContainerUtil.summarize(this.sorter.level, this.targetBoxPos);
                        if (summary != null && summary.empty()) {
                            this.sorter.releaseBoxCategory(this.targetBoxPos, category);
                        }
                    }
                }

                Classifier.LOGGER.debug("[Classifier] 状态2->0: 放入目标箱完毕, 剩余物品: {}", this.sorter.getCarriedItem().getItem());
                this.state = 0;
                this.targetBoxPos = null;
                this.cooldown = 20;
            }
        }
    }

    private BlockPos findTargetChest(BlockPos excludePos, ItemStack sampleItem) {
        BlockPos bestPos = null;
        int bestScore = -1;
        double bestDist = Double.MAX_VALUE;

        // 收集其他分类者绑定的箱子，防倒手（双箱的左右两半视为同一个箱子）
        java.util.List<SorterEntity> otherSorters = this.sorter.level.getEntitiesOfClass(
            SorterEntity.class, this.sorter.getBoundingBox().inflate(32.0D)
        );
        java.util.Set<BlockPos> claimedBoxes = new java.util.HashSet<>();
        for (SorterEntity other : otherSorters) {
            if (other != this.sorter && other.getSorterBoxPos() != null) {
                claimedBoxes.add(ContainerUtil.getChestAnchor(this.sorter.level, other.getSorterBoxPos()));
            }
        }
        BlockPos sourceAnchor = ContainerUtil.getChestAnchor(this.sorter.level, excludePos);
        BlockPos sourceOtherHalf = getConnectedChestPos(excludePos);
        java.util.Set<BlockPos> visitedAnchors = new java.util.HashSet<>();

        // 玩家设置了工作区时，严格限定在 betweenClosed(min, max) 内，不再使用分类箱周围的默认范围。
        // 每次迭代前校验体积：旧存档的超大工作区直接退化为默认范围，防止巨量遍历卡死服务端
        BlockPos workspaceMin = this.sorter.getWorkspaceMin();
        BlockPos workspaceMax = this.sorter.getWorkspaceMax();
        if (this.sorter.isWorkspaceOversized()) {
            if (this.sorter.level.getGameTime() - this.lastWorkspaceWarnTick > 200) {
                this.lastWorkspaceWarnTick = this.sorter.level.getGameTime();
                warnWorkspaceTooLarge();
            }
        }
        Iterable<BlockPos> candidates = workspaceMin != null && workspaceMax != null
                ? BlockPos.betweenClosed(workspaceMin, workspaceMax)
                : BlockPos.betweenClosed(excludePos.offset(-16, -4, -16), excludePos.offset(16, 4, 16));

        for (BlockPos pos : candidates) {
            // 先归一化再做去重/排除，双箱只会被评估一次
            BlockPos anchor = ContainerUtil.getChestAnchor(this.sorter.level, pos);
            if (anchor.equals(sourceAnchor) || anchor.equals(sourceOtherHalf)) continue;
            if (claimedBoxes.contains(anchor)) continue;
            if (!visitedAnchors.add(anchor)) continue;

            IItemHandler inv = getItemHandler(anchor);
            if (inv == null) continue;

            String category = this.sorter.getBoxCategory(anchor);
            boolean empty = isContainerEmpty(inv);
            // 专用空箱（矿物箱 / Create 专属箱）只接收命中类别的物品，否则会被其它物品“污染”
            if (empty && CategoryRules.isReservedCategory(category) && !CategoryRules.matches(category, sampleItem)) {
                continue;
            }

            int score = getContainerScore(inv, sampleItem, category, empty);
            if (score <= 0) continue; // 0分代表装满或有杂质，直接跳过

            double dist = anchor.distSqr(sourceAnchor);
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                bestScore = score;
                bestDist = dist;
                bestPos = anchor;
            }
        }
        return bestPos;
    }

    /** 若该坐标是双箱的一半，返回另一半坐标；否则返回 null */
    private BlockPos getConnectedChestPos(BlockPos pos) {
        var state = this.sorter.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
        }
        return null;
    }

    private boolean isContainerEmpty(IItemHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 打分机制（从高到低）：
     * - 命中箱子标签 / 模组类别：1000 分（标签箱最高优先，纯净空箱与已装同类物品都适用）
     * - 箱子内已有同类物品：100 分，且同类物品一定来自同一模组，再加同模组加分
     * - 同 Tag / 同为食物：50 分
     * - 纯净空箱：10 分
     * - 同模组命名空间（非 minecraft）：额外 +30 分
     * - 混装有杂质：0 分淘汰
     */
    private int getContainerScore(IItemHandler inv, ItemStack sampleItem, String category, boolean empty) {
        boolean hasSameItem = false;
        boolean hasSameTag = false;
        boolean sameModNamespace = false;
        boolean hasRoom = false;

        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                if (ItemStack.isSameItemSameTags(stack, sampleItem)) {
                    hasSameItem = true;
                } else if (sampleItem.getTags().anyMatch(tag -> stack.getTags().anyMatch(t -> t.equals(tag)))
                    || (sampleItem.getItem().isEdible() && stack.getItem().isEdible())) {
                    hasSameTag = true;
                } else {
                    // 发现不兼容物品（比如箱子里有泥土，你要放生肉），直接给0分，防止混装！
                    return 0;
                }
                if (CategoryRules.sameNonVanillaNamespace(stack, sampleItem)) {
                    sameModNamespace = true;
                }
            }
            if (!hasRoom && inv.insertItem(i, sampleItem.copy(), true).isEmpty()) {
                hasRoom = true;
            }
        }

        if (!hasRoom) return 0; // 满箱淘汰
        int score;
        if (CategoryRules.isReservedCategory(category) && CategoryRules.matches(category, sampleItem)) {
            // 标签箱 / 模组箱：命中类别（含已装同类物品）一律最高分
            score = CategoryRules.TAG_SCORE;
        } else if (hasSameItem) {
            score = 100;
        } else if (hasSameTag) {
            score = 50;
        } else if (empty) {
            score = 10;
        } else {
            return 0;
        }
        // 同模组加分：Create 的物品只往 Create 箱里堆，避免混入原版/其它模组箱子
        if (sameModNamespace) {
            score += CategoryRules.MOD_BONUS;
        }
        return score;
    }

    private IItemHandler getItemHandler(BlockPos pos) {
        // 每次都重新读取 BlockState：单箱在绑定后扩容成双箱时，这里会自动拿到合并后的 54 格；
        // 传入左半或右半都能归一化到主半，避免两个半箱引用不同实例。
        var container = ContainerUtil.getContainer(this.sorter.level, pos);
        if (container == null) {
            return null;
        }
        return new net.minecraftforge.items.wrapper.InvWrapper(container);
    }

    /**
     * 开盖并保持 holdTicks 个 tick，到时间后由 tick() 里的 closeOpenChestIfDue 关闭。
     * 由 tick() 驱动关闭，避免依赖方块的 tick 状态（分类者运行时箱子所在区块被强加载）。
     */
    private void openChestFor(BlockPos pos, int holdTicks) {
        if (pos == null) return;
        long now = this.sorter.level.getGameTime();
        // 换箱子时先把上一个箱子合上，避免盖子一直开着
        if (this.openBoxPos != null && !this.openBoxPos.equals(pos)) {
            closeOpenChestNow();
        }
        this.openBoxPos = pos.immutable();
        this.openHoldUntil = now + holdTicks;
        playChestAnimation(pos, true);
        // 兜底：若实体异常移除导致 tick() 不再执行，也保证箱子按时合上（closeOpenChestIfDue 已关则此处为空操作）
        final BlockPos openedPos = this.openBoxPos;
        if (this.sorter.level.getServer() != null) {
            this.sorter.level.getServer().tell(new net.minecraft.server.TickTask(
                    this.sorter.level.getServer().getTickCount() + holdTicks + 1, () -> {
                        if (this.openBoxPos != null && this.openBoxPos.equals(openedPos)
                                && this.sorter.level.getGameTime() >= this.openHoldUntil) {
                            closeOpenChestNow();
                        }
                    }));
        }
    }

    private void closeOpenChestIfDue() {
        if (this.openBoxPos == null) return;
        if (this.sorter.level.getGameTime() >= this.openHoldUntil) {
            closeOpenChestNow();
        }
    }

    private void closeOpenChestNow() {
        if (this.openBoxPos == null) return;
        BlockPos closedPos = this.openBoxPos;
        this.openBoxPos = null;
        this.openHoldUntil = 0;
        playChestAnimation(closedPos, false);
        // 合盖音效与开盖位置不同，单独播放
        playLidSound(closedPos, false);
    }

    /**
     * 驱动原版箱子盖动画。1.18.2 的官方链路：
     * BlockState.triggerEvent → BlockBehaviour（BaseEntityBlock 覆写）转发给 BlockEntity
     * → ChestBlockEntity.triggerEvent(1, n) → ChestLidController.shouldBeOpen(n > 0)，
     * 客户端每 tick 的 lidAnimateTick 平滑过渡 openness。
     * 客户端同步走原版 ClientboundBlockEventPacket 广播（与原版开箱完全一致），无需额外的自定义包。
     */
    private void playChestAnimation(BlockPos pos, boolean open) {
        if (pos == null) return;
        if (!(this.sorter.level instanceof ServerLevel serverLevel)) return;
        // 服务端直接调用 chest.triggerEvent 不会同步到客户端，必须同时下发原版 block event 包
        triggerChestLid(serverLevel, pos, open);
        // 双箱是独立的两个 BlockEntity，渲染时取两半 openness 的较大值，因此两半都要触发
        BlockState state = serverLevel.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos otherHalf = pos.relative(ChestBlock.getConnectedDirection(state));
                if (serverLevel.getBlockState(otherHalf).getBlock() instanceof ChestBlock) {
                    triggerChestLid(serverLevel, otherHalf, open);
                }
            }
        }
        // 开盖音效只对交互的那一半播放
        if (open) {
            playLidSound(pos, true);
        }
    }

    /** 复刻原版 ChestBlockEntity.playSound 的音量与随机音高 */
    private void playLidSound(BlockPos pos, boolean open) {
        this.sorter.level.playSound(null, pos,
                open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE,
                SoundSource.BLOCKS, 0.5F, this.sorter.level.random.nextFloat() * 0.1F + 0.9F);
    }

    private void triggerChestLid(ServerLevel serverLevel, BlockPos pos, boolean open) {
        BlockState state = serverLevel.getBlockState(pos);
        int param = open ? 1 : 0;
        // 服务端立即生效，与 ServerLevel.runBlockEvents 内部对 doBlockEvent 的处理一致
        state.triggerEvent(serverLevel, pos, 1, param);
        // 按原版 ClientboundBlockEventPacket 格式同步 64 格内的客户端（等同原版 runBlockEvents 的广播）
        ClientboundBlockEventPacket packet = new ClientboundBlockEventPacket(pos, state.getBlock(), 1, param);
        serverLevel.getServer().getPlayerList().broadcast(null, pos.getX(), pos.getY(), pos.getZ(), 64.0D, serverLevel.dimension(), packet);
    }

    private void warnWorkspaceTooLarge() {
        String msg = "§c分类者 " + this.sorter.getName().getString()
                + "：工作区过大（上限 " + SorterEntity.MAX_WORKSPACE_VOLUME + " 格），已退化为默认 16 格范围";
        this.sorter.level.getEntitiesOfClass(
                net.minecraft.world.entity.player.Player.class,
                this.sorter.getBoundingBox().inflate(32.0D)
        ).forEach(p -> p.sendMessage(new net.minecraft.network.chat.TextComponent(msg), p.getUUID()));
    }

    private void warnNeedMoreChests(BlockPos sorterBoxPos) {
        long currentTick = this.sorter.level.getGameTime();
        if (currentTick - this.lastWarningTick <= 200) {
            return;
        }
        this.lastWarningTick = currentTick;

        String boxName = this.sorter.level.getBlockState(sorterBoxPos).getBlock().getName().getString();
        String sorterName = this.sorter.getName().getString();
        String msg = String.format("§c%s：%s中存在其他类型的物品，请扩充箱子", sorterName, boxName);

        this.sorter.level.getEntitiesOfClass(
                net.minecraft.world.entity.player.Player.class,
                this.sorter.getBoundingBox().inflate(32.0D)
        ).forEach(p -> p.sendMessage(new net.minecraft.network.chat.TextComponent(msg), p.getUUID()));
    }

    private void returnCarriedItem() {
        if (this.sorter.getCarriedItem().isEmpty()) return;
        BlockPos sorterBoxPos = this.sorter.getSorterBoxPos();
        IItemHandler inv = sorterBoxPos != null ? getItemHandler(sorterBoxPos) : null;
        if (inv != null) {
            this.sorter.setCarriedItem(ItemHandlerHelper.insertItemStacked(inv, this.sorter.getCarriedItem(), false));
        }
        if (!this.sorter.getCarriedItem().isEmpty()) {
            this.sorter.spawnAtLocation(this.sorter.getCarriedItem());
            this.sorter.setCarriedItem(ItemStack.EMPTY);
        }
    }
}
