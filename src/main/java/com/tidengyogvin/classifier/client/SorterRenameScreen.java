package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.SorterMenu;
import com.tidengyogvin.classifier.network.RenameSorterPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/** 独立的名字修改二级界面，视觉与分类者设置界面保持一致 */
public class SorterRenameScreen extends Screen {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 140;

    private final SorterMenu menu;
    private EditBox nameBox;

    private int leftPos;
    private int topPos;

    public SorterRenameScreen(SorterMenu menu) {
        super(new TextComponent("修改分类者名字"));
        this.menu = menu;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;

        this.nameBox = new EditBox(this.font, this.leftPos + 30, this.topPos + 60, 140, 18,
                new TextComponent("输入新名字"));
        this.nameBox.setMaxLength(16);
        this.nameBox.setValue(currentName());
        this.nameBox.setFilter(text -> text.length() <= 16 && !text.startsWith("§"));
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        int buttonY = this.topPos + 94;
        this.addRenderableWidget(new Button(this.leftPos + 20, buttonY, 76, 20,
                new TextComponent("确认"), b -> this.confirm()));
        this.addRenderableWidget(new Button(this.leftPos + 104, buttonY, 76, 20,
                new TextComponent("取消"), b -> this.onClose()));
    }

    private void confirm() {
        String newName = this.nameBox.getValue().trim();
        if (!newName.isEmpty()) {
            ModNetwork.INSTANCE.sendToServer(new RenameSorterPacket(this.menu.getEntityId(), newName));
        }
        this.onClose();
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);

        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);

        Component hint = new TextComponent("当前名字：" + currentName());
        this.font.draw(poseStack, ManagerUi.ellipsize(this.font, hint.getString(), WIDTH - 40),
                this.leftPos + 20, this.topPos + 40, ManagerUi.COLOR_TEXT_DIM);
        this.font.draw(poseStack, "1 - 16 个字符", this.leftPos + 20, this.topPos + 84, ManagerUi.COLOR_TEXT_FAINT);

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private String currentName() {
        SorterEntity sorter = this.getSorterEntity();
        return sorter != null ? sorter.getName().getString() : "分类者";
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            SorterEntity sorter = this.getSorterEntity();
            Component title = sorter != null ? sorter.getDisplayName() : new TextComponent("分类者");
            mc.setScreen(new SorterScreen(this.menu, mc.player.getInventory(), title));
        } else {
            super.onClose();
        }
    }

    @Nullable
    private SorterEntity getSorterEntity() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        Entity entity = this.minecraft.level.getEntity(this.menu.getEntityId());
        return entity instanceof SorterEntity ? (SorterEntity) entity : null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
