package com.tidengyogvin.classifier.network;

import com.tidengyogvin.classifier.ClassifierManagerItem;
import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.ModEntities;
import com.tidengyogvin.classifier.SorterEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：在玩家面前生成一台新的分类者。
 *
 * 名字规则：留空用默认名「分类者」；默认名被占用时自动往后加 -2、-3……
 * （因为默认名是自动生成的，报错重名没有意义，反而会让「创建一台不命名的」永远失败）。
 * 玩家自己填的名字重复则直接拒绝并给提示，符合方丈「名称不可重复」的要求。
 */
public class CreateSorterPacket {

    private static final int PERMISSION_LEVEL = 2;
    private static final int MAX_NAME_LENGTH = 16;
    private static final String DEFAULT_NAME = "分类者";
    private static final int MAX_DEFAULT_SUFFIX = 999;

    @Nullable
    private final String name;

    public CreateSorterPacket(@Nullable String name) {
        this.name = name;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.name != null);
        if (this.name != null) {
            buf.writeUtf(this.name, MAX_NAME_LENGTH);
        }
    }

    public static CreateSorterPacket decode(FriendlyByteBuf buf) {
        return new CreateSorterPacket(buf.readBoolean() ? buf.readUtf(MAX_NAME_LENGTH) : null);
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.getLevel();
            if (!player.hasPermissions(PERMISSION_LEVEL)
                    && ClassifierManagerItem.findHeldManager(player).isEmpty()) {
                player.displayClientMessage(new TextComponent("§c需要管理员权限，或手持分类者管理器"), false);
                return;
            }

            String requested = this.name == null ? "" : this.name.trim();
            if (requested.length() > MAX_NAME_LENGTH) {
                player.displayClientMessage(new TextComponent("§c名字最多 " + MAX_NAME_LENGTH + " 个字符"), false);
                return;
            }
            String finalName;
            if (requested.isEmpty()) {
                finalName = nextDefaultName(player.getServer());
                if (finalName == null) {
                    player.displayClientMessage(new TextComponent("§c默认名字已被占满，请手动命名"), false);
                    return;
                }
            } else if (ManagerData.isSorterNameTaken(player.getServer(), requested, null)) {
                player.displayClientMessage(new TextComponent("§c分类者名称「" + requested + "」已存在"), false);
                return;
            } else {
                finalName = requested;
            }

            Vec3 spawn = spawnPoint(player);
            SorterEntity sorter = ModEntities.SORTER.get().create(level);
            if (sorter == null) {
                player.displayClientMessage(new TextComponent("§c创建分类者失败"), false);
                return;
            }
            sorter.moveTo(spawn.x, spawn.y, spawn.z, player.getYRot() + 180.0F, 0.0F);
            sorter.setCustomName(new TextComponent(finalName));
            sorter.setCustomNameVisible(true);
            sorter.setOwner(player.getUUID(), player.getName().getString());
            sorter.finalizeSpawn(level, level.getCurrentDifficultyAt(new BlockPos(spawn)), MobSpawnType.COMMAND,
                    null, null);
            level.addFreshEntity(sorter);

            player.displayClientMessage(new TextComponent("§a已创建分类者「" + finalName + "」"), true);
            ManagerActionPacket.syncTo(player);
        });
        ctx.setPacketHandled(true);
    }

    /** 默认名：分类者 / 分类者-2 / 分类者-3 …；全部占满返回 null */
    @Nullable
    private static String nextDefaultName(@Nullable net.minecraft.server.MinecraftServer server) {
        // 一次取回占用表再顺延。默认名没有持久化计数器，名字跟着实体走：
        // 删掉一台「分类者-2」之后，下一个无名分类者会重新拿到「分类者-2」。
        java.util.Set<String> taken = ManagerData.collectSorterNameKeys(server, null);
        if (!taken.contains(DEFAULT_NAME.toLowerCase(java.util.Locale.ROOT))) {
            return DEFAULT_NAME;
        }
        for (int i = 2; i <= MAX_DEFAULT_SUFFIX; i++) {
            String candidate = DEFAULT_NAME + "-" + i;
            if (!taken.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }

    /** 生成点：玩家正前方两格，落到地面高度；失败时退回玩家脚边 */
    private static Vec3 spawnPoint(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        double x = player.getX() + look.x * 2.0D;
        double z = player.getZ() + look.z * 2.0D;
        double y = player.getY();
        BlockPos pos = new BlockPos(x, y, z);
        ServerLevel level = player.getLevel();
        // 只在区块已加载时探地面，避免生成动作顺带强加载一片地形
        if (level.hasChunkAt(pos)) {
            BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
            if (Math.abs(ground.getY() - y) < 6.0D) {
                y = ground.getY();
            }
        }
        return new Vec3(x, y, z);
    }
}
