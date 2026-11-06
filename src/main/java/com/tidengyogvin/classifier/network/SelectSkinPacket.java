package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.SorterEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectSkinPacket {

    private final int entityId;
    private final String playerName;

    public SelectSkinPacket(int entityId, String playerName) {
        this.entityId = entityId;
        this.playerName = playerName;
    }

    public int getEntityId() {
        return entityId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeUtf(playerName, 64);
    }

    public static SelectSkinPacket decode(FriendlyByteBuf buf) {
        return new SelectSkinPacket(buf.readInt(), buf.readUtf(64));
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
                sorter.setSkinPlayer(playerName);
            }
        });
        ctx.setPacketHandled(true);
    }
}
