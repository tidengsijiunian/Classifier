package com.tidengyogvin.classifier;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Classifier.MOD_ID)
@Mod.EventBusSubscriber(modid = Classifier.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Classifier {

    public static final String MOD_ID = "classifier";

    /** 全局日志（M2.5.0-BugFix：便于排查搬运 AI 状态机） */
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Classifier");

    public Classifier() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        try {
            ModEntities.register(modEventBus);
            ModMenuTypes.register(modEventBus);
            ItemInit.register(modEventBus);
        } catch (Throwable t) {
            LOGGER.error("注册阶段发生严重错误", t);
            throw t; // 打印后再抛出，以便看到详细堆栈
        }
        modEventBus.addListener(this::commonSetup);
        SorterCommand.registerArgumentType();
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
    }

    /**
     * 进服时把工作区表和管理器快照各推一份：工作区列表界面不依赖“手持管理器右键”也能拿到数据，
     * 否则刚上线打开界面会看到空列表。
     */
    private void onPlayerLogin(
            final net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.tidengyogvin.classifier.network.WorkspaceSyncPacket.sendTo(player);
            com.tidengyogvin.classifier.network.ManagerActionPacket.syncTo(player);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }

    private void onRegisterCommands(final RegisterCommandsEvent event) {
        SorterCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SORTER.get(), SorterEntity.createAttributes().build());
    }
}
