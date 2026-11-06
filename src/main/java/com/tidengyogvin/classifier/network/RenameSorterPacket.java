package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.SorterEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端请求给分类者改名 */
public class RenameSorterPacket {

    private final int entityId;
    private final String newName;

    public RenameSorterPacket(int entityId, String newName) {
        this.entityId = entityId;
        this.newName = newName;
    }

    public int getEntityId() {
        return entityId;
    }

    public String getNewName() {
        return newName;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeUtf(newName, 16);
    }

    public static RenameSorterPacket decode(FriendlyByteBuf buf) {
        return new RenameSorterPacket(buf.readInt(), buf.readUtf(16));
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level.getEntity(entityId);
            if (!(entity instanceof SorterEntity sorter) || newName.isEmpty()) {
                return;
            }
            String trimmed = newName.trim();
            if (trimmed.isEmpty() || trimmed.length() > 16) {
                player.displayClientMessage(new TextComponent("§c名字需要 1 - 16 个字符"), true);
                return;
            }
            // 重名只认服务端：客户端提示只是即时反馈，真源在这里
            if (ManagerData.isSorterNameTaken(player.getServer(), trimmed, sorter.getUUID())) {
                player.displayClientMessage(new TextComponent("§c分类者名称「" + trimmed + "」已存在"), true);
                return;
            }
            sorter.setCustomName(new TextComponent(trimmed));
            sorter.setCustomNameVisible(true);
            player.displayClientMessage(new TextComponent("§a已改名为「" + trimmed + "」"), true);
        });
        ctx.setPacketHandled(true);
    }
}
