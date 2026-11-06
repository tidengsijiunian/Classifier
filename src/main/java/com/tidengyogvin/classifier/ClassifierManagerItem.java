package com.tidengyogvin.classifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;

/**
 * 分类者管理器：用于在工作现场把容器和附近的分类者绑定，并提供管理界面与工作区划定。
 *
 * - 右键容器：记录选中的容器；
 * - Shift + 右键分类者：把已选中的容器绑定到该分类者；
 * - 右键空气：打开管理器主界面。
 *
 * 选中目标与工作区边界都存放在物品 NBT 上：客户端渲染高亮时直接从手持物品读取，
 * 真正的绑定只在服务端执行。
 */
public class ClassifierManagerItem extends Item {

    /** 选中容器坐标的 NBT 键 */
    public static final String TAG_SELECTED_POS = "SelectedPos";
    /** 工作区点 A 的 NBT 键 */
    public static final String TAG_POS1 = "Pos1";
    /** 工作区点 B 的 NBT 键 */
    public static final String TAG_POS2 = "Pos2";

    public ClassifierManagerItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键方块只做“选中容器”：蓄力在物品上，真正的绑定发生在对分类者 Shift+右键时
     * （见 {@link #interactLivingEntity}）。不是合法容器时直接 PASS，不给任何提示。
     */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        if (!isBindableContainer(context.getLevel(), pos)) {
            return InteractionResult.PASS;
        }
        // 双箱：无论点到哪一半，都归一化到主半锚点
        pos = ContainerUtil.getChestAnchor(context.getLevel(), pos);

        // 选中坐标写进物品 NBT；客户端也写一份，保存后线框高亮无需等待同步即可显示
        setSelectedPos(stack, pos);
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        player.displayClientMessage(new TextComponent(
                "§a已选中容器: " + formatPos(pos) + "，请 Shift+右键分类者进行绑定"), false);
        return InteractionResult.SUCCESS;
    }

    /**
     * Shift+右键分类者执行绑定。
     *
     * 优先级说明：1.18.2 的 {@code Player.interactOn} 会先调用实体的 {@code mobInteract}，
     * 只有那边返回 PASS 才会继续走物品的 {@code interactLivingEntity}。因此
     * {@link SorterEntity#mobInteract} 在“Shift + 手持管理器”时主动让路，本方法才可能被调用。
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand) {
        if (!(entity instanceof SorterEntity) || !player.isShiftKeyDown()) {
            // 普通右键实体不拦截，交还给分类者自己的开界面逻辑
            return InteractionResult.PASS;
        }

        BlockPos selected = getSelectedAnchor(player.level, stack);
        if (selected == null) {
            if (!player.level.isClientSide) {
                player.displayClientMessage(new TextComponent("§c请先右键一个箱子选中"), false);
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        }

        if (player.level.isClientSide) {
            // 绑定结果由服务端权威执行；客户端只保证交互不继续往下走
            return InteractionResult.SUCCESS;
        }

        SorterEntity sorter = (SorterEntity) entity;
        if (!isBindableContainer(player.level, selected)) {
            player.displayClientMessage(new TextComponent("§c选中的容器已不存在，请重新右键选中"), false);
            setSelectedPos(stack, null);
            return InteractionResult.SUCCESS;
        }
        if (!(player.level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        // 占用校验以服务端实体字段为准（手持 NBT 只是输入）：
        // 同一台分类者重复绑自己已占用的箱子是幂等的；绑别人占着的箱子则拒绝并点名占用者
        BlockPos currentAnchor = sorter.getSorterBoxPos();
        currentAnchor = currentAnchor == null ? null : SorterOccupancy.anchorOf(serverLevel, currentAnchor);
        if (selected.equals(currentAnchor)) {
            // 已经绑好了：不改数据、不提示，只把选中状态收掉
            setSelectedPos(stack, null);
            return InteractionResult.SUCCESS;
        }
        SorterEntity boxOccupant = SorterOccupancy.scan(serverLevel).boxOccupant(selected);
        if (boxOccupant != null && boxOccupant != sorter) {
            // 保留选中，玩家换一台分类者接着绑，不用重新右键箱子
            player.sendMessage(new TextComponent("§c该箱子已被 "
                    + boxOccupant.getName().getString() + " 占用，无法绑定"), player.getUUID());
            return InteractionResult.SUCCESS;
        }

        // 谁把分类箱绑到分类者身上，谁就是它的主人（管理器保存工作区时只同步给主名下的分类者）
        sorter.setOwner(player.getUUID(), player.getName().getString());
        sorter.setSorterBoxPos(selected);
        sorter.refreshWorkingStateFromSorterBox();
        setSelectedPos(stack, null);
        player.displayClientMessage(new TextComponent("§a绑定成功！分类箱 "
                + formatPos(selected) + " → " + sorter.getName().getString()), false);
        return InteractionResult.SUCCESS;
    }

    /**
     * 右键空气打开管理器。界面只存在于客户端，服务端只负责消费物品使用事件并回推数据，
     * 因此用 DistExecutor 把界面类隔离在客户端。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.tidengyogvin.classifier.client.ManagerScreen.open());
        } else if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            // 打开管理器的同时下发一份最新快照（分类者 + 工作区表）
            com.tidengyogvin.classifier.network.ManagerActionPacket.syncTo(serverPlayer);
            com.tidengyogvin.classifier.network.WorkspaceSyncPacket.sendTo(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        BlockPos pos1 = getWorkspaceCorner(stack, TAG_POS1);
        BlockPos pos2 = getWorkspaceCorner(stack, TAG_POS2);
        if (pos1 != null && pos2 != null) {
            tooltip.add(new TextComponent("§7工作区: §f" + formatPos(pos1) + " §7→ §f" + formatPos(pos2)));
        } else {
            tooltip.add(new TextComponent("§8工作区未划定"));
        }
        tooltip.add(new TextComponent("§7右键空气打开管理器"));
    }

    /** 该坐标是否是管理器可选的容器（严格限定箱子 / 木桶 / 潜影盒） */
    public static boolean isBindableContainer(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return (state.getBlock() instanceof ChestBlock
                || state.getBlock() instanceof BarrelBlock
                || state.getBlock() instanceof ShulkerBoxBlock)
                && level.getBlockEntity(pos) instanceof Container;
    }

    /** 读取选中坐标；旧存档里可能存的是双箱的副半，读取时统一归一化 */
    @Nullable
    public static BlockPos getSelectedPos(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_SELECTED_POS)) {
            return null;
        }
        return BlockPos.of(tag.getLong(TAG_SELECTED_POS));
    }

    /** 选中坐标归一化到双箱主半；区块未加载时原样返回，避免读方块状态触发强制加载 */
    @Nullable
    public static BlockPos getSelectedAnchor(Level level, ItemStack stack) {
        BlockPos pos = getSelectedPos(stack);
        if (pos == null || level == null || !level.hasChunkAt(pos)) {
            return pos;
        }
        return ContainerUtil.getChestAnchor(level, pos);
    }

    /**
     * 选中容器的渲染包围盒：双箱合并两半的方块形状，其它容器用单块形状。
     * 包围盒本身很小时直接返回；异常数据由渲染层再做尺寸校验。
     */
    @Nullable
    public static AABB getContainerBox(Level level, BlockPos pos) {
        AABB box = getBlockBox(level, pos);
        if (box == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            AABB otherBox = getBlockBox(level, other);
            if (otherBox != null) {
                box = box.minmax(otherBox);
            }
        }
        return box;
    }

    @Nullable
    private static AABB getBlockBox(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        VoxelShape shape = state.getShape(level, pos);
        return shape.isEmpty()
                ? new AABB(pos)
                : shape.bounds().move(pos.getX(), pos.getY(), pos.getZ());
    }

    public static void setSelectedPos(ItemStack stack, @Nullable BlockPos pos) {
        if (pos == null) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(TAG_SELECTED_POS);
            }
            return;
        }
        stack.getOrCreateTag().putLong(TAG_SELECTED_POS, pos.asLong());
    }

    /** 读取工作区的一个端点；键不存在时返回 null */
    @Nullable
    public static BlockPos getWorkspaceCorner(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(key)) {
            return null;
        }
        return BlockPos.of(tag.getLong(key));
    }

    /** 写入工作区边界；任一坐标为 null 时清空两端，保证 AABB 不会只存半边 */
    public static void setWorkspaceCorners(ItemStack stack, @Nullable BlockPos pos1, @Nullable BlockPos pos2) {
        CompoundTag tag = stack.getOrCreateTag();
        if (pos1 == null || pos2 == null) {
            tag.remove(TAG_POS1);
            tag.remove(TAG_POS2);
            return;
        }
        tag.putLong(TAG_POS1, pos1.asLong());
        tag.putLong(TAG_POS2, pos2.asLong());
    }

    /** 展开两端点得到可用于渲染和判定的 AABB；未划定时返回 null */
    @Nullable
    public static AABB getWorkspaceBox(ItemStack stack) {
        BlockPos pos1 = getWorkspaceCorner(stack, TAG_POS1);
        BlockPos pos2 = getWorkspaceCorner(stack, TAG_POS2);
        if (pos1 == null || pos2 == null) {
            return null;
        }
        // 玩家可能先点 B 再点 A，这里按分量排序后再向外扩展一整格
        double minX = Math.min(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double maxY = Math.max(pos1.getY(), pos2.getY());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
    }

    /**
     * 找到当前手持的管理器：主手优先，其次副手。
     * 界面对 NBT 的修改最终都通过这个结果定位到真实物品。
     */
    public static ItemStack findHeldManager(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ItemInit.CLASSIFIER_MANAGER.get())) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.is(ItemInit.CLASSIFIER_MANAGER.get())) {
            return off;
        }
        return ItemStack.EMPTY;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /**
     * 手持模型：默认 2D 贴图中的方块坐标，用来让高亮线框精确贴合物品模型的四个角。
     * 只在客户端调用时通过反射加载，避免服务端类加载进 Minecraft 客户端类。
     */
    @Nullable
    public static AABB getItemBounds(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return null;
        }
        BakedQuad quad = null;
        for (BakedQuad candidate : mc.getItemRenderer().getItemModelShaper().getItemModel(stack)
                .getQuads(null, null, new Random(42L))) {
            if (candidate.getVertices().length >= 20) {
                quad = candidate;
                break;
            }
        }
        if (quad == null) {
            return null;
        }
        int[] vs = quad.getVertices();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            float x = Float.intBitsToFloat(vs[base]);
            float y = Float.intBitsToFloat(vs[base + 1]);
            float z = Float.intBitsToFloat(vs[base + 2]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        // 平滑模型是 0..1 的方块空间坐标，缩放到物品在手中大约 0.3 格宽
        float scale = 0.3125F;
        return new AABB(
                (minX - 0.5F) * scale, (minY - 0.5F) * scale, (minZ - 0.5F) * scale,
                (maxX - 0.5F) * scale, (maxY - 0.5F) * scale, (maxZ - 0.5F) * scale);
    }

    /** 选中容器是否仍然存在（客户端高亮用） */
    public static boolean hasSelection(ItemStack stack, Level level) {
        BlockPos pos = getSelectedAnchor(level, stack);
        if (pos == null || level == null) {
            return false;
        }
        return level.hasChunkAt(pos) && isBindableContainer(level, pos);
    }
}
