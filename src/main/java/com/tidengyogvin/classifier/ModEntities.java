package com.tidengyogvin.classifier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITIES, Classifier.MOD_ID);

    public static final RegistryObject<EntityType<SorterEntity>> SORTER = ENTITIES.register("sorter",
            () -> EntityType.Builder.of(SorterEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .build(new ResourceLocation(Classifier.MOD_ID, "sorter").toString()));

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
    }
}
