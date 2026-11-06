package com.tidengyogvin.classifier;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ItemInit {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Classifier.MOD_ID);

    public static final RegistryObject<Item> SORTER_SPAWN_EGG = ITEMS.register("sorter_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.SORTER, 0x2E8B57, 0xFFD700,
                    new Item.Properties().tab(CreativeModeTab.TAB_MISC)));

    public static final RegistryObject<Item> CLASSIFIER_MANAGER = ITEMS.register("classifier_manager",
            () -> new ClassifierManagerItem(new Item.Properties()
                    .stacksTo(1)
                    .tab(CreativeModeTab.TAB_MISC)));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
