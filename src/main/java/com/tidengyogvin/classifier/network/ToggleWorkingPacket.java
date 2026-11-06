package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.SorterEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端点击“状态”按钮后请求服务端切换分类者的工作状态 */
public class ToggleWorkingPacket {

    private final int entityId;

    public ToggleWorkingPacket(int entityId) {
        this.entityId = entityId;
    }

    public int getEntityId() {
        return entityId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static ToggleWorkingPacket decode(FriendlyByteBuf buf) {
        return new ToggleWorkingPacket(buf.readInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            Entity entity = player.level.getEntity(entityId);
            if (entity instanceof SorterEntity sorter) {
                sorter.toggleWorking();
            }
        });
        ctx.setPacketHandled(true);
    }
}
