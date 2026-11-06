package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 绑定占用校验：一个分类箱、一片工作区，任一时刻只允许一台分类者持有。
 *
 * 真值全部来自服务端实体字段（SorterEntity 的 DATA_SORTER_BOX / workspaceMin+Max），
 * 手持管理器 NBT 只是输入源与回显，不参与判定。
 *
 * 只在玩家绑定 / 保存工作区的动作里调用，不参与 tick；单次扫描有硬上限，
 * 超限按“无人占用”放行并记日志，宁可放行也不让服务端卡在遍历里。
 *
 * 已知限制：占用是从“已加载实体”现算的。分类者空闲时不持有区块 ticket，
 * 区块卸载后它就从扫描里消失，此时它占用的目标会被当成无人占用。
 */
public final class SorterOccupancy {

    /** 单次扫描检查的实体上限 */
    private static final int MAX_ENTITY_SCAN = 8192;

    /** 工作区的归一化键：两个角点按分量排序，A→B 与 B→A 视为同一片地 */
    public record WorkspaceKey(BlockPos min, BlockPos max) {

        public static WorkspaceKey of(BlockPos a, BlockPos b) {
            return new WorkspaceKey(
                    new BlockPos(Math.min(a.getX(), b.getX()),
                            Math.min(a.getY(), b.getY()),
                            Math.min(a.getZ(), b.getZ())),
                    new BlockPos(Math.max(a.getX(), b.getX()),
                            Math.max(a.getY(), b.getY()),
                            Math.max(a.getZ(), b.getZ())));
        }
    }

    /** 一次扫描得到的占用表；只在当前绑定动作内有效，不跨调用缓存 */
    public static final class Occupancy {

        private final Map<BlockPos, SorterEntity> boxes = new HashMap<>();
        private final Map<WorkspaceKey, SorterEntity> workspaces = new HashMap<>();
        private boolean truncated;

        @Nullable
        public SorterEntity boxOccupant(@Nullable BlockPos anchor) {
            return anchor == null ? null : this.boxes.get(anchor);
        }

        @Nullable
        public SorterEntity workspaceOccupant(@Nullable BlockPos pos1, @Nullable BlockPos pos2) {
            return (pos1 == null || pos2 == null)
                    ? null
                    : this.workspaces.get(WorkspaceKey.of(pos1, pos2));
        }

        /** 扫描是否因达到上限而提前结束；为真时可能漏判占用 */
        public boolean isTruncated() {
            return this.truncated;
        }

        private void recordBox(ServerLevel level, SorterEntity sorter) {
            BlockPos box = sorter.getSorterBoxPos();
            if (box == null) {
                return;
            }
            // 绑定在别的维度上的坐标在当前世界没有意义，不算占用
            ResourceKey<Level> bound = sorter.getBoundDimension();
            if (bound != null && !bound.equals(level.dimension())) {
                return;
            }
            this.boxes.putIfAbsent(anchorOf(level, box), sorter);
        }

        private void recordWorkspace(SorterEntity sorter) {
            // getWorkspaceMin/Max 在“跨维度”或“体积超限”时返回 null，这里天然跳过
            BlockPos min = sorter.getWorkspaceMin();
            BlockPos max = sorter.getWorkspaceMax();
            if (min == null || max == null) {
                return;
            }
            this.workspaces.putIfAbsent(WorkspaceKey.of(min, max), sorter);
        }
    }

    private SorterOccupancy() {
    }

    /** 扫一遍当前维度的已加载实体，构建占用表；遍历数有硬上限 */
    public static Occupancy scan(ServerLevel level) {
        Occupancy occupancy = new Occupancy();
        int scanned = 0;
        for (Entity entity : level.getAllEntities()) {
            if (scanned++ >= MAX_ENTITY_SCAN) {
                occupancy.truncated = true;
                break;
            }
            // 死掉 / 已被移除的分类者不算占用：它已经被删了，占着位置只会让别人绑不上
            if (!(entity instanceof SorterEntity sorter) || !ManagerData.isLiveSorter(sorter)) {
                continue;
            }
            occupancy.recordBox(level, sorter);
            occupancy.recordWorkspace(sorter);
        }
        if (occupancy.truncated) {
            Classifier.LOGGER.warn("[Classifier] 维度 {} 占用扫描超过 {} 个实体，本次按“无人占用”放行",
                    level.dimension().location(), MAX_ENTITY_SCAN);
        }
        return occupancy;
    }

    /** 双箱锚点归一化；区块未加载时不读方块状态，避免触发强制加载 */
    public static BlockPos anchorOf(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) ? ContainerUtil.getChestAnchor(level, pos) : pos.immutable();
    }

    /** 这台分类者是否已经持有同一片工作区（角点排序后逐分量比较）；用于幂等判定 */
    public static boolean holdsWorkspace(SorterEntity sorter, BlockPos pos1, BlockPos pos2) {
        BlockPos min = sorter.getWorkspaceMin();
        BlockPos max = sorter.getWorkspaceMax();
        if (min == null || max == null) {
            return false;
        }
        return WorkspaceKey.of(min, max).equals(WorkspaceKey.of(pos1, pos2));
    }
}
