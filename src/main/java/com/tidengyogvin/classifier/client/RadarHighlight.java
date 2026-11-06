package com.tidengyogvin.classifier.client;

import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;

/**
 * 仓储雷达的“选中箱子”：雷达界面上点击一行后记录在这里，
 * 世界中由 {@link ManagerRenderer} 以透视线框高亮。关闭界面后依然保留，直到：
 * - 玩家右键打开了该箱子且没有锁定（自动取消）；
 * - 玩家在雷达里选了别的箱子 / 手动清除；
 * - 退出服务器。
 */
public final class RadarHighlight {

    @Nullable
    private static volatile BlockPos selected;

    private RadarHighlight() {
    }

    public static void select(@Nullable BlockPos pos) {
        selected = pos == null ? null : pos.immutable();
    }

    @Nullable
    public static BlockPos get() {
        return selected;
    }

    public static void clear() {
        selected = null;
    }

    /** 当前玩家是否锁定了这个箱子（锁定状态来自服务端快照） */
    public static boolean isLocked(BlockPos pos) {
        for (com.tidengyogvin.classifier.ManagerData.RadarEntry entry : ManagerSyncCache.getRadar()) {
            if (entry.pos().equals(pos) && entry.locked()) {
                return true;
            }
        }
        return false;
    }

    /** 该箱子对应的雷达条目，不存在时返回 null */
    @Nullable
    public static com.tidengyogvin.classifier.ManagerData.RadarEntry entryOf(BlockPos pos) {
        for (com.tidengyogvin.classifier.ManagerData.RadarEntry entry : ManagerSyncCache.getRadar()) {
            if (entry.pos().equals(pos)) {
                return entry;
            }
        }
        return null;
    }

    public static final class Events {

        @SubscribeEvent
        public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
            clear();
        }
    }
}
