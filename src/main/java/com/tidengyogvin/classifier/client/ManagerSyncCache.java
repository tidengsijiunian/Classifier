package com.tidengyogvin.classifier.client;

import com.tidengyogvin.classifier.ManagerData;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Collections;
import java.util.List;

/** 客户端缓存：保存服务端最近一次推送的分类者快照与仓储雷达，界面从这里读取 */
public final class ManagerSyncCache {

    private static volatile List<ManagerData.SorterInfo> sorters = Collections.emptyList();
    private static volatile List<ManagerData.RadarEntry> radar = Collections.emptyList();
    /** 每次收到新快照自增，界面据此判断是否需要重建列表 */
    private static volatile int version = 0;

    private ManagerSyncCache() {
    }

    /** 网络包线程触发，界面渲染线程读取，用 volatile + 不可变列表保证可见性 */
    public static void accept(List<ManagerData.SorterInfo> incoming, List<ManagerData.RadarEntry> incomingRadar) {
        sorters = List.copyOf(incoming);
        radar = List.copyOf(incomingRadar);
        version++;
    }

    public static List<ManagerData.SorterInfo> get() {
        return sorters;
    }

    public static List<ManagerData.RadarEntry> getRadar() {
        return radar;
    }

    public static int getVersion() {
        return version;
    }

    public static void clear() {
        sorters = Collections.emptyList();
        radar = Collections.emptyList();
        version++;
    }

    /** 退出服务器时清空，避免下次进服前短暂显示过期数据 */
    public static final class Events {

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            clear();
            ManagerRejectCache.clear();
        }
    }
}
