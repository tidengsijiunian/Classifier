package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 世界级的工作区表：工作区从「分类者身上的三个字段」升级成有独立身份的对象。
 *
 * 存在原因：工作区需要起名、重名拦截、列表展示和选中标记，这些都必须有一个
 * 稳定的 UUID 与独立存档；继续挂在分类者身上会出现「没绑分类者的工作区存不下来」
 * 和「两台分类者绑同一片地时列表出现两行同一个区」两个问题。
 *
 * 名字是可选的备注：null / 空串表示未命名，未命名的工作区之间不算重名，
 * 也不参与任何重名比较（占位文字「未命名工作区」绝不会被当成真实名字拦截）。
 *
 * 真值只在服务端；客户端拿到的是 {@link com.tidengyogvin.classifier.network.WorkspaceSyncPacket} 下发的只读副本。
 */
public class WorkspaceTable extends SavedData {

    /** 存档文件名，落在 overworld 的 data 目录下 */
    private static final String DATA_NAME = "classifier_workspaces";
    /** 工作区名字的最大长度，与分类者名字保持同一量级 */
    public static final int MAX_WORKSPACE_NAME_LENGTH = 32;
    /** 条目上限：防止异常数据把同步包撑爆 */
    private static final int MAX_ENTRIES = 4096;

    /**
     * 一条工作区记录。dimension 为划定时的维度；min / max 保存原样的两个角点，
     * 比较占用时才排序（与分类箱占用校验的既有口径一致）。
     */
    public record Workspace(UUID id, @Nullable String name, ResourceLocation dimension,
                            BlockPos min, BlockPos max) {

        public boolean hasName() {
            return name != null && !name.isBlank();
        }

        /** 归一化后的最小角点，供列表显示尺寸与占用比较使用 */
        public BlockPos normalizedMin() {
            return new BlockPos(Math.min(min.getX(), max.getX()),
                    Math.min(min.getY(), max.getY()),
                    Math.min(min.getZ(), max.getZ()));
        }

        public BlockPos normalizedMax() {
            return new BlockPos(Math.max(min.getX(), max.getX()),
                    Math.max(min.getY(), max.getY()),
                    Math.max(min.getZ(), max.getZ()));
        }
    }

    private final Map<UUID, Workspace> workspaces = new LinkedHashMap<>();

    public WorkspaceTable() {
    }

    /** 取（或首次创建）当前存档的工作区表；只在服务端调用 */
    public static WorkspaceTable get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(WorkspaceTable::load, WorkspaceTable::new, DATA_NAME);
    }

    public static WorkspaceTable load(CompoundTag tag) {
        WorkspaceTable table = new WorkspaceTable();
        ListTag list = tag.getList("Workspaces", Tag.TAG_COMPOUND);
        // 存档被写坏时按条丢弃，绝不让整个世界卡在加载
        for (int i = 0; i < list.size() && i < MAX_ENTRIES; i++) {
            CompoundTag entry = list.getCompound(i);
            try {
                if (!entry.hasUUID("Id")) {
                    continue;
                }
                BlockPos min = readPos(entry, "Min");
                BlockPos max = readPos(entry, "Max");
                if (min == null || max == null) {
                    continue;
                }
                ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dimension"));
                if (dimension == null) {
                    continue;
                }
                String rawName = entry.contains("Name", Tag.TAG_STRING) ? entry.getString("Name") : null;
                table.workspaces.put(entry.getUUID("Id"),
                        new Workspace(entry.getUUID("Id"), normalizeName(rawName), dimension, min, max));
            } catch (RuntimeException e) {
                Classifier.LOGGER.warn("[Classifier] 工作区条目 #{} 读取失败，已跳过", i, e);
            }
        }
        return table;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Workspace workspace : this.workspaces.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", workspace.id());
            if (workspace.hasName()) {
                entry.putString("Name", workspace.name());
            }
            entry.putString("Dimension", workspace.dimension().toString());
            entry.putLong("Min", workspace.min().asLong());
            entry.putLong("Max", workspace.max().asLong());
            list.add(entry);
        }
        tag.put("Workspaces", list);
        return tag;
    }

    @Nullable
    private static BlockPos readPos(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            return null;
        }
        long packed = tag.getLong(key);
        int x = BlockPos.getX(packed);
        int y = BlockPos.getY(packed);
        int z = BlockPos.getZ(packed);
        if (y < -2048 || y > 2047 || Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L) {
            return null;
        }
        try {
            return BlockPos.of(packed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 名字规范化：去掉首尾空白，空串视为未命名 */
    @Nullable
    public static String normalizeName(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_WORKSPACE_NAME_LENGTH
                ? trimmed : trimmed.substring(0, MAX_WORKSPACE_NAME_LENGTH);
    }

    public Collection<Workspace> all() {
        return this.workspaces.values();
    }

    /** 列表展示顺序：先按维度，再按名字（未命名排后面），最后按 UUID 保证稳定 */
    public List<Workspace> sorted() {
        List<Workspace> result = new ArrayList<>(this.workspaces.values());
        result.sort((a, b) -> {
            int byDimension = a.dimension().compareTo(b.dimension());
            if (byDimension != 0) {
                return byDimension;
            }
            boolean namedA = a.hasName();
            boolean namedB = b.hasName();
            if (namedA != namedB) {
                return namedA ? -1 : 1;
            }
            if (namedA) {
                int byName = a.name().compareToIgnoreCase(b.name());
                if (byName != 0) {
                    return byName;
                }
            }
            return a.id().compareTo(b.id());
        });
        return result;
    }

    @Nullable
    public Workspace get(@Nullable UUID id) {
        return id == null ? null : this.workspaces.get(id);
    }

    /** 重名判断：大小写不敏感，未命名的条目不参与；exceptId 用于改名时排除自己 */
    public boolean isNameTaken(@Nullable String rawName, @Nullable UUID exceptId) {
        String name = normalizeName(rawName);
        if (name == null) {
            return false;
        }
        String probe = name.toLowerCase(Locale.ROOT);
        for (Workspace workspace : this.workspaces.values()) {
            if (exceptId != null && exceptId.equals(workspace.id())) {
                continue;
            }
            if (workspace.hasName() && workspace.name().toLowerCase(Locale.ROOT).equals(probe)) {
                return true;
            }
        }
        return false;
    }

    /** 新建工作区；名字可为空。调用方负责先做重名校验 */
    public Workspace create(ResourceLocation dimension, BlockPos min, BlockPos max, @Nullable String name) {
        Workspace workspace = new Workspace(UUID.randomUUID(), normalizeName(name), dimension, min, max);
        this.workspaces.put(workspace.id(), workspace);
        this.setDirty();
        return workspace;
    }

    /** 迁移与幂等场景用：按指定 UUID 写入，已存在则原样保留，不产生第二条 */
    public boolean putIfAbsent(Workspace workspace) {
        if (this.workspaces.containsKey(workspace.id())) {
            return false;
        }
        if (this.workspaces.size() >= MAX_ENTRIES) {
            Classifier.LOGGER.warn("[Classifier] 工作区数量达到上限 {}，拒绝新增", MAX_ENTRIES);
            return false;
        }
        this.workspaces.put(workspace.id(), workspace);
        this.setDirty();
        return true;
    }

    /** 改坐标 / 维度，保持 UUID 与名字不变 */
    public boolean updateBounds(UUID id, ResourceLocation dimension, BlockPos min, BlockPos max) {
        Workspace current = this.workspaces.get(id);
        if (current == null) {
            return false;
        }
        this.workspaces.put(id, new Workspace(id, current.name(), dimension, min, max));
        this.setDirty();
        return true;
    }

    /** 改名；名字为空表示去掉名字（回到未命名） */
    public boolean rename(UUID id, @Nullable String name) {
        Workspace current = this.workspaces.get(id);
        if (current == null) {
            return false;
        }
        String normalized = normalizeName(name);
        if (equalsNullable(current.name(), normalized)) {
            return true;
        }
        this.workspaces.put(id, new Workspace(id, normalized, current.dimension(), current.min(), current.max()));
        this.setDirty();
        return true;
    }

    public boolean remove(UUID id) {
        if (this.workspaces.remove(id) != null) {
            this.setDirty();
            return true;
        }
        return false;
    }

    private static boolean equalsNullable(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
