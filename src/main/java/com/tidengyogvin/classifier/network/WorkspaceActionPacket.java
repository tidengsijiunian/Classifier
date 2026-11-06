package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.ClassifierManagerItem;
import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.WorkspaceTable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：工作区列表上的操作（改名 / 绑定分类者 / 解除绑定）。
 *
 * 名字唯一性与绑定关系全部在这一侧判定：客户端只做即时提示，不当真源。
 * 每次成功操作后回推全量工作区表 + 管理器快照，界面立刻能看到结果。
 */
public class WorkspaceActionPacket {

    public enum Action {
        RENAME,
        BIND,
        UNBIND
    }

    private static final int PERMISSION_LEVEL = 2;

    private final Action action;
    private final UUID workspaceId;
    @Nullable
    private final String name;
    @Nullable
    private final UUID sorterId;

    private WorkspaceActionPacket(Action action, UUID workspaceId, @Nullable String name, @Nullable UUID sorterId) {
        this.action = action;
        this.workspaceId = workspaceId;
        this.name = name;
        this.sorterId = sorterId;
    }

    /** 改名；name 传空串表示去掉名字（回到未命名） */
    public static WorkspaceActionPacket rename(UUID workspaceId, @Nullable String name) {
        return new WorkspaceActionPacket(Action.RENAME, workspaceId, name, null);
    }

    /** 把工作区绑定到指定分类者 */
    public static WorkspaceActionPacket bind(UUID workspaceId, UUID sorterId) {
        return new WorkspaceActionPacket(Action.BIND, workspaceId, null, sorterId);
    }

    /** 解除工作区与分类者的绑定（工作区本身保留） */
    public static WorkspaceActionPacket unbind(UUID workspaceId) {
        return new WorkspaceActionPacket(Action.UNBIND, workspaceId, null, null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(this.action);
        buf.writeUUID(this.workspaceId);
        buf.writeBoolean(this.name != null);
        if (this.name != null) {
            buf.writeUtf(this.name, WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH);
        }
        buf.writeBoolean(this.sorterId != null);
        if (this.sorterId != null) {
            buf.writeUUID(this.sorterId);
        }
    }

    public static WorkspaceActionPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        UUID workspaceId = buf.readUUID();
        String name = buf.readBoolean() ? buf.readUtf(WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH) : null;
        UUID sorterId = buf.readBoolean() ? buf.readUUID() : null;
        return new WorkspaceActionPacket(action, workspaceId, name, sorterId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || player.getServer() == null) {
                return;
            }
            if (!player.hasPermissions(PERMISSION_LEVEL)
                    && ClassifierManagerItem.findHeldManager(player).isEmpty()) {
                player.displayClientMessage(new TextComponent("§c需要管理员权限，或手持分类者管理器"), false);
                return;
            }
            WorkspaceTable table = WorkspaceTable.get(player.getServer());
            WorkspaceTable.Workspace workspace = table.get(this.workspaceId);
            if (workspace == null) {
                player.displayClientMessage(new TextComponent("§c工作区已不存在"), false);
                WorkspaceSyncPacket.sendTo(player);
                return;
            }
            switch (this.action) {
                case RENAME -> rename(player, table, workspace);
                case BIND -> bind(player, workspace);
                case UNBIND -> unbind(player, workspace);
                default -> {
                }
            }
            WorkspaceSyncPacket.broadcast(player.getServer());
            ManagerActionPacket.syncTo(player);
        });
        ctx.setPacketHandled(true);
    }

    private void rename(ServerPlayer player, WorkspaceTable table, WorkspaceTable.Workspace workspace) {
        String raw = this.name == null ? "" : this.name.trim();
        if (raw.length() > WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH) {
            player.displayClientMessage(new TextComponent("§c名字最多 "
                    + WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH + " 个字符"), false);
            return;
        }
        if (table.isNameTaken(raw, workspace.id())) {
            player.displayClientMessage(new TextComponent("§c工作区名称「" + raw + "」已存在"), false);
            return;
        }
        table.rename(workspace.id(), raw);
        protectedMessage(player, raw.isEmpty()
                ? "§a已清除工作区名称"
                : "§a工作区已改名为「" + raw + "」");
    }

    private void bind(ServerPlayer player, WorkspaceTable.Workspace workspace) {
        SorterEntity sorter = ManagerData.find(player.getServer(), this.sorterId);
        if (sorter == null) {
            player.displayClientMessage(new TextComponent("§c分类者不存在或不在已加载区块"), false);
            return;
        }
        // 严格独占：一片工作区只归一台分类者
        for (SorterEntity other : ManagerData.collectSorters(player.getServer())) {
            if (other != sorter && workspace.id().equals(other.getWorkspaceId())) {
                player.displayClientMessage(new TextComponent("§c该工作区已被 "
                        + other.getName().getString() + " 占用"), false);
                return;
            }
        }
        if (!sorter.bindWorkspace(workspace.id())) {
            player.displayClientMessage(new TextComponent("§c绑定失败，请重试"), false);
            return;
        }
        protectedMessage(player, "§a已将工作区绑定到 " + sorter.getName().getString());
    }

    private void unbind(ServerPlayer player, WorkspaceTable.Workspace workspace) {
        int unbound = 0;
        for (SorterEntity sorter : ManagerData.collectSorters(player.getServer())) {
            if (workspace.id().equals(sorter.getWorkspaceId())) {
                sorter.bindWorkspace(null);
                unbound++;
            }
        }
        protectedMessage(player, unbound == 0
                ? "§7该工作区当前没有绑定分类者"
                : "§a已解除与 " + unbound + " 台分类者的绑定");
    }

    private static void protectedMessage(ServerPlayer player, String text) {
        player.displayClientMessage(new TextComponent(text), true);
    }
}
