package com.tidengyogvin.classifier.client;

import com.tidengyogvin.classifier.network.WorkspaceSyncPacket;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 客户端缓存：服务端最近一次推送的全量工作区表，工作区列表界面从这里读 */
public final class WorkspaceSyncCache {

    private static volatile List<WorkspaceSyncPacket.Entry> entries = Collections.emptyList();
    /** 每次收到新表自增，界面据此判断是否要重建列表 */
    private static volatile int version = 0;

    private WorkspaceSyncCache() {
    }

    public static void accept(List<WorkspaceSyncPacket.Entry> incoming) {
        entries = List.copyOf(incoming);
        version++;
    }

    public static List<WorkspaceSyncPacket.Entry> get() {
        return entries;
    }

    public static int getVersion() {
        return version;
    }

    @Nullable
    public static WorkspaceSyncPacket.Entry find(@Nullable UUID id) {
        if (id == null) {
            return null;
        }
        for (WorkspaceSyncPacket.Entry entry : entries) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public static void clear() {
        entries = Collections.emptyList();
        version++;
    }

    /** 退出服务器时清空，避免下次进服前短暂显示过期数据 */
    public static final class Events {

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            clear();
        }
    }
}
