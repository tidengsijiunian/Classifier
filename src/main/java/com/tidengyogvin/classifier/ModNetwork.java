package com.tidengyogvin.classifier;

import com.tidengyogvin.classifier.network.CreateSorterPacket;
import com.tidengyogvin.classifier.network.ManagerActionPacket;
import com.tidengyogvin.classifier.network.ManagerRejectPacket;
import com.tidengyogvin.classifier.network.ManagerSyncPacket;
import com.tidengyogvin.classifier.network.RenameSorterPacket;
import com.tidengyogvin.classifier.network.SelectSkinPacket;
import com.tidengyogvin.classifier.network.ToggleWorkingPacket;
import com.tidengyogvin.classifier.network.WorkspaceActionPacket;
import com.tidengyogvin.classifier.network.WorkspaceSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModNetwork {

    /** 协议版本：工作区对象化之后包格式变了，旧客户端必须被拒绝 */
    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Classifier.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        INSTANCE.registerMessage(id++, SelectSkinPacket.class,
                SelectSkinPacket::encode,
                SelectSkinPacket::decode,
                SelectSkinPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        INSTANCE.registerMessage(id++, ToggleWorkingPacket.class,
                ToggleWorkingPacket::encode,
                ToggleWorkingPacket::decode,
                ToggleWorkingPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        INSTANCE.registerMessage(id++, RenameSorterPacket.class,
                RenameSorterPacket::encode,
                RenameSorterPacket::decode,
                RenameSorterPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        INSTANCE.registerMessage(id++, ManagerSyncPacket.class,
                ManagerSyncPacket::encode,
                ManagerSyncPacket::decode,
                ManagerSyncPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        INSTANCE.registerMessage(id++, ManagerActionPacket.class,
                ManagerActionPacket::encode,
                ManagerActionPacket::decode,
                ManagerActionPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        INSTANCE.registerMessage(id++, ManagerRejectPacket.class,
                ManagerRejectPacket::encode,
                ManagerRejectPacket::decode,
                ManagerRejectPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        INSTANCE.registerMessage(id++, WorkspaceSyncPacket.class,
                WorkspaceSyncPacket::encode,
                WorkspaceSyncPacket::decode,
                WorkspaceSyncPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        INSTANCE.registerMessage(id++, WorkspaceActionPacket.class,
                WorkspaceActionPacket::encode,
                WorkspaceActionPacket::decode,
                WorkspaceActionPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        INSTANCE.registerMessage(id++, CreateSorterPacket.class,
                CreateSorterPacket::encode,
                CreateSorterPacket::decode,
                CreateSorterPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}
