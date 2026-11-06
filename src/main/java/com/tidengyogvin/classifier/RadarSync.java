package com.tidengyogvin.classifier;

import com.tidengyogvin.classifier.network.ManagerSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;

/**
 * 服务端 → 客户端的雷达数据推送。
 *
 * 雷达内容 = 当前维度所有已分配存放类型的箱子（见 {@link SorterEntity#getManagedBoxes()}）。
 * 玩家进入管理器界面时由客户端发请求全量拉取；游玩期间分类者数据变化后由实体节流推送。
 */
public final class RadarSync {

    private RadarSync() {
    }

    /** 单次快照最多处理的分类者数量；防止实体数量异常时一次遍历过重 */
    private static final int MAX_SORTERS_PER_SNAPSHOT = 64;

    /** 收集指定维度的箱子快照；锁定状态按接收玩家区分 */
    public static ManagerSyncPacket snapshot(ServerLevel level, ServerPlayer viewer) {
        java.util.List<ManagerData.RadarEntry> entries = new ArrayList<>();
        // 多个分类者可能记录了同一只箱子，按坐标去重并保留使用次数更高的那条
        java.util.Map<BlockPos, ManagerData.RadarEntry> unique = new java.util.LinkedHashMap<>();
        int processed = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (processed >= MAX_SORTERS_PER_SNAPSHOT) {
                break;
            }
            if (entity instanceof SorterEntity sorter && ManagerData.isLiveSorter(sorter)) {
                processed++;
                for (SorterEntity.ManagedBox box : sorter.getManagedBoxes()) {
                    ManagerData.RadarEntry existing = unique.get(box.pos());
                    if (existing != null && existing.priority() >= box.priority()) {
                        continue;
                    }
                    // getManagedBoxes 已归一化到双箱主半；这里再兜底一次，防止旧数据绕过
                    BlockPos anchor = level.hasChunkAt(box.pos())
                            ? ContainerUtil.getChestAnchor(level, box.pos()) : box.pos();
                    unique.put(anchor, new ManagerData.RadarEntry(level.dimension().location(), sorter.getUUID(),
                            anchor, box.category(), box.empty(), box.priority(),
                            ClassifierRadarState.isLocked(viewer, anchor)));
                    if (unique.size() >= SorterEntity.MAX_RADAR_ENTRIES) {
                        break;
                    }
                }
            }
        }
        entries.addAll(unique.values());
        return new ManagerSyncPacket(ManagerData.collect(level.getServer()), entries);
    }

    /** 把某个分类者所在维度的雷达快照推给持有管理器、且身处同一维度的玩家 */
    public static void pushToManagerHolders(ServerLevel level, SorterEntity sorter) {
        for (ServerPlayer player : level.getPlayers(p -> !ClassifierManagerItem.findHeldManager(p).isEmpty())) {
            ModNetwork.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), snapshot(level, player));
        }
    }
}
