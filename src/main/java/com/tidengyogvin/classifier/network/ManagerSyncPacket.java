package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.ManagerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * 服务端 → 客户端：玩家打开管理器时，推送全部分类者的状态快照与当前维度的仓储雷达数据。
 */
public class ManagerSyncPacket {

    private static final int MAX_NAME_LENGTH = ManagerData.MAX_NAME_LENGTH;
    /** 雷达类别字符串（如 tag:forge:ores）的最大长度 */
    private static final int MAX_CATEGORY_LENGTH = 128;
    private static final int MAX_RADAR_ENTRIES = 1024;

    private final List<ManagerData.SorterInfo> sorters;
    private final List<ManagerData.RadarEntry> radar;

    public ManagerSyncPacket(List<ManagerData.SorterInfo> sorters, List<ManagerData.RadarEntry> radar) {
        this.sorters = sorters;
        this.radar = radar;
    }

    public List<ManagerData.SorterInfo> getSorters() {
        return this.sorters;
    }

    public List<ManagerData.RadarEntry> getRadar() {
        return this.radar;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.sorters.size());
        for (ManagerData.SorterInfo info : this.sorters) {
            buf.writeUUID(info.id());
            buf.writeUtf(info.name(), MAX_NAME_LENGTH);
            buf.writeResourceLocation(info.dimension());
            buf.writeBlockPos(info.pos());
            buf.writeBoolean(info.working());
            buf.writeBoolean(info.locked());
            writeOptionalPos(buf, info.sorterBox());
            writeOptionalPos(buf, info.workspaceMin());
            writeOptionalPos(buf, info.workspaceMax());
        }

        int radarSize = Math.min(this.radar.size(), MAX_RADAR_ENTRIES);
        buf.writeVarInt(radarSize);
        for (int i = 0; i < radarSize; i++) {
            ManagerData.RadarEntry entry = this.radar.get(i);
            buf.writeResourceLocation(entry.dimension());
            buf.writeUUID(entry.sorterId());
            buf.writeBlockPos(entry.pos());
            String category = entry.category();
            buf.writeBoolean(category != null);
            if (category != null) {
                buf.writeUtf(category, MAX_CATEGORY_LENGTH);
            }
            buf.writeBoolean(entry.empty());
            buf.writeVarInt(entry.priority());
            buf.writeBoolean(entry.locked());
        }
    }

    public static ManagerSyncPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<ManagerData.SorterInfo> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new ManagerData.SorterInfo(
                    buf.readUUID(),
                    buf.readUtf(MAX_NAME_LENGTH),
                    buf.readResourceLocation(),
                    buf.readBlockPos(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    readOptionalPos(buf),
                    readOptionalPos(buf),
                    readOptionalPos(buf)));
        }

        int radarSize = buf.readVarInt();
        List<ManagerData.RadarEntry> radar = new ArrayList<>(radarSize);
        for (int i = 0; i < radarSize; i++) {
            ResourceLocation dimension = buf.readResourceLocation();
            UUID sorterId = buf.readUUID();
            BlockPos pos = buf.readBlockPos();
            String category = buf.readBoolean() ? buf.readUtf(MAX_CATEGORY_LENGTH) : null;
            boolean empty = buf.readBoolean();
            int priority = buf.readVarInt();
            boolean locked = buf.readBoolean();
            radar.add(new ManagerData.RadarEntry(dimension, sorterId, pos, category, empty, priority, locked));
        }
        return new ManagerSyncPacket(list, radar);
    }

    /** 可空坐标：先写是否存在标记，存在时再写 BlockPos */
    private static void writeOptionalPos(FriendlyByteBuf buf, @Nullable BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        }
    }

    @Nullable
    private static BlockPos readOptionalPos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {        NetworkEvent.Context ctx = ctxSupplier.get();
        // 客户端缓存只在客户端存在，用 DistExecutor 隔离，避免专用服务端加载客户端类
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.tidengyogvin.classifier.client.ManagerSyncCache.accept(this.sorters, this.radar)));
        ctx.setPacketHandled(true);
    }
}
