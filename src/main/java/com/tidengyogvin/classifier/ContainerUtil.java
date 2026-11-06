package com.tidengyogvin.classifier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器访问工具：统一“任意一半 ↔ 完整双箱”的解析规则。
 * 原版 ChestBlock.getContainer 每次都重新读取当前 BlockState，因此箱子在绑定后才合并成双箱也能被识别；
 * 双箱的左右两半共享同一个 ChestBlockEntity，传入哪一半都会拿到完整的 54 格视图。
 */
public final class ContainerUtil {

    private ContainerUtil() {
    }

    /**
     * 获取容器对应的完整物品存储。箱子无论传入哪一半，都会合并为 54 格的双箱视图；
     * 木桶、潜影盒、模组容器走 Forge capability。
     */
    @Nullable
    public static Container getContainer(Level level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be == null) {
            return null;
        }
        if (be instanceof ChestBlockEntity) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof ChestBlock chestBlock) {
                return ChestBlock.getContainer(chestBlock, state, level, pos, true);
            }
        }
        return be.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)
                .map(cap -> (Container) new HandlerView(cap))
                .orElse(null);
    }

    /** 把任意 IItemHandler 包装成只读语义足够的 Container（写入仍会透传），供统一流程使用 */
    private static final class HandlerView implements Container {

        private final IItemHandler handler;

        private HandlerView(IItemHandler handler) {
            this.handler = handler;
        }

        @Override
        public int getContainerSize() {
            return this.handler.getSlots();
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < this.handler.getSlots(); i++) {
                if (!this.handler.getStackInSlot(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return this.handler.getStackInSlot(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return this.handler.extractItem(slot, amount, false);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return this.handler.extractItem(slot, Integer.MAX_VALUE, false);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (this.handler instanceof IItemHandlerModifiable modifiable) {
                modifiable.setStackInSlot(slot, stack);
            }
        }

        @Override
        public void setChanged() {
            // 由 IItemHandler 自身负责变更通知
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < this.handler.getSlots(); i++) {
                this.handler.extractItem(i, Integer.MAX_VALUE, false);
            }
        }
    }

    /**
     * 把任意一半归一化为双箱的主半（RIGHT 或 SINGLE）坐标。
     * findTargetChest 扫描时会同时遇到左右两半，归一化后只处理一次，避免重复计算。
     */
    public static BlockPos getChestAnchor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)) {
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type == ChestType.LEFT) {
                BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                BlockState otherState = level.getBlockState(other);
                if (otherState.getBlock() == state.getBlock()
                        && otherState.hasProperty(ChestBlock.TYPE)
                        && otherState.getValue(ChestBlock.TYPE) == ChestType.RIGHT) {
                    return other.immutable();
                }
            }
        }
        return pos.immutable();
    }

    /**
     * 容器内容概要，用于雷达分类与自动开仓判定。
     * count 为占用的槽位数；containsOnly 判断容器里是否全部是自己类别的物品。
     */
    public record ContainerSummary(boolean empty, int count,
                                   List<ItemStack> samples, List<ResourceLocation> sampleIds) {

        /** 空箱，或所有样本都严格命中该类别（同物品也算命中标签 / 模组类别） */
        public boolean containsOnly(@Nullable String category) {
            if (this.empty) {
                return true;
            }
            if (this.samples.isEmpty()) {
                return false;
            }
            for (ItemStack sample : this.samples) {
                if (!CategoryRules.matches(category, sample)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 采样槽位上限定：只关心前若干种物品，足以判断类别即可 */
    private static final int SAMPLE_LIMIT = 8;

    /** 读取容器内容概要；容器不存在或不是可存储容器时返回 null */
    @Nullable
    public static ContainerSummary summarize(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) == null) {
            return null;
        }
        Container container = getContainer(level, pos);
        if (container == null) {
            return null;
        }
        int count = 0;
        List<ItemStack> samples = new ArrayList<>();
        List<ResourceLocation> ids = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            count++;
            if (samples.size() >= SAMPLE_LIMIT) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && ids.contains(id)) {
                continue;
            }
            samples.add(stack);
            // 只有注册表里能找到的物品才参与去重；未知物品保留为独立样本
            if (id != null) {
                ids.add(id);
            }
        }
        return new ContainerSummary(count == 0, count, samples, ids);
    }
}
