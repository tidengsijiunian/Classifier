package com.tidengyogvin.classifier;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 箱子存放类型规则。
 *
 * 纯净空箱收到第一件物品时推断类别，类别字符串直接存进 SorterEntity 的 NBT：
 * - "tag:<namespace>:<path>"：标签箱，例如 tag:forge:ores、tag:minecraft:logs
 * - "mod:<namespace>"：模组专属箱，例如 mod:create
 * - "item:<namespace>:<path>"：普通物品箱，只用于展示，不限制其它物品进入
 *
 * 打分约定：命中标签类别给最高分（{@link #TAG_SCORE}）；同模组命名空间额外加分（{@link #MOD_BONUS}）。
 */
public final class CategoryRules {

    public static final String PREFIX_TAG = "tag:";
    public static final String PREFIX_MOD = "mod:";
    public static final String PREFIX_ITEM = "item:";

    /** 命中标签箱的最高分，保证优先于同类物品的 100 分 */
    public static final int TAG_SCORE = 1000;
    /** 同一模组命名空间（非 minecraft）的加分 */
    public static final int MOD_BONUS = 30;

    /** 空箱开仓时优先匹配的标签，越靠前越优先。子标签（如 forge:ores/iron）也算命中 */
    private static final List<ResourceLocation> PRIORITY_TAGS = List.of(
            new ResourceLocation("forge", "ores"),
            new ResourceLocation("forge", "raw_materials"),
            new ResourceLocation("forge", "ingots"),
            new ResourceLocation("forge", "nuggets"),
            new ResourceLocation("forge", "gems"),
            new ResourceLocation("forge", "dusts"),
            new ResourceLocation("forge", "storage_blocks"),
            new ResourceLocation("forge", "crops"),
            new ResourceLocation("forge", "seeds"),
            new ResourceLocation("minecraft", "logs"),
            new ResourceLocation("minecraft", "planks"),
            new ResourceLocation("minecraft", "wool"),
            new ResourceLocation("minecraft", "sand"),
            new ResourceLocation("minecraft", "flowers")
    );

    private CategoryRules() {
    }

    /**
     * 推断一件物品所属的箱子类别。非空物品一定返回非 null：
     * 先匹配内置优先标签，其次按模组命名空间，最后退化为具体物品。
     */
    public static String inferCategory(ItemStack stack) {
        for (ResourceLocation tagId : PRIORITY_TAGS) {
            if (itemHasTag(stack, tagId)) {
                return PREFIX_TAG + tagId;
            }
        }
        String namespace = namespaceOf(stack);
        if (!namespace.isEmpty() && !ResourceLocation.DEFAULT_NAMESPACE.equals(namespace)) {
            return PREFIX_MOD + namespace;
        }
        return PREFIX_ITEM + idOf(stack);
    }

    /** 物品是否属于某个箱子类别（标签 / 模组 / 具体物品） */
    public static boolean matches(@Nullable String category, ItemStack stack) {
        if (category == null || stack.isEmpty()) {
            return false;
        }
        if (category.startsWith(PREFIX_TAG)) {
            ResourceLocation tagId = ResourceLocation.tryParse(category.substring(PREFIX_TAG.length()));
            return tagId != null && itemHasTag(stack, tagId);
        }
        if (category.startsWith(PREFIX_MOD)) {
            return category.substring(PREFIX_MOD.length()).equals(namespaceOf(stack));
        }
        if (category.startsWith(PREFIX_ITEM)) {
            return category.substring(PREFIX_ITEM.length()).equals(idOf(stack));
        }
        return false;
    }

    public static boolean isTagCategory(@Nullable String category) {
        return category != null && category.startsWith(PREFIX_TAG);
    }

    public static boolean isModCategory(@Nullable String category) {
        return category != null && category.startsWith(PREFIX_MOD);
    }

    /** 该类别是否“专用”（标签箱 / 模组箱）：专用空箱只接收命中类别的物品 */
    public static boolean isReservedCategory(@Nullable String category) {
        return isTagCategory(category) || isModCategory(category);
    }

    /**
     * 物品是否带指定标签。tag 本身与它的子标签都算命中，
     * 兼容部分整合包只给物品打 forge:ores/iron 子标签的情况。
     */
    public static boolean itemHasTag(ItemStack stack, ResourceLocation tagId) {
        if (stack.isEmpty()) {
            return false;
        }
        TagKey<Item> tag = TagKey.create(Registry.ITEM_REGISTRY, tagId);
        if (stack.is(tag)) {
            return true;
        }
        String path = tagId.getPath();
        for (TagKey<Item> owned : stack.getTags().toList()) {
            ResourceLocation location = owned.location();
            if (location.getNamespace().equals(tagId.getNamespace())
                    && (location.getPath().equals(path) || location.getPath().startsWith(path + "/"))) {
                return true;
            }
        }
        return false;
    }

    /** 两个物品是否来自同一个非原版模组命名空间 */
    public static boolean sameNonVanillaNamespace(ItemStack a, ItemStack b) {
        String namespace = namespaceOf(a);
        return !namespace.isEmpty() && !ResourceLocation.DEFAULT_NAMESPACE.equals(namespace)
                && namespace.equals(namespaceOf(b));
    }

    public static String namespaceOf(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "" : id.getNamespace();
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    /** 雷达列表里的中文类别名 */
    public static String displayName(@Nullable String category) {
        if (category == null) {
            return "未分类";
        }
        if (category.startsWith(PREFIX_TAG)) {
            return switch (category.substring(PREFIX_TAG.length())) {
                case "forge:ores" -> "矿物箱";
                case "forge:raw_materials" -> "粗矿箱";
                case "forge:ingots" -> "锭箱";
                case "forge:nuggets" -> "粒箱";
                case "forge:gems" -> "宝石箱";
                case "forge:dusts" -> "粉末箱";
                case "forge:storage_blocks" -> "矿块箱";
                case "forge:crops" -> "作物箱";
                case "forge:seeds" -> "种子箱";
                case "minecraft:logs" -> "原木箱";
                case "minecraft:planks" -> "木板箱";
                case "minecraft:wool" -> "羊毛箱";
                case "minecraft:sand" -> "沙子箱";
                case "minecraft:flowers" -> "花卉箱";
                default -> category.substring(PREFIX_TAG.length()) + " 箱";
            };
        }
        if (category.startsWith(PREFIX_MOD)) {
            String namespace = category.substring(PREFIX_MOD.length());
            return ("create".equals(namespace) ? "Create" : namespace) + " 专属箱";
        }
        if (category.startsWith(PREFIX_ITEM)) {
            return "普通物品箱";
        }
        return category;
    }

    /** 雷达与线框的类别配色：标签箱金色、模组箱蓝色、普通物品箱灰色 */
    public static float[] color(@Nullable String category) {
        if (isTagCategory(category)) {
            return new float[]{1.0F, 0.82F, 0.35F};
        }
        if (isModCategory(category)) {
            return new float[]{0.45F, 0.72F, 1.0F};
        }
        return new float[]{0.72F, 0.72F, 0.72F};
    }
}
