package com.tidengyogvin.classifier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.synchronization.ArgumentTypes;
import net.minecraft.commands.synchronization.EmptyArgumentSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SorterCommand {

    private static final NameArgument NAME_ARGUMENT = NameArgument.INSTANCE;

    private SorterCommand() {
    }

    public static void registerArgumentType() {
        if (!ArgumentTypes.isTypeRegistered(NAME_ARGUMENT)) {
            ArgumentTypes.register(Classifier.MOD_ID + ":sorter_name", NameArgument.class,
                    new EmptyArgumentSerializer<>(() -> NAME_ARGUMENT));
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(createCommand("cls"));
        dispatcher.register(createCommand("classification"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createCommand(String name) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("name", NAME_ARGUMENT)
                                .executes(SorterCommand::addSorter)
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(SorterCommand::addSorterAtPos))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", NAME_ARGUMENT)
                                .suggests(SorterCommand::suggestSorterNames)
                                .executes(SorterCommand::removeSorters)))
                .then(Commands.literal("list")
                        .executes(SorterCommand::listSorters))
                .then(Commands.literal("allremove")
                        .executes(SorterCommand::removeAllSorters))
                .then(Commands.literal("lock")
                        .then(Commands.argument("name", NAME_ARGUMENT)
                                .suggests(SorterCommand::suggestSorterNames)
                                .executes(SorterCommand::lockSorter)))
                .then(Commands.literal("alllock")
                        .executes(SorterCommand::lockAllSorters))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("name", NAME_ARGUMENT)
                                .suggests(SorterCommand::suggestSorterNames)
                                .executes(SorterCommand::unlockSorter)))
                .then(Commands.literal("allunlock")
                        .executes(SorterCommand::unlockAllSorters))
                .then(Commands.literal("sort")
                        .then(Commands.argument("name", NAME_ARGUMENT)
                                .suggests(SorterCommand::suggestSorterNames)
                                .executes(SorterCommand::sortSorter)))
                .then(Commands.literal("allsort")
                        .executes(SorterCommand::sortAllSorters))
                .then(Commands.literal("bounds")
                        .then(Commands.argument("name", NAME_ARGUMENT)
                                .suggests(SorterCommand::suggestSorterNames)
                                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                                .executes(SorterCommand::setSorterBounds)))))
                .then(Commands.literal("tp")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("name", NAME_ARGUMENT)
                                        .suggests(SorterCommand::suggestSorterNames)
                                        .executes(SorterCommand::teleportPlayerToSorter)))
                        .then(Commands.literal("here")
                                .then(Commands.argument("name", NAME_ARGUMENT)
                                        .suggests(SorterCommand::suggestSorterNames)
                                        .executes(SorterCommand::teleportSorterHere)))
                        .then(Commands.literal("to")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("name", NAME_ARGUMENT)
                                                .suggests(SorterCommand::suggestSorterNames)
                                                .executes(SorterCommand::teleportSorterToPlayer)))));
    }

    private static int addSorter(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        return spawnSorter(context, getLookTargetPosition(player));
    }

    private static int addSorterAtPos(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        return spawnSorter(context, Vec3.atBottomCenterOf(pos));
    }

    private static int spawnSorter(CommandContext<CommandSourceStack> context, Vec3 spawnPosition)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        String name = StringArgumentType.getString(context, "name");

        if (findSorter(level, name) != null || ManagerData.isSorterNameTaken(source.getServer(), name, null)) {
            source.sendFailure(Component.nullToEmpty("§c分类者名字已存在"));
            return 0;
        }

        SorterEntity sorter = new SorterEntity(ModEntities.SORTER.get(), level);
        sorter.moveTo(spawnPosition.x, spawnPosition.y, spawnPosition.z, player.getYRot(), player.getXRot());
        sorter.setCustomName(Component.nullToEmpty(name));
        sorter.setCustomNameVisible(true);
        // 创建者即归属者：管理器保存工作区时只会同步给归属者名下的分类者
        sorter.setOwner(player.getUUID(), player.getName().getString());
        level.addFreshEntity(sorter);
        source.sendSuccess(Component.nullToEmpty("§a已创建分类者 " + name), true);
        return 1;
    }

    private static Vec3 getLookTargetPosition(ServerPlayer player) {
        HitResult hitResult = player.pick(5.0D, 0.0F, false);
        if (hitResult.getType() == HitResult.Type.BLOCK && hitResult instanceof BlockHitResult blockHitResult) {
            BlockPos spawnPos = blockHitResult.getBlockPos().above();
            return Vec3.atBottomCenterOf(spawnPos);
        }
        return player.getEyePosition().add(player.getLookAngle().scale(3.0D));
    }

    private static int removeSorters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        List<SorterEntity> matchedSorters = new ArrayList<>();

        for (ServerLevel level : source.getServer().getAllLevels()) {
            addMatchingSorters(level, name, matchedSorters);
        }

        for (SorterEntity sorter : matchedSorters) {
            sorter.discard();
        }
        source.sendSuccess(Component.nullToEmpty("§a已移除 " + matchedSorters.size() + " 个分类者"), true);
        return matchedSorters.size();
    }

    private static int listSorters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<SorterEntity> sorters = getAllSorters(source.getServer());
        if (sorters.isEmpty()) {
            source.sendSuccess(Component.nullToEmpty("§e全服没有分类者"), false);
            return 0;
        }

        for (SorterEntity sorter : sorters) {
            BlockPos pos = sorter.blockPosition();
            String status;
            if (sorter.isManualPause()) {
                status = "锁定";
            } else if (sorter.isWorking()) {
                status = "工作";
            } else {
                status = "休闲";
            }
            source.sendSuccess(Component.nullToEmpty(sorter.getName().getString() + " | "
                    + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " | " + status), false);
        }
        return sorters.size();
    }

    private static int removeAllSorters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<SorterEntity> sorters = getAllSorters(source.getServer());
        for (SorterEntity sorter : sorters) {
            sorter.discard();
        }
        source.sendSuccess(Component.nullToEmpty("§a已移除全服 " + sorters.size() + " 个分类者"), true);
        return sorters.size();
    }

    private static int lockSorter(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        SorterEntity sorter = findSorter(source.getServer(), name);
        if (sorter == null) {
            source.sendFailure(Component.nullToEmpty("§c未找到分类者"));
            return 0;
        }

        lockSorter(sorter);
        source.sendSuccess(Component.nullToEmpty("§a已锁定分类者 " + name), true);
        return 1;
    }

    private static int lockAllSorters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<SorterEntity> sorters = getAllSorters(source.getServer());
        for (SorterEntity sorter : sorters) {
            lockSorter(sorter);
        }
        source.sendSuccess(Component.nullToEmpty("§a已锁定全服 " + sorters.size() + " 个分类者"), true);
        return sorters.size();
    }

    private static void lockSorter(SorterEntity sorter) {
        sorter.setWorking(false);
        sorter.setManualPause(true);
    }

    private static int unlockSorter(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        SorterEntity sorter = findSorter(source.getServer(), name);
        if (sorter == null) {
            source.sendFailure(Component.nullToEmpty("§c未找到分类者"));
            return 0;
        }

        unlockSorter(sorter);
        source.sendSuccess(Component.nullToEmpty("§a已解锁分类者 " + name), true);
        return 1;
    }

    private static int unlockAllSorters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<SorterEntity> sorters = getAllSorters(source.getServer());
        for (SorterEntity sorter : sorters) {
            unlockSorter(sorter);
        }
        source.sendSuccess(Component.nullToEmpty("§a已解锁全服 " + sorters.size() + " 个分类者"), true);
        return sorters.size();
    }

    private static void unlockSorter(SorterEntity sorter) {
        sorter.setManualPause(false);
        sorter.refreshWorkingStateFromSorterBox();
    }

    private static int sortSorter(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        SorterEntity sorter = findSorter(source.getServer(), name);
        if (sorter == null) {
            source.sendFailure(Component.nullToEmpty("§c未找到分类者"));
            return 0;
        }

        sorter.triggerSort();
        source.sendSuccess(Component.nullToEmpty("§a已触发分类者 " + name + " 的整理任务"), true);
        return 1;
    }

    private static int sortAllSorters(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<SorterEntity> sorters = getAllSorters(source.getServer());
        for (SorterEntity sorter : sorters) {
            sorter.triggerSort();
        }
        source.sendSuccess(Component.nullToEmpty("§a已触发全服 " + sorters.size() + " 个分类者的整理任务"), true);
        return sorters.size();
    }

    private static int setSorterBounds(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        SorterEntity sorter = findSorter(source.getServer(), name);
        if (sorter == null) {
            source.sendFailure(Component.nullToEmpty("§c未找到分类者"));
            return 0;
        }

        BlockPos pos1 = BlockPosArgument.getLoadedBlockPos(context, "pos1");
        BlockPos pos2 = BlockPosArgument.getLoadedBlockPos(context, "pos2");
        // 严格独占：一片工作区只归一台分类者；已经持有同一片地时视为幂等，直接放行
        if (!SorterOccupancy.holdsWorkspace(sorter, pos1, pos2)) {
            SorterEntity occupant = SorterOccupancy.scan(source.getLevel()).workspaceOccupant(pos1, pos2);
            if (occupant != null && occupant != sorter) {
                source.sendFailure(Component.nullToEmpty("§c该工作区已被 "
                        + occupant.getName().getString() + " 占用，无法绑定"));
                return 0;
            }
        }
        if (!sorter.setWorkspace(pos1, pos2)) {
            source.sendFailure(Component.nullToEmpty("§c工作区范围过大，最大支持 "
                    + SorterEntity.MAX_WORKSPACE_VOLUME + " 格！"));
            return 0;
        }
        source.sendSuccess(Component.nullToEmpty("§a已设置分类者 " + name + " 的工作区: "
                + formatBlockPos(pos1) + " -> " + formatBlockPos(pos2)), true);
        return 1;
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static int teleportPlayerToSorter(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String name = StringArgumentType.getString(context, "name");

        for (ServerLevel level : source.getServer().getAllLevels()) {
            SorterEntity sorter = findSorter(level, name);
            if (sorter != null) {
                player.teleportTo(level, sorter.getX(), sorter.getY(), sorter.getZ(),
                        player.getYRot(), player.getXRot());
                source.sendSuccess(Component.nullToEmpty("§a已将玩家传送到分类者 " + name), true);
                return 1;
            }
        }

        source.sendFailure(Component.nullToEmpty("§c未找到分类者"));
        return 0;
    }

    private static int teleportSorterHere(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        String name = StringArgumentType.getString(context, "name");
        return teleportSorterToPlayer(context, player, name);
    }

    private static int teleportSorterToPlayer(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        String name = StringArgumentType.getString(context, "name");
        return teleportSorterToPlayer(context, player, name);
    }

    private static int teleportSorterToPlayer(CommandContext<CommandSourceStack> context,
                                              ServerPlayer player, String name) {
        CommandSourceStack source = context.getSource();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            SorterEntity sorter = findSorter(level, name);
            if (sorter == null) {
                continue;
            }

            ServerLevel playerLevel = player.getLevel();
            if (sorter.getLevel() != playerLevel) {
                Entity movedEntity = sorter.changeDimension(playerLevel);
                if (!(movedEntity instanceof SorterEntity movedSorter)) {
                    source.sendFailure(Component.nullToEmpty("§c分类者无法传送到当前维度"));
                    return 0;
                }
                sorter = movedSorter;
            }

            sorter.teleportTo(player.getX(), player.getY(), player.getZ());
            source.sendSuccess(Component.nullToEmpty("§a已将分类者 " + name + " 传送到玩家位置"), true);
            return 1;
        }

        source.sendFailure(Component.nullToEmpty("§c未找到分类者"));
        return 0;
    }

    private static CompletableFuture<Suggestions> suggestSorterNames(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(getAllSorterNames(context.getSource().getServer()), builder);
    }

    private static List<String> getAllSorterNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        for (SorterEntity sorter : getAllSorters(server)) {
            names.add(sorter.getName().getString());
        }
        return names;
    }

    private static List<SorterEntity> getAllSorters(MinecraftServer server) {
        // 复用 ManagerData 的存活过滤：死掉 / 已被移除的分类者不能再出现在补全和查找里
        return ManagerData.collectSorters(server);
    }

    private static SorterEntity findSorter(MinecraftServer server, String name) {
        for (ServerLevel level : server.getAllLevels()) {
            SorterEntity sorter = findSorter(level, name);
            if (sorter != null) {
                return sorter;
            }
        }
        return null;
    }

    private static SorterEntity findSorter(ServerLevel level, String name) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof SorterEntity sorter && ManagerData.isLiveSorter(sorter)
                    && name.equals(sorter.getName().getString())) {
                return sorter;
            }
        }
        return null;
    }

    private static void addMatchingSorters(ServerLevel level, String name, List<SorterEntity> matchedSorters) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof SorterEntity sorter && ManagerData.isLiveSorter(sorter)
                    && name.equals(sorter.getName().getString())) {
                matchedSorters.add(sorter);
            }
        }
    }

    // Brigadier 1.0.18 的 word() 仅接受 ASCII；补充非空白 Unicode 字符解析。
    private static final class NameArgument implements ArgumentType<String> {

        private static final NameArgument INSTANCE = new NameArgument();

        private NameArgument() {
        }

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            String word = StringArgumentType.word().parse(reader);
            if (!reader.canRead() || Character.isWhitespace(reader.peek())) {
                return word;
            }
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
                reader.skip();
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public java.util.Collection<String> getExamples() {
            return List.of("Miner", "矿工");
        }
    }
}
