package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.WorkspaceTable;
import com.tidengyogvin.classifier.network.WorkspaceActionPacket;
import com.tidengyogvin.classifier.network.WorkspaceSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

/**
 * 工作区改名：留空提交表示去掉名字、回到「未命名工作区」。
 * 重名由服务端拒绝并在聊天栏提示，这里不做判断。
 */
public class RenameWorkspaceScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 140;

    private final WorkspaceManageScreen parent;
    private final WorkspaceSyncPacket.Entry entry;
    private EditBox nameBox;
    private int leftPos;
    private int topPos;

    public RenameWorkspaceScreen(WorkspaceManageScreen parent, WorkspaceSyncPacket.Entry entry) {
        super(new TextComponent("工作区改名"));
        this.parent = parent;
        this.entry = entry;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;

        this.nameBox = new EditBox(this.font, this.leftPos + 24, this.topPos + 58,
                WIDTH - 48, 18, new TextComponent("工作区名称"));
        this.nameBox.setMaxLength(WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH);
        this.nameBox.setValue(this.entry.hasName() ? this.entry.name() : "");
        this.nameBox.setFilter(text -> !text.startsWith("§"));
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        int buttonY = this.topPos + 92;
        this.addRenderableWidget(new Button(this.leftPos + 28, buttonY, 76, 20,
                new TextComponent("确认"), b -> this.confirm()));
        this.addRenderableWidget(new Button(this.leftPos + WIDTH - 28 - 76, buttonY, 76, 20,
                new TextComponent("取消"), b -> this.onClose()));
    }

    private void confirm() {
        ModNetwork.INSTANCE.sendToServer(
                WorkspaceActionPacket.rename(this.entry.id(), this.nameBox.getValue().trim()));
        this.onClose();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);

        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);

        String current = "当前：" + (this.entry.hasName() ? this.entry.name() : "未命名工作区");
        this.font.draw(poseStack, ManagerUi.ellipsize(this.font, current, WIDTH - 40),
                this.leftPos + 24, this.topPos + 40, ManagerUi.COLOR_TEXT_DIM);
        this.font.draw(poseStack, "留空表示去掉名字", this.leftPos + 24, this.topPos + 82,
                ManagerUi.COLOR_TEXT_FAINT);

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
