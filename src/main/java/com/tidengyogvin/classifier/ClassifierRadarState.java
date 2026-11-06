package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 雷达锁定状态（服务端权威，按玩家区分）。
 *
 * 规则：右键打开的高亮箱子在未锁定时取消高亮；锁定后即使右键也不取消，必须回雷达界面手动解锁。
 * 锁定记录在内存中，箱子被拆掉后会在下次查询时自然失效（雷达条目本身也会消失）。
 */
public final class ClassifierRadarState {

    /** 玩家 UUID →（箱子坐标 → 所属分类者 UUID），箱子坐标用 asLong 存储 */
    private static final Map<UUID, Map<Long, UUID>> LOCKED = new HashMap<>();

    private ClassifierRadarState() {
    }

    public static void lock(net.minecraft.world.entity.player.Player player, SorterEntity sorter, BlockPos pos) {
        LOCKED.computeIfAbsent(player.getUUID(), id -> new HashMap<>())
                .put(pos.asLong(), sorter.getUUID());
    }

    public static void unlock(net.minecraft.world.entity.player.Player player, BlockPos pos) {
        Map<Long, UUID> locks = LOCKED.get(player.getUUID());
        if (locks != null) {
            locks.remove(pos.asLong());
            if (locks.isEmpty()) {
                LOCKED.remove(player.getUUID());
            }
        }
    }

    public static boolean isLocked(net.minecraft.world.entity.player.Player player, BlockPos pos) {
        Map<Long, UUID> locks = LOCKED.get(player.getUUID());
        return locks != null && locks.containsKey(pos.asLong());
    }

    /** 当前玩家锁定的全部箱子坐标（客户端据此渲染高亮） */
    public static java.util.Set<BlockPos> lockedPositions(net.minecraft.world.entity.player.Player player) {
        Map<Long, UUID> locks = LOCKED.get(player.getUUID());
        if (locks == null) {
            return java.util.Set.of();
        }
        java.util.Set<BlockPos> result = new java.util.HashSet<>();
        for (Long packed : locks.keySet()) {
            result.add(BlockPos.of(packed));
        }
        return result;
    }

    @Nullable
    public static UUID ownerOf(net.minecraft.world.entity.player.Player player, BlockPos pos) {
        Map<Long, UUID> locks = LOCKED.get(player.getUUID());
        return locks == null ? null : locks.get(pos.asLong());
    }

    public static void clear() {
        LOCKED.clear();
    }
}
