package com.tidengyogvin.classifier.client;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端短暂的“本次绑定被拒”状态，用于管理器界面行闪红。
 *
 * 只保存到过期时间为止，不参与任何服务端判定；玩家重连时清空。
 * 网络线程写入、渲染线程读取，用 ConcurrentHashMap 保证可见性。
 */
public final class ManagerRejectCache {

    /** 行闪红持续时间：足够看清，又不至于让旧结果一直挂着 */
    private static final long FLASH_MILLIS = 4_000L;

    private record Entry(String occupantName, long expiresAt) {
    }

    private static final Map<UUID, Entry> REJECTED = new ConcurrentHashMap<>();

    private ManagerRejectCache() {
    }

    public static void reject(List<UUID> ids, String occupantName) {
        long expiresAt = System.currentTimeMillis() + FLASH_MILLIS;
        for (UUID id : ids) {
            REJECTED.put(id, new Entry(occupantName, expiresAt));
        }
        // 顺手清理过期条目，避免长时间停留界面时表无限增长
        long now = System.currentTimeMillis();
        REJECTED.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    /** 该分类者当前是否处于“刚被拒绝”的闪红窗口内 */
    public static boolean isRejected(UUID id) {
        return remainingMillis(id) > 0;
    }

    /** 占用者名字；不在闪红窗口内返回 null */
    @Nullable
    public static String occupantName(UUID id) {
        Entry entry = REJECTED.get(id);
        if (entry == null || entry.expiresAt() <= System.currentTimeMillis()) {
            return null;
        }
        return entry.occupantName();
    }

    public static long remainingMillis(UUID id) {
        Entry entry = REJECTED.get(id);
        if (entry == null) {
            return 0L;
        }
        return Math.max(0L, entry.expiresAt() - System.currentTimeMillis());
    }

    public static void clear() {
        REJECTED.clear();
    }
}
