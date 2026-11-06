package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

/**
 * 「工作区」二级菜单：把原本一级菜单里的「划定工作区」收进来，并加上「工作区管理」。
 * 一级菜单只保留入口，功能分两级，避免主界面一排按钮越来越长。
 */
public class WorkspaceMenuScreen extends Screen {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 150;

    private int leftPos;
    private int topPos;

    public WorkspaceMenuScreen() {
        super(new TextComponent("工作区"));
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;

        int buttonWidth = 150;
        int buttonX = this.leftPos + (WIDTH - buttonWidth) / 2;
        this.addRenderableWidget(new Button(buttonX, this.topPos + 42, buttonWidth, 20,
                new TextComponent("划定工作区"), b -> Minecraft.getInstance().setScreen(new WorkspaceScreen(this)),
                (button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack,
                        new TextComponent("输入两角坐标，圈出一片工作区并绑定分类者"), mouseX, mouseY)));
        this.addRenderableWidget(new Button(buttonX, this.topPos + 68, buttonWidth, 20,
                new TextComponent("工作区管理"), b -> Minecraft.getInstance().setScreen(new WorkspaceManageScreen(this)),
                (button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack,
                        new TextComponent("查看已有工作区，改名、绑定或解除绑定"), mouseX, mouseY)));
        this.addRenderableWidget(new Button(buttonX, this.topPos + HEIGHT - 30, buttonWidth, 20,
                new TextComponent("返回"), b -> Minecraft.getInstance().setScreen(new ManagerScreen())));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);
        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
