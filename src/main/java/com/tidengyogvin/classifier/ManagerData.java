package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 管理器界面的数据层：服务端按需收集全服（所有维度）的分类者快照。
 * 快照只包含界面需要的字段，避免把实体对象直接跨端传递。
 */
public final class ManagerData {

    /** 名字在网络包与界面中的最大长度，超出部分截断，避免 writeUtf 抛异常 */
    public static final int MAX_NAME_LENGTH = 128;

    private ManagerData() {
    }

    /**
     * 单个分类者在管理列表中的只读快照。
     * sorterBox / workspaceMin / workspaceMax 为 null 表示该项尚未绑定，
     * 界面据此绘制状态点与悬停详情。
     */
    public record SorterInfo(UUID id, String name, ResourceLocation dimension, BlockPos pos,
                             boolean working, boolean locked,
                             @Nullable BlockPos sorterBox,
                             @Nullable BlockPos workspaceMin, @Nullable BlockPos workspaceMax) {

        public boolean hasSorterBox() {
            return this.sorterBox != null;
        }

        public boolean hasWorkspace() {
            return this.workspaceMin != null && this.workspaceMax != null;
        }

        /** 分类箱与工作区都已绑定 */
        public boolean fullyBound() {
            return this.hasSorterBox() && this.hasWorkspace();
        }
    }

    /**
     * 仓储雷达的一行。sorterId 为负责该箱子的分类者；category 为 null 表示物品来源的分类箱；
     * priority 表示该箱子在当前搜索里被选为首选目标的次数（越大越常用）；
     * locked 表示当前玩家是否锁定了该箱子（锁定后右键开箱不会取消高亮）。
     */
    public record RadarEntry(ResourceLocation dimension, UUID sorterId, BlockPos pos,
                             @Nullable String category, boolean empty, int priority, boolean locked) {
    }

    /**
     * 实体是否还算数的分类者。
     *
     * level.getAllEntities() 会在一小段时间里仍然返回「刚被杀死、正在播死亡动画」的实体
     * （血量已归零但还没被移除），也会返回已经 setRemoved 的实体。这些实体不该再占用
     * 名字、分类箱、工作区，否则删掉一台分类者后，玩家在那一秒里改名会被告知重名。
     * 另外区块视角下它们的坐标也基本作废，绑定它们只会得到一台连不上的死实体。
     */
    public static boolean isLiveSorter(@Nullable SorterEntity sorter) {
        // LivingEntity#isAlive 已经包含「未被移除 + 血量 > 0」；再显式判一次 isRemoved 兜底
        return sorter != null && !sorter.isRemoved() && sorter.isAlive();
    }

    /**
     * 分类者真正挂在头上的名字（自定义名）。
     * 没有自定义名时 Entity#getName 会退化成实体类型的默认名「分类者」——
     * 那不是一个玩家起过的名字，不能拿去参与重名判断，否则一台没名字的旧分类者
     * 会凭空占住「分类者」，让后面的人怎么都起不了这个名。
     *
     * @return 去空白后的名字；没有自定义名或名字为空时返回 null（表示「未命名」）
     */
    @Nullable
    public static String customNameOf(@Nullable SorterEntity sorter) {
        if (sorter == null) {
            return null;
        }
        net.minecraft.network.chat.Component custom = sorter.getCustomName();
        if (custom == null) {
            return null;
        }
        String name = custom.getString().trim();
        return name.isEmpty() ? null : name;
    }

    /** 收集全部已加载、且仍然活着的分类者实体（工作区绑定、重名校验都要按实体干活） */
    public static List<SorterEntity> collectSorters(@Nullable MinecraftServer server) {
        List<SorterEntity> result = new ArrayList<>();
        if (server == null) {
            return result;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof SorterEntity sorter && isLiveSorter(sorter)) {
                    result.add(sorter);
                }
            }
        }
        return result;
    }

    /**
     * 当前被占用的分类者名字集合（小写、去空白），用于一次性做重名判断 / 默认名顺延。
     * 只统计活着的、真有自定义名的分类者；exceptId 的名字不参与（改名时排除自己）。
     *
     * 已知限制：只看已加载的实体，未加载区块里的分类者扫不到（与占用校验同一限制）。
     */
    public static java.util.Set<String> collectSorterNameKeys(@Nullable MinecraftServer server,
                                                              @Nullable UUID exceptId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (SorterEntity sorter : collectSorters(server)) {
            if (exceptId != null && exceptId.equals(sorter.getUUID())) {
                continue;
            }
            String name = customNameOf(sorter);
            if (name != null) {
                keys.add(name.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return keys;
    }

    /**
     * 分类者名字是否已被占用（大小写不敏感）。
     * 只看已加载的实体：没加载的区块里的分类者扫不到，这是既有占用校验同样的限制。
     * 已删除 / 正在死亡动画中的分类者不参与判断，名字会立刻释放。
     */
    public static boolean isSorterNameTaken(@Nullable MinecraftServer server, @Nullable String rawName,
                                            @Nullable UUID exceptId) {
        if (rawName == null || rawName.isBlank()) {
            return false;
        }
        String probe = rawName.trim().toLowerCase(java.util.Locale.ROOT);
        return collectSorterNameKeys(server, exceptId).contains(probe);
    }

    /** 遍历所有维度的已加载实体，收集并按名字排序，保证列表顺序稳定 */
    public static List<SorterInfo> collect(MinecraftServer server) {
        List<SorterInfo> result = new ArrayList<>();
        if (server == null) {
            return result;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof SorterEntity sorter) || !isLiveSorter(sorter)) {
                    continue;
                }
                result.add(new SorterInfo(
                        sorter.getUUID(),
                        shorten(sorter.getName().getString()),
                        level.dimension().location(),
                        sorter.blockPosition(),
                        sorter.isWorking(),
                        sorter.isManualPause(),
                        sorter.getSorterBoxPos(),
                        sorter.getWorkspaceMin(),
                        sorter.getWorkspaceMax()));
            }
        }
        result.sort(Comparator.comparing(SorterInfo::name));
        return result;
    }

    private static String shorten(String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
    }

    /** 按 UUID 跨维度查找分类者 */
    @Nullable
    public static SorterEntity find(MinecraftServer server, UUID id) {
        if (server == null || id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof SorterEntity sorter
                        && isLiveSorter(sorter) && sorter.getUUID().equals(id)) {
                    return sorter;
                }
            }
        }
        return null;
    }
}
