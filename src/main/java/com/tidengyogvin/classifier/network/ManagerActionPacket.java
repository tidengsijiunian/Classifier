package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.Classifier;
import com.tidengyogvin.classifier.ClassifierRadarState;
import com.tidengyogvin.classifier.ClassifierManagerItem;
import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.RadarSync;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.SorterOccupancy;
import com.tidengyogvin.classifier.WorkspaceTable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 客户端 → 服务端：管理器界面上的按钮操作，执行后立即回推一次全量同步 */
public class ManagerActionPacket {

    public enum Action {
        TELEPORT,
        TOGGLE_WORKING,
        UNBIND,
        SET_WORKSPACE,
        REQUEST_RADAR,
        LOCK_RADAR,
        UNLOCK_RADAR
    }

    private static final int PERMISSION_LEVEL = 2;
    /** 工作区同步范围 */
    private static final double WORKSPACE_SYNC_RADIUS = 32.0D;
    /** 与客户端一致的世界边界，客户端数据不可信，服务端必须再校验一次 */
    private static final int MAX_WORLD_COORD = 30_000_000;
    /** Alt+滚轮同一片工作区的重复提交在 400ms 内只扇出一次 */
    private static final long WORKSPACE_FANOUT_DEDUPE_MILLIS = 400L;
    /** 去重表条目上限；超过直接清空，避免按玩家 UUID 无限堆积 */
    private static final int MAX_DEDUPE_ENTRIES = 64;
    /** 玩家 UUID → 上一次扇出过的工作区提交 */
    private static final Map<UUID, WorkspaceSubmission> LAST_WORKSPACE_SUBMISSION = new HashMap<>();
    /** 管理器界面刷新节流：绑定结果变化后最多每 500ms 推一次全量快照 */
    private static final long MANAGER_SYNC_THROTTLE_MILLIS = 500L;
    /** 玩家 UUID → 上一次推送管理器快照的时间 */
    private static final Map<UUID, Long> LAST_MANAGER_SYNC = new HashMap<>();

    /** 工作区提交记录：维度 + 归一化后的两个角点 + 时间戳 */
    private record WorkspaceSubmission(ResourceKey<Level> dimension, BlockPos min, BlockPos max, long at) {
    }

    private final Action action;
    @Nullable
    private final UUID targetId;
    @Nullable
    private final BlockPos pos1;
    @Nullable
    private final BlockPos pos2;
    /** 仅 SET_WORKSPACE 使用：工作区名字，null 表示沿用/不改名 */
    @Nullable
    private final String workspaceName;

    private ManagerActionPacket(Action action, @Nullable UUID targetId,
                                 @Nullable BlockPos pos1, @Nullable BlockPos pos2) {
        this(action, targetId, pos1, pos2, null);
    }

    private ManagerActionPacket(Action action, @Nullable UUID targetId, @Nullable BlockPos pos1,
                                 @Nullable BlockPos pos2, @Nullable String workspaceName) {
        this.action = action;
        this.targetId = targetId;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.workspaceName = workspaceName;
    }

    public static ManagerActionPacket teleport(UUID targetId) {
        return new ManagerActionPacket(Action.TELEPORT, targetId, null, null);
    }

    public static ManagerActionPacket toggleWorking(UUID targetId) {
        return new ManagerActionPacket(Action.TOGGLE_WORKING, targetId, null, null);
    }

    public static ManagerActionPacket unbind(UUID targetId) {
        return new ManagerActionPacket(Action.UNBIND, targetId, null, null);
    }

    /** 保存工作区坐标：位置写在服务端手持的管理器 NBT 上，并同步给附近的分类者 */
    public static ManagerActionPacket setWorkspace(@Nullable BlockPos pos1, @Nullable BlockPos pos2) {
        return new ManagerActionPacket(Action.SET_WORKSPACE, null, pos1, pos2, null);
    }

    /**
     * 保存工作区坐标并指定归属分类者与名字（第二轮的划定工作区界面用）。
     * targetSorterId 为 null 时退回旧行为：同步给玩家附近最近的那台分类者。
     */
    public static ManagerActionPacket setWorkspace(@Nullable BlockPos pos1, @Nullable BlockPos pos2,
                                                   @Nullable UUID targetSorterId, @Nullable String name) {
        return new ManagerActionPacket(Action.SET_WORKSPACE, targetSorterId, pos1, pos2, name);
    }

    /** 请求一次当前维度的雷达快照（打开雷达界面时使用） */
    public static ManagerActionPacket requestRadar() {
        return new ManagerActionPacket(Action.REQUEST_RADAR, null, null, null);
    }

    /** 雷达锁定 / 解锁：targetId 为箱子所属分类者，pos1 为箱子坐标 */
    public static ManagerActionPacket setRadarLock(boolean lock, UUID sorterId, BlockPos boxPos) {
        return new ManagerActionPacket(lock ? Action.LOCK_RADAR : Action.UNLOCK_RADAR, sorterId, boxPos, null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(this.action);
        buf.writeBoolean(this.targetId != null);
        if (this.targetId != null) {
            buf.writeUUID(this.targetId);
        }
        buf.writeBoolean(this.pos1 != null);
        buf.writeBoolean(this.pos2 != null);
        if (this.pos1 != null) {
            buf.writeBlockPos(this.pos1);
        }
        if (this.pos2 != null) {
            buf.writeBlockPos(this.pos2);
        }
        buf.writeBoolean(this.workspaceName != null);
        if (this.workspaceName != null) {
            buf.writeUtf(this.workspaceName, com.tidengyogvin.classifier.WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH);
        }
    }

    public static ManagerActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        UUID targetId = buf.readBoolean() ? buf.readUUID() : null;
        BlockPos pos1 = buf.readBoolean() ? buf.readBlockPos() : null;
        BlockPos pos2 = buf.readBoolean() ? buf.readBlockPos() : null;
        String name = buf.readBoolean()
                ? buf.readUtf(com.tidengyogvin.classifier.WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH) : null;
        return new ManagerActionPacket(action, targetId, pos1, pos2, name);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            if (this.action == Action.SET_WORKSPACE) {
                saveWorkspace(player, this.pos1, this.pos2, this.targetId, this.workspaceName);
                return;
            }
            if (this.action == Action.REQUEST_RADAR) {
                // ServerPlayer#getLevel 在 1.18.2 已返回 ServerLevel，无需再做类型判断
                ModNetwork.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        RadarSync.snapshot(player.getLevel(), player));
                return;
            }
            if (this.action == Action.LOCK_RADAR || this.action == Action.UNLOCK_RADAR) {
                setRadarLock(player, this.action == Action.LOCK_RADAR, this.targetId, this.pos1);
                return;
            }

            SorterEntity sorter = ManagerData.find(player.getServer(), this.targetId);
            if (sorter == null) {
                player.displayClientMessage(new TextComponent("§c分类者不存在或不在已加载区块"), false);
                return;
            }
            if (!canOperate(player)) {
                player.displayClientMessage(new TextComponent("§c需要管理员权限，或手持分类者管理器"), false);
                return;
            }

            switch (this.action) {
                case TELEPORT -> teleportToSorter(player, sorter);
                case TOGGLE_WORKING -> {
                    sorter.toggleWorking();
                    player.displayClientMessage(new TextComponent("§a已切换 " + sorter.getName().getString() + " 的工作状态"), true);
                }
                case UNBIND -> {
                    sorter.setSorterBoxPos(null);
                    sorter.refreshWorkingStateFromSorterBox();
                    player.displayClientMessage(new TextComponent("§a已解除 " + sorter.getName().getString() + " 的分类箱绑定"), true);
                }
                default -> {
                }
            }
            syncTo(player);
        });
        ctx.setPacketHandled(true);
    }

    /** 推送一次全量快照（分类者列表 + 当前维度雷达），让界面在操作后立即刷新 */
    public static void syncTo(ServerPlayer player) {
        ModNetwork.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                RadarSync.snapshot(player.getLevel(), player));
    }

    /**
     * 带节流的管理器快照推送。
     * 工作区滚轮会连续提交坐标，绑定结果每变一次都全量扫描代价太高，
     * 这里按玩家限制最多每 500ms 推一次；界面最迟半秒内刷新到位。
     */
    private static void syncManagerThrottled(ServerPlayer player) {
        long now = System.currentTimeMillis();
        Long last = LAST_MANAGER_SYNC.get(player.getUUID());
        if (last != null && now - last < MANAGER_SYNC_THROTTLE_MILLIS) {
            return;
        }
        if (LAST_MANAGER_SYNC.size() > MAX_DEDUPE_ENTRIES) {
            LAST_MANAGER_SYNC.clear();
        }
        LAST_MANAGER_SYNC.put(player.getUUID(), now);
        syncTo(player);
    }

    /** 雷达锁定 / 解锁。锁定状态保存在服务端，玩家重进服务器后依然有效 */
    private static void setRadarLock(ServerPlayer player, boolean lock, @Nullable UUID sorterId, @Nullable BlockPos pos) {
        SorterEntity sorter = ManagerData.find(player.getServer(), sorterId);
        if (sorter == null || pos == null) {
            player.displayClientMessage(new TextComponent("§c雷达目标已失效"), true);
            return;
        }
        if (lock) {
            ClassifierRadarState.lock(player, sorter, pos);
            player.displayClientMessage(new TextComponent("§a已锁定箱子 " + formatPos(pos)), true);
        } else {
            ClassifierRadarState.unlock(player, pos);
            player.displayClientMessage(new TextComponent("§a已解除箱子锁定 " + formatPos(pos)), true);
        }
        syncTo(player);
    }

    /** 把玩家传送到分类者身边；跨维度时一并切换维度 */
    private static void teleportToSorter(ServerPlayer player, SorterEntity sorter) {
        if (!(sorter.getLevel() instanceof ServerLevel targetLevel)) {
            player.displayClientMessage(new TextComponent("§c分类者所在维度不可用"), false);
            return;
        }
        player.teleportTo(targetLevel, sorter.getX(), sorter.getY(), sorter.getZ(),
                player.getYRot(), player.getXRot());
        player.displayClientMessage(new TextComponent("§a已传送到 " + sorter.getName().getString() + " 身边"), true);
    }

    /** 操作权限：管理员，或手中持有管理器（客户端界面就是从这个物品打开的） */
    private static boolean canOperate(ServerPlayer player) {
        return player.hasPermissions(PERMISSION_LEVEL)
                || !ClassifierManagerItem.findHeldManager(player).isEmpty();
    }

    /** 把坐标写进手持的管理器，并同步给 32 格内归属该玩家的分类者 */
    private static void saveWorkspace(ServerPlayer player, @Nullable BlockPos pos1, @Nullable BlockPos pos2,
                                      @Nullable UUID targetSorterId, @Nullable String requestedName) {
        // 任一端点越界就整包丢弃，避免分类者拿到天价范围后逐格扫描卡死服务端
        if ((pos1 != null && !isWithinWorld(pos1)) || (pos2 != null && !isWithinWorld(pos2))) {
            player.displayClientMessage(new TextComponent("§c工作区坐标超出世界边界，已拒绝保存"), false);
            Classifier.LOGGER.warn("Rejected out-of-bounds workspace from {}: {} -> {}",
                    player.getName().getString(), pos1, pos2);
            return;
        }
        // 体积上限：防旧数据 / 滚轮失控把寻箱扫描和巨型线框拖死
        if (pos1 != null && pos2 != null && !SorterEntity.isWorkspaceVolumeValid(pos1, pos2)) {
            player.displayClientMessage(new TextComponent("§c工作区范围过大，最大支持 "
                    + SorterEntity.MAX_WORKSPACE_VOLUME + " 格！"), false);
            Classifier.LOGGER.warn("Rejected oversized workspace from {}: {} -> {}",
                    player.getName().getString(), pos1, pos2);
            return;
        }
        ItemStack stack = ClassifierManagerItem.findHeldManager(player);
        if (stack.isEmpty()) {
            player.displayClientMessage(new TextComponent("§c请手持分类者管理器再保存工作区"), false);
            return;
        }
        ClassifierManagerItem.setWorkspaceCorners(stack, pos1, pos2);

        // 指定了归属分类者时只操作那一台；名字的重名校验必须在动坐标之前做完
        SorterEntity explicitTarget = targetSorterId == null
                ? null : ManagerData.find(player.getServer(), targetSorterId);
        if (targetSorterId != null && explicitTarget == null) {
            player.displayClientMessage(new TextComponent("§c目标分类者不存在或不在已加载区块"), false);
            return;
        }
        if (explicitTarget != null && !player.hasPermissions(PERMISSION_LEVEL)
                && !explicitTarget.isOwnedBy(player.getUUID())) {
            player.displayClientMessage(new TextComponent("§c这台分类者不属于你"), false);
            return;
        }
        WorkspaceTable table = WorkspaceTable.get(player.getServer());
        String workspaceName = WorkspaceTable.normalizeName(requestedName);
        if (workspaceName != null && table.isNameTaken(workspaceName,
                explicitTarget == null ? null : explicitTarget.getWorkspaceId())) {
            player.displayClientMessage(new TextComponent("§c工作区名称「" + workspaceName + "」已存在"), false);
            return;
        }

        // Alt+滚轮会以 90ms 的间隔连续提交工作区；400ms 内重复提交同一片区域时，
        // 物品 NBT 已经写好，直接跳过扇出扫描
        if (explicitTarget == null && pos1 != null && pos2 != null && isDuplicateSubmission(player, pos1, pos2)) {
            return;
        }

        if (explicitTarget != null) {
            applyToSingleSorter(player, explicitTarget, pos1, pos2, workspaceName, table);
            player.containerMenu.broadcastChanges();
            WorkspaceSyncPacket.broadcast(player.getServer());
            return;
        }

        // 保存工作区时的位置也是“这把管理器属于谁”的线索：附近的分类者自动归属当前玩家
        List<SorterEntity> nearby = new ArrayList<>(collectNearbySorters(player, WORKSPACE_SYNC_RADIUS));
        // 严格独占：一片工作区只归一台分类者，离玩家最近的那台优先，保证结果可复现
        nearby.sort(Comparator.comparingDouble(sorter -> sorter.distanceToSqr(player)));
        SorterOccupancy.Occupancy occupancy = (pos1 == null || pos2 == null)
                ? null : SorterOccupancy.scan(player.getLevel());
        int synced = 0;
        int rejected = 0;
        SorterEntity claimed = null;
        List<UUID> rejectedIds = new ArrayList<>();
        for (SorterEntity sorter : nearby) {
            if (player.hasPermissions(PERMISSION_LEVEL)) {
                sorter.setOwner(player.getUUID(), player.getName().getString());
            } else if (!sorter.isOwnedBy(player.getUUID())) {
                continue; // 非管理员只能影响自己名下的分类者
            }
            if (pos1 != null && pos2 != null) {
                // 幂等：这台已经持有同一片工作区，不重复写入
                if (SorterOccupancy.holdsWorkspace(sorter, pos1, pos2)) {
                    synced++;
                    if (claimed == null) {
                        claimed = sorter;
                    }
                    continue;
                }
                SorterEntity occupant = claimed;
                if (occupant == null && occupancy != null) {
                    occupant = occupancy.workspaceOccupant(pos1, pos2);
                }
                if (occupant != null) {
                    rejected++;
                    // 记下具体是哪几台，客户端据此在界面上把对应行闪红
                    rejectedIds.add(sorter.getUUID());
                    continue;
                }
            }
            if (sorter.setWorkspace(pos1, pos2)) {
                synced++;
                if (pos1 != null && pos2 != null) {
                    claimed = sorter;
                }
            }
        }
        // 名字落到刚认领的那片工作区上（未命名时不动，保留既有名字）
        if (workspaceName != null && claimed != null && claimed.getWorkspaceId() != null) {
            table.rename(claimed.getWorkspaceId(), workspaceName);
        }
        if (pos1 != null && pos2 != null) {
            player.displayClientMessage(new TextComponent(
                    "§a已将工作区同步给附近的 " + synced + " 个分类者"), true);
            if (rejected > 0) {
                // 多台被拒只出一条聚合提示，否则一次滚轮就能刷屏
                SorterEntity occupant = claimed != null ? claimed : occupancy.workspaceOccupant(pos1, pos2);
                String who = occupant == null ? "其它分类者" : occupant.getName().getString();
                player.sendMessage(new TextComponent("§c该工作区已被 " + who + " 占用，"
                        + rejected + " 台分类者未绑定"), player.getUUID());
                // 界面行闪红：与聊天栏提示同时到达，明确标出是哪几台被拒
                ModNetwork.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                        new ManagerRejectPacket(rejectedIds, who));
            }
            // 绑定结果变了才需要刷新列表；滚动连续提交时按 500ms 节流，避免每格都全量扫描
            if (synced > 0 || rejected > 0) {
                syncManagerThrottled(player);
            }
        } else {
            player.displayClientMessage(new TextComponent("§a已清除附近 " + synced + " 个分类者的工作区"), true);
        }
        if (synced > 0 || rejected > 0) {
            WorkspaceSyncPacket.broadcast(player.getServer());
        }

        // 服务端 NBT 已更新，立即对比槽位并把变化推给客户端，保证手持物品的 NBT 不过期
        player.containerMenu.broadcastChanges();
        // 这里不额外推送全量快照：客户端已经用同样的坐标写了本地 NBT，
        // 分类者的状态变化会由实体的节流同步送达，避免每次滚轮微调都触发一次全量扫描。

        Classifier.LOGGER.debug("Manager workspace saved for {}: {} -> {}, synced {} sorters",
                player.getName().getString(), pos1, pos2, synced);
    }

    /** 划定工作区界面指定了分类者：只改这一台，严格独占照旧 */
    private static void applyToSingleSorter(ServerPlayer player, SorterEntity sorter,
                                            @Nullable BlockPos pos1, @Nullable BlockPos pos2,
                                            @Nullable String workspaceName, WorkspaceTable table) {
        if (pos1 == null || pos2 == null) {
            sorter.setWorkspace(null, null);
            player.displayClientMessage(new TextComponent("§a已解除 " + sorter.getName().getString()
                    + " 的工作区绑定"), true);
            return;
        }
        if (!SorterOccupancy.holdsWorkspace(sorter, pos1, pos2)) {
            SorterEntity occupant = SorterOccupancy.scan(player.getLevel()).workspaceOccupant(pos1, pos2);
            if (occupant != null && occupant != sorter) {
                player.displayClientMessage(new TextComponent("§c该工作区已被 "
                        + occupant.getName().getString() + " 占用，无法绑定"), false);
                return;
            }
        }
        if (!sorter.setWorkspace(pos1, pos2)) {
            player.displayClientMessage(new TextComponent("§c工作区范围过大，最大支持 "
                    + SorterEntity.MAX_WORKSPACE_VOLUME + " 格！"), false);
            return;
        }
        if (sorter.getWorkspaceId() != null) {
            sorter.setOwner(player.getUUID(), player.getName().getString());
            if (workspaceName != null) {
                table.rename(sorter.getWorkspaceId(), workspaceName);
            }
            player.displayClientMessage(new TextComponent("§a已为 " + sorter.getName().getString()
                    + (workspaceName == null ? " 划定工作区" : " 划定工作区并命名为「" + workspaceName + "」")), true);
        }
        syncManagerThrottled(player);
    }

    private static boolean isWithinWorld(BlockPos pos) {
        return Math.abs(pos.getX()) <= MAX_WORLD_COORD
                && Math.abs(pos.getY()) <= MAX_WORLD_COORD
                && Math.abs(pos.getZ()) <= MAX_WORLD_COORD;
    }

    /**
     * 同一维度、同一片工作区在 400ms 内被重复提交时返回 true（角点排序后比较）。
     * Alt+滚轮连滚与界面连点保存都会产生这种同值请求。
     */
    private static boolean isDuplicateSubmission(ServerPlayer player, BlockPos pos1, BlockPos pos2) {
        long now = System.currentTimeMillis();
        SorterOccupancy.WorkspaceKey key = SorterOccupancy.WorkspaceKey.of(pos1, pos2);
        WorkspaceSubmission current = new WorkspaceSubmission(player.getLevel().dimension(),
                key.min(), key.max(), now);
        WorkspaceSubmission previous = LAST_WORKSPACE_SUBMISSION.put(player.getUUID(), current);
        if (LAST_WORKSPACE_SUBMISSION.size() > MAX_DEDUPE_ENTRIES) {
            LAST_WORKSPACE_SUBMISSION.clear();
            LAST_WORKSPACE_SUBMISSION.put(player.getUUID(), current);
            return false;
        }
        return previous != null
                && previous.dimension().equals(current.dimension())
                && previous.min().equals(current.min())
                && previous.max().equals(current.max())
                && now - previous.at() < WORKSPACE_FANOUT_DEDUPE_MILLIS;
    }

    /** 以玩家位置为中心搜索指定半径内的分类者（同一维度）；死掉 / 已移除的不算 */
    private static List<SorterEntity> collectNearbySorters(ServerPlayer player, double radius) {
        Vec3 center = player.position();
        List<SorterEntity> found = player.getLevel().getEntitiesOfClass(SorterEntity.class,
                new net.minecraft.world.phys.AABB(center, center).inflate(radius));
        List<SorterEntity> alive = new ArrayList<>(found.size());
        for (SorterEntity sorter : found) {
            if (ManagerData.isLiveSorter(sorter)) {
                alive.add(sorter);
            }
        }
        return alive;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
