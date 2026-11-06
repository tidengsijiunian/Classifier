package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.network.WorkspaceActionPacket;
import com.tidengyogvin.classifier.network.WorkspaceSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 工作区管理：列出全部工作区（名字 / 维度 / 范围 / 绑定的分类者），
 * 支持新建（转到划定工作区界面）、改名、解除绑定。
 *
 * 数据来自服务端推送的 {@link WorkspaceSyncCache}；重名判断在服务端做，
 * 这里只负责把结果画出来，客户端不猜唯一性。
 */
public class WorkspaceManageScreen extends Screen {

    private static final int WIDTH = 272;
    private static final int HEIGHT = 214;

    private static final int LIST_X = 10;
    private static final int LIST_TOP = 40;
    private static final int ROW_HEIGHT = 28;
    private static final int ROW_SPACING = 32;
    private static final int VISIBLE_ROWS = 4;

    private static final int ROW_BG = 0x14FFFFFF;
    private static final int ROW_BG_HOVER = 0x40FFFFFF;
    private static final int ROW_ACCENT_WIDTH = 3;

    private static final int RENAME_WIDTH = 46;
    private static final int UNBIND_WIDTH = 40;
    private static final int BUTTON_GAP = 4;

    private final Screen parent;
    private int leftPos;
    private int topPos;
    private int scrollOffset;
    private int lastSyncVersion = -1;

    public WorkspaceManageScreen(Screen parent) {
        super(new TextComponent("工作区管理"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        this.lastSyncVersion = WorkspaceSyncCache.getVersion();
        this.rebuildWidgets();
    }

    private void rebuildWidgets() {
        this.clearWidgets();
        List<WorkspaceSyncPacket.Entry> workspaces = WorkspaceSyncCache.get();
        int maxScroll = Math.max(0, workspaces.size() - VISIBLE_ROWS);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));

        int end = Math.min(workspaces.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            WorkspaceSyncPacket.Entry entry = workspaces.get(i);
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            int buttonY = rowY + (ROW_HEIGHT - 20) / 2;

            this.addRenderableWidget(new Button(this.leftPos + WIDTH - UNBIND_WIDTH - 10, buttonY,
                    UNBIND_WIDTH, 20, new TextComponent("解绑"),
                    b -> ModNetwork.INSTANCE.sendToServer(WorkspaceActionPacket.unbind(entry.id())),
                    (button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack,
                            new TextComponent("解除与分类者的绑定，工作区本身保留"), mouseX, mouseY)));

            this.addRenderableWidget(new Button(
                    this.leftPos + WIDTH - UNBIND_WIDTH - 10 - BUTTON_GAP - RENAME_WIDTH, buttonY,
                    RENAME_WIDTH, 20, new TextComponent("改名"),
                    b -> Minecraft.getInstance().setScreen(new RenameWorkspaceScreen(this, entry)),
                    (button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack,
                            new TextComponent("给工作区起名或改名（可留空回到未命名）"), mouseX, mouseY)));
        }

        int bottomY = this.topPos + HEIGHT - 28;
        this.addRenderableWidget(new Button(this.leftPos + 10, bottomY, 100, 20,
                new TextComponent("新建工作区"),
                b -> Minecraft.getInstance().setScreen(new WorkspaceScreen(this))));
        this.addRenderableWidget(new Button(this.leftPos + WIDTH - 80 - 10, bottomY, 80, 20,
                new TextComponent("返回"), b -> Minecraft.getInstance().setScreen(this.parent)));
    }

    @Override
    public void tick() {
        super.tick();
        if (WorkspaceSyncCache.getVersion() != this.lastSyncVersion) {
            this.lastSyncVersion = WorkspaceSyncCache.getVersion();
            this.rebuildWidgets();
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);

        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);

        this.renderList(poseStack, mouseX, mouseY);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private void renderList(PoseStack poseStack, int mouseX, int mouseY) {
        List<WorkspaceSyncPacket.Entry> workspaces = WorkspaceSyncCache.get();
        int rowX = this.leftPos + LIST_X;
        int rowRight = this.leftPos + WIDTH - LIST_X;

        if (workspaces.isEmpty()) {
            String empty = "还没有工作区，点左下角「新建工作区」";
            this.font.draw(poseStack, empty,
                    this.leftPos + (WIDTH - this.font.width(empty)) / 2.0F,
                    this.topPos + LIST_TOP + 20, ManagerUi.COLOR_TEXT_FAINT);
            return;
        }

        int buttonsLeft = this.leftPos + WIDTH - UNBIND_WIDTH - 10
                - BUTTON_GAP - RENAME_WIDTH - 6;
        int end = Math.min(workspaces.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            WorkspaceSyncPacket.Entry entry = workspaces.get(i);
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            boolean hovered = mouseX >= rowX && mouseX < rowRight && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            GuiComponent.fill(poseStack, rowX, rowY, rowRight, rowY + ROW_HEIGHT,
                    hovered ? ROW_BG_HOVER : ROW_BG);
            if (hovered) {
                GuiComponent.fill(poseStack, rowX, rowY, rowX + ROW_ACCENT_WIDTH, rowY + ROW_HEIGHT,
                        ManagerUi.COLOR_ACCENT);
            }

            int textLeft = rowX + 8;
            int textWidth = buttonsLeft - textLeft - 4;
            // 名字：未命名的用占位文字，颜色更淡，一眼能区分「有名字」和「没起名」
            String name = entry.hasName() ? entry.name() : "未命名工作区";
            int nameColor = entry.hasName() ? ManagerUi.COLOR_TEXT : ManagerUi.COLOR_TEXT_FAINT;
            this.font.draw(poseStack, ManagerUi.ellipsize(this.font, name, textWidth),
                    textLeft, rowY + 5, nameColor);

            String detail = dimensionName(entry.dimension()) + " · " + sizeOf(entry.min(), entry.max())
                    + " · " + (entry.sorterName() == null ? "未绑定分类者" : entry.sorterName());
            this.font.draw(poseStack, ManagerUi.ellipsize(this.font, detail, textWidth),
                    textLeft, rowY + 16,
                    entry.sorterName() == null ? ManagerUi.COLOR_TEXT_FAINT : ManagerUi.COLOR_TEXT_DIM);
        }

        // 内容超出可视区时画一条细滚动条
        if (workspaces.size() > VISIBLE_ROWS) {
            int trackTop = this.topPos + LIST_TOP;
            int trackBottom = trackTop + VISIBLE_ROWS * ROW_SPACING - 4;
            int barX = rowRight - 2;
            GuiComponent.fill(poseStack, barX, trackTop, barX + 2, trackBottom, 0x33000000);
            float ratio = (float) VISIBLE_ROWS / workspaces.size();
            int barHeight = Math.max(8, (int) ((trackBottom - trackTop) * ratio));
            int barTop = trackTop + (int) ((trackBottom - trackTop - barHeight)
                    * ((float) this.scrollOffset / (workspaces.size() - VISIBLE_ROWS)));
            GuiComponent.fill(poseStack, barX, barTop, barX + 2, barTop + barHeight, ManagerUi.COLOR_ACCENT);
        }
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

    private static String sizeOf(BlockPos a, BlockPos b) {
        int x = Math.abs(a.getX() - b.getX()) + 1;
        int y = Math.abs(a.getY() - b.getY()) + 1;
        int z = Math.abs(a.getZ() - b.getZ()) + 1;
        return x + "×" + y + "×" + z;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        List<WorkspaceSyncPacket.Entry> workspaces = WorkspaceSyncCache.get();
        int maxScroll = Math.max(0, workspaces.size() - VISIBLE_ROWS);
        int rowX = this.leftPos + LIST_X;
        int rowRight = this.leftPos + WIDTH - LIST_X;
        int listBottom = this.topPos + LIST_TOP + VISIBLE_ROWS * ROW_SPACING;
        if (maxScroll > 0 && mouseX >= rowX && mouseX < rowRight
                && mouseY >= this.topPos + LIST_TOP && mouseY < listBottom) {
            int next = Math.max(0, Math.min(maxScroll, this.scrollOffset - (int) Math.signum(delta)));
            if (next != this.scrollOffset) {
                this.scrollOffset = next;
                this.rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 被上层界面用来判断是否需要刷新（改名后返回列表时重建一次） */
    @Nullable
    public Screen getParent() {
        return this.parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
