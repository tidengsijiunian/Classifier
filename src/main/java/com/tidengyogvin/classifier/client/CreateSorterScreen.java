package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.network.CreateSorterPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

/**
 * 创建分类者：只填名字，位置由服务端放在玩家正前方。
 * 名字留空时服务端用默认名「分类者」；默认名被占用会自动加 -2、-3……
 */
public class CreateSorterScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 136;

    private EditBox nameBox;
    private int leftPos;
    private int topPos;

    public CreateSorterScreen() {
        super(new TextComponent("创建分类者"));
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;

        this.nameBox = new EditBox(this.font, this.leftPos + 24, this.topPos + 54,
                WIDTH - 48, 18, new TextComponent("分类者名称"));
        this.nameBox.setMaxLength(16);
        this.nameBox.setFilter(text -> !text.startsWith("§"));
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        int buttonY = this.topPos + 90;
        this.addRenderableWidget(new Button(this.leftPos + 28, buttonY, 76, 20,
                new TextComponent("创建"), b -> this.confirm()));
        this.addRenderableWidget(new Button(this.leftPos + WIDTH - 28 - 76, buttonY, 76, 20,
                new TextComponent("取消"), b -> this.onClose()));
    }

    private void confirm() {
        ModNetwork.INSTANCE.sendToServer(new CreateSorterPacket(this.nameBox.getValue().trim()));
        this.onClose();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);

        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);
        this.font.draw(poseStack, "留空则使用默认名「分类者」", this.leftPos + 24, this.topPos + 40,
                ManagerUi.COLOR_TEXT_DIM);
        this.font.draw(poseStack, "名称不可重复，重复时服务端会拒绝", this.leftPos + 24, this.topPos + 78,
                ManagerUi.COLOR_TEXT_FAINT);

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new ManagerScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
