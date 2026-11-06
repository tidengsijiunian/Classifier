package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.ManagerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：本次绑定因目标已被占用而失败。
 *
 * 与聊天栏提示同时发出，界面收到后在对应分类者行上闪红，
 * 让玩家不用回翻聊天记录就知道哪几台没绑上、被谁占着。
 * 行闪红是纯客户端状态，不写进实体，也不影响任何判定。
 */
public class ManagerRejectPacket {

    private static final int MAX_NAME_LENGTH = ManagerData.MAX_NAME_LENGTH;
    private static final int MAX_REJECTED = 256;

    private final List<UUID> rejectedIds;
    private final String occupantName;

    public ManagerRejectPacket(List<UUID> rejectedIds, String occupantName) {
        this.rejectedIds = rejectedIds;
        this.occupantName = occupantName == null ? "" : occupantName;
    }

    public void encode(FriendlyByteBuf buf) {
        int size = Math.min(this.rejectedIds.size(), MAX_REJECTED);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeUUID(this.rejectedIds.get(i));
        }
        buf.writeUtf(this.occupantName, MAX_NAME_LENGTH);
    }

    public static ManagerRejectPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<UUID> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(buf.readUUID());
        }
        return new ManagerRejectPacket(ids, buf.readUtf(MAX_NAME_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.tidengyogvin.classifier.client.ManagerRejectCache.reject(
                        this.rejectedIds, this.occupantName)));
        ctx.setPacketHandled(true);
    }
}
