package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.WorkspaceTable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：全量工作区表。
 *
 * 工作区列表界面的数据全部来自这里；登录时下发一次，任何创建 / 改名 / 绑定 / 解除后
 * 再推一次全量。工作区数量是个位数到几十，全量推比做增量简单且不会漏。
 */
public class WorkspaceSyncPacket {

    /** 单条工作区在客户端只读副本里的形态 */
    public record Entry(UUID id, @Nullable String name, ResourceLocation dimension,
                        BlockPos min, BlockPos max,
                        @Nullable UUID sorterId, @Nullable String sorterName) {

        public boolean hasName() {
            return this.name != null && !this.name.isBlank();
        }
    }

    private static final int MAX_NAME_LENGTH = WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH;
    private static final int MAX_SORTER_NAME_LENGTH = 128;
    private static final int MAX_ENTRIES = 4096;

    private final List<Entry> entries;

    public WorkspaceSyncPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> getEntries() {
        return this.entries;
    }

    /** 收集全量工作区 + 各自绑定的分类者；只在服务端调用 */
    public static List<Entry> collect(MinecraftServer server) {
        WorkspaceTable table = WorkspaceTable.get(server);
        // 工作区 UUID → 绑定它的分类者（严格独占下最多一台）。
        // 只认还活着的实体：被删掉的分类者不能继续在列表里挂着「已绑定 XX」。
        Map<UUID, SorterEntity> bound = new HashMap<>();
        for (SorterEntity sorter : ManagerData.collectSorters(server)) {
            if (sorter.getWorkspaceId() != null) {
                bound.putIfAbsent(sorter.getWorkspaceId(), sorter);
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (WorkspaceTable.Workspace workspace : table.sorted()) {
            SorterEntity sorter = bound.get(workspace.id());
            entries.add(new Entry(workspace.id(), workspace.name(), workspace.dimension(),
                    workspace.min(), workspace.max(),
                    sorter == null ? null : sorter.getUUID(),
                    sorter == null ? null : sorter.getName().getString()));
        }
        return entries;
    }

    /** 推给单个玩家 */
    public static void sendTo(ServerPlayer player) {
        if (player.getServer() == null) {
            return;
        }
        ModNetwork.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
                new WorkspaceSyncPacket(collect(player.getServer())));
    }

    /** 工作区数据变了：广播给所有在线玩家，界面和线框都跟着刷新 */
    public static void broadcast(MinecraftServer server) {
        List<Entry> entries = collect(server);
        ModNetwork.INSTANCE.send(PacketDistributor.ALL.noArg(), new WorkspaceSyncPacket(entries));
    }

    public void encode(FriendlyByteBuf buf) {
        int size = Math.min(this.entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Entry entry = this.entries.get(i);
            buf.writeUUID(entry.id());
            buf.writeBoolean(entry.name() != null);
            if (entry.name() != null) {
                buf.writeUtf(entry.name(), MAX_NAME_LENGTH);
            }
            buf.writeResourceLocation(entry.dimension());
            buf.writeBlockPos(entry.min());
            buf.writeBlockPos(entry.max());
            buf.writeBoolean(entry.sorterId() != null);
            if (entry.sorterId() != null) {
                buf.writeUUID(entry.sorterId());
            }
            buf.writeBoolean(entry.sorterName() != null);
            if (entry.sorterName() != null) {
                buf.writeUtf(entry.sorterName(), MAX_SORTER_NAME_LENGTH);
            }
        }
    }

    public static WorkspaceSyncPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String name = buf.readBoolean() ? buf.readUtf(MAX_NAME_LENGTH) : null;
            ResourceLocation dimension = buf.readResourceLocation();
            BlockPos min = buf.readBlockPos();
            BlockPos max = buf.readBlockPos();
            UUID sorterId = buf.readBoolean() ? buf.readUUID() : null;
            String sorterName = buf.readBoolean() ? buf.readUtf(MAX_SORTER_NAME_LENGTH) : null;
            entries.add(new Entry(id, name, dimension, min, max, sorterId, sorterName));
        }
        return new WorkspaceSyncPacket(entries);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        // 客户端缓存只在客户端存在，用 DistExecutor 隔离，避免专用服务端加载客户端类
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.tidengyogvin.classifier.client.WorkspaceSyncCache.accept(this.entries)));
        ctx.setPacketHandled(true);
    }
}
