package com.tidengyogvin.classifier.client;

import com.tidengyogvin.classifier.Classifier;
import com.tidengyogvin.classifier.ModEntities;
import com.tidengyogvin.classifier.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Classifier.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SORTER.get(), SorterRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.SORTER_MENU.get(), SorterScreen::new);
            // 这些类的静态初始化会触碰 Blaze3D 渲染状态；必须等窗口与 GL 上下文就绪后再加载，
            // 因此不用 @Mod.EventBusSubscriber（Mod 扫描期就会强制加载），改为此处手动注册。
            MinecraftForge.EVENT_BUS.register(ManagerRenderer.class);
            MinecraftForge.EVENT_BUS.register(RadarInteractionHandler.class);
            MinecraftForge.EVENT_BUS.register(WorkspaceScrollHandler.class);
            MinecraftForge.EVENT_BUS.register(ManagerSyncCache.Events.class);
            MinecraftForge.EVENT_BUS.register(WorkspaceSyncCache.Events.class);
            MinecraftForge.EVENT_BUS.register(RadarHighlight.Events.class);
        });
    }
}
