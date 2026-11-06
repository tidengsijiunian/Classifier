package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ManagerData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 分类者选择器：划定工作区时挑一台分类者接管这片地。
 * 「自动」（不选）保持旧行为——绑定离玩家最近的那台。
 */
public class SorterPickerScreen extends Screen {

    private static final int WIDTH = 250;
    private static final int HEIGHT = 216;

    private static final int LIST_X = 10;
    private static final int LIST_TOP = 40;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_SPACING = 24;
    private static final int VISIBLE_ROWS = 5;
    private static final int ROW_BG = 0x14FFFFFF;
    private static final int ROW_BG_HOVER = 0x40FFFFFF;

    private final Screen parent;
    private int leftPos;
    private int topPos;
    private int scrollOffset;

    public SorterPickerScreen(Screen parent) {
        super(new TextComponent("选择分类者"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        this.rebuildWidgets();
    }

    private void rebuildWidgets() {
        this.clearWidgets();
        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int maxScroll = Math.max(0, sorters.size() - VISIBLE_ROWS);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));

        int end = Math.min(sorters.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            ManagerData.SorterInfo info = sorters.get(i);
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            this.addRenderableWidget(new Button(this.leftPos + WIDTH - 54, rowY + 1, 44, 20,
                    new TextComponent("选中"), b -> this.choose(info.id(), info.name())));
        }

        this.addRenderableWidget(new Button(this.leftPos + 10, this.topPos + HEIGHT - 28, 110, 20,
                new TextComponent("用最近的一台"), b -> this.choose(null, null)));
        this.addRenderableWidget(new Button(this.leftPos + WIDTH - 90, this.topPos + HEIGHT - 28, 80, 20,
                new TextComponent("返回"), b -> Minecraft.getInstance().setScreen(this.parent)));
    }

    private void choose(@Nullable java.util.UUID sorterId, @Nullable String name) {
        WorkspaceScreen.setPendingTarget(sorterId, name);
        Minecraft.getInstance().setScreen(this.parent);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);

        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);

        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int rowX = this.leftPos + LIST_X;
        int rowRight = this.leftPos + WIDTH - LIST_X;
        if (sorters.isEmpty()) {
            this.font.draw(poseStack, "暂无分类者",
                    this.leftPos + (WIDTH - this.font.width("暂无分类者")) / 2.0F,
                    this.topPos + LIST_TOP + 20, ManagerUi.COLOR_TEXT_FAINT);
        }
        int end = Math.min(sorters.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            ManagerData.SorterInfo info = sorters.get(i);
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            boolean hovered = mouseX >= rowX && mouseX < rowRight && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            GuiComponent.fill(poseStack, rowX, rowY, rowRight, rowY + ROW_HEIGHT,
                    hovered ? ROW_BG_HOVER : ROW_BG);
            this.font.draw(poseStack, ManagerUi.ellipsize(this.font, info.name(), 130),
                    rowX + 8, rowY + 3, ManagerUi.COLOR_TEXT);
            this.font.draw(poseStack, dimensionName(info.dimension()),
                    rowX + 8, rowY + 12, ManagerUi.COLOR_TEXT_FAINT);
        }

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private static String dimensionName(ResourceLocation dimension) {
        if (dimension.equals(ResourceLocation.withDefaultNamespace("overworld"))) {
            return "主世界";
        }
        if (dimension.equals(ResourceLocation.withDefaultNamespace("the_nether"))) {
            return "下界";
        }
        if (dimension.equals(ResourceLocation.withDefaultNamespace("the_end"))) {
            return "末地";
        }
        return dimension.toString();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int maxScroll = Math.max(0, sorters.size() - VISIBLE_ROWS);
        if (maxScroll > 0 && mouseX >= this.leftPos + LIST_X && mouseX < this.leftPos + WIDTH - LIST_X
                && mouseY >= this.topPos + LIST_TOP
                && mouseY < this.topPos + LIST_TOP + VISIBLE_ROWS * ROW_SPACING) {
            int next = Math.max(0, Math.min(maxScroll, this.scrollOffset - (int) Math.signum(delta)));
            if (next != this.scrollOffset) {
                this.scrollOffset = next;
                this.rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
