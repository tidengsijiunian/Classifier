package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.network.ManagerActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类者管理器主界面：展示全部分类者，并提供传送 / 工作切换 / 解绑操作。
 * 数据来自服务端推送的 {@link ManagerSyncCache}，操作后服务端会再推一份刷新。
 *
 * 界面信息分三层：
 * 1. 顶部汇总条——总数、已绑、半绑、空闲一眼看全；
 * 2. 每行左侧状态灯——灰=未绑、琥珀=只绑一半、绿=箱子和工作区都绑好、红=刚被拒绝；
 * 3. 悬停详情——分类箱坐标、工作区两角与尺寸、冲突占用者。
 */
public class ManagerScreen extends Screen {

    private static final int WIDTH = 272;
    private static final int HEIGHT = 236;

    private static final int LIST_X = 10;
    private static final int LIST_TOP = 72;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_SPACING = 27;
    private static final int VISIBLE_ROWS = 4;

    /** 顶部汇总条的位置与高度 */
    private static final int SUMMARY_Y = 26;
    private static final int SUMMARY_HEIGHT = 14;
    /** 汇总条内各分组之间的间距 */
    private static final int SUMMARY_GAP = 10;

    private static final int ROW_BG = 0x14FFFFFF;
    private static final int ROW_BG_HOVER = 0x40FFFFFF;
    /** 悬停时行左侧的强调色竖条宽度 */
    private static final int ROW_ACCENT_WIDTH = 3;

    /** 状态灯外圈尺寸；内圈是实际颜色，外圈做一点暗边让灯看起来是嵌进去的 */
    private static final int DOT_SIZE = 6;
    private static final int DOT_CORE = 4;

    private static final int COLOR_ROW_SEPARATOR = 0x1FFFFFFF;
    private static final int COLOR_SUMMARY_BG = 0x33000000;
    private static final int COLOR_REJECTION = 0xE07070;

    private int leftPos;
    private int topPos;
    private int scrollOffset;
    private int lastSyncVersion = -1;

    public ManagerScreen() {
        super(new TextComponent("分类者管理器"));
    }

    /**
     * 右键空气时由物品调用，只应在客户端执行。
     */
    public static void open() {
        Minecraft.getInstance().setScreen(new ManagerScreen());
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        this.lastSyncVersion = ManagerSyncCache.getVersion();
        this.rebuildWidgets();
    }

    /** 按当前数据与滚动位置重建全部控件 */
    private void rebuildWidgets() {
        this.clearWidgets();

        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int maxScroll = Math.max(0, sorters.size() - VISIBLE_ROWS);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));

        // 主操作：工作区二级菜单 / 创建分类者，两个按钮并排
        int mainButtonWidth = 78;
        int mainButtonGap = 4;
        int mainStartX = this.leftPos + (WIDTH - mainButtonWidth * 2 - mainButtonGap) / 2;
        this.addRenderableWidget(new Button(mainStartX, this.topPos + 44,
                mainButtonWidth, 20, new TextComponent("工作区"), b -> this.openWorkspaceMenu(),
                (button, poseStack, mouseX, mouseY) ->
                        this.renderTooltip(poseStack, new TextComponent("划定工作区 / 工作区管理"),
                                mouseX, mouseY)));
        this.addRenderableWidget(new Button(mainStartX + mainButtonWidth + mainButtonGap, this.topPos + 44,
                mainButtonWidth, 20, new TextComponent("创建分类者"), b -> this.openCreateSorter(),
                (button, poseStack, mouseX, mouseY) ->
                        this.renderTooltip(poseStack, new TextComponent("在面前生成一台分类者，可起名"),
                                mouseX, mouseY)));
        // 仓储雷达
        this.addRenderableWidget(new Button(this.leftPos + (WIDTH - mainButtonWidth) / 2, this.topPos + 183,
                mainButtonWidth, 20, new TextComponent("仓储雷达"), b -> ManagerRadarScreen.open(),
                (button, poseStack, mouseX, mouseY) ->
                        this.renderTooltip(poseStack, new TextComponent("查看已分配类型的箱子，可透视高亮并锁定"),
                                mouseX, mouseY)));
        // 关闭
        this.addRenderableWidget(new Button(this.leftPos + (WIDTH - 90) / 2, this.topPos + HEIGHT - 28,
                90, 20, new TextComponent("关闭"), b -> this.onClose()));

        // 列表行内的操作按钮：右侧三个小按钮，左侧留出名字与状态灯
        int end = Math.min(sorters.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            ManagerData.SorterInfo info = sorters.get(i);
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            int rowCenterY = rowY + (ROW_HEIGHT - 20) / 2;

            this.addRenderableWidget(new Button(actionColumn(), rowCenterY, 34, 20,
                    new TextComponent("传送"),
                    b -> ModNetwork.INSTANCE.sendToServer(ManagerActionPacket.teleport(info.id())),
                    (button, poseStack, mouseX, mouseY) ->
                            this.renderTooltip(poseStack, new TextComponent("传送到 " + info.name()), mouseX, mouseY)));

            String toggleLabel = info.working() ? "暂停" : "工作";
            this.addRenderableWidget(new Button(actionColumn() + 38, rowCenterY, 34, 20,
                    new TextComponent(toggleLabel),
                    b -> ModNetwork.INSTANCE.sendToServer(ManagerActionPacket.toggleWorking(info.id())),
                    (button, poseStack, mouseX, mouseY) ->
                            this.renderTooltip(poseStack, new TextComponent(statusText(info)), mouseX, mouseY)));

            this.addRenderableWidget(new Button(actionColumn() + 76, rowCenterY, 34, 20,
                    new TextComponent("解绑"),
                    b -> ModNetwork.INSTANCE.sendToServer(ManagerActionPacket.unbind(info.id())),
                    (button, poseStack, mouseX, mouseY) ->
                            this.renderTooltip(poseStack, new TextComponent("解除分类箱绑定"), mouseX, mouseY)));
        }
    }

    /** 行内第一个小按钮的 x；三个按钮依次占 34px，间距 4px，右边距 10px */
    private int actionColumn() {
        return this.leftPos + WIDTH - 10 - 34 * 3 - 4 * 2;
    }

    private void openWorkspaceMenu() {
        Minecraft.getInstance().setScreen(new WorkspaceMenuScreen());
    }

    private void openCreateSorter() {
        Minecraft.getInstance().setScreen(new CreateSorterScreen());
    }

    @Override
    public void tick() {
        super.tick();
        // 服务端推送了新快照时刷新列表
        if (ManagerSyncCache.getVersion() != this.lastSyncVersion) {
            this.lastSyncVersion = ManagerSyncCache.getVersion();
            this.rebuildWidgets();
        }
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        // 原版暗化背景，保证在不同维度下都有沉浸感
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);
        this.renderHeader(poseStack);
        this.renderSummary(poseStack);
        this.renderList(poseStack, mouseX, mouseY);
        super.render(poseStack, mouseX, mouseY, partialTick);
        this.renderListTooltip(poseStack, mouseX, mouseY);
    }

    private void renderHeader(PoseStack poseStack) {
        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);
    }

    /**
     * 顶部汇总条：分类者总数 / 已绑 / 半绑 / 空闲。
     * 空闲数量为 0 时省略“半绑”，避免一条全是数字的横条显得拥挤。
     */
    private void renderSummary(PoseStack poseStack) {
        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int total = sorters.size();
        int bound = 0;
        int half = 0;
        for (ManagerData.SorterInfo info : sorters) {
            if (info.fullyBound()) {
                bound++;
            } else if (info.hasSorterBox() || info.hasWorkspace()) {
                half++;
            }
        }
        int idle = total - bound - half;

        int left = this.leftPos + LIST_X;
        int right = this.leftPos + WIDTH - LIST_X;
        int top = this.topPos + SUMMARY_Y;
        GuiComponent.fill(poseStack, left, top, right, top + SUMMARY_HEIGHT, COLOR_SUMMARY_BG);
        GuiComponent.fill(poseStack, left, top, right, top + 1, COLOR_ROW_SEPARATOR);
        GuiComponent.fill(poseStack, left, top + SUMMARY_HEIGHT - 1, right, top + SUMMARY_HEIGHT,
                COLOR_ROW_SEPARATOR);

        List<String> texts = new ArrayList<>(4);
        List<Integer> colors = new ArrayList<>(4);
        texts.add("分类者 " + total);
        colors.add(ManagerUi.COLOR_TEXT_DIM);
        texts.add("已绑 " + bound);
        colors.add(ManagerUi.COLOR_GOOD);
        if (half > 0) {
            texts.add("半绑 " + half);
            colors.add(ManagerUi.COLOR_WARN);
        }
        texts.add("空闲 " + idle);
        colors.add(ManagerUi.COLOR_TEXT_FAINT);

        int contentWidth = 0;
        for (int i = 0; i < texts.size(); i++) {
            contentWidth += this.font.width(texts.get(i));
            if (i > 0) {
                contentWidth += SUMMARY_GAP;
            }
        }
        int textY = top + (SUMMARY_HEIGHT - 8) / 2;
        int x = left + (WIDTH - 2 * LIST_X - contentWidth) / 2;
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                int dotX = x + (SUMMARY_GAP - this.font.width("·")) / 2;
                this.font.draw(poseStack, "·", dotX, textY, ManagerUi.COLOR_TEXT_FAINT);
                x += SUMMARY_GAP;
            }
            this.font.draw(poseStack, texts.get(i), x, textY, colors.get(i));
            x += this.font.width(texts.get(i));
        }
    }

    /** 绘制每一行的底色、左侧强调竖条、状态灯与名字（按钮由 super.render 负责） */
    private void renderList(PoseStack poseStack, int mouseX, int mouseY) {
        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int rowX = this.leftPos + LIST_X;
        int rowRight = this.leftPos + WIDTH - LIST_X;

        if (sorters.isEmpty()) {
            Component empty = new TextComponent("暂无分类者");
            this.font.draw(poseStack, empty,
                    this.leftPos + (WIDTH - this.font.width(empty)) / 2.0F,
                    this.topPos + LIST_TOP + 16, ManagerUi.COLOR_TEXT_FAINT);
            return;
        }

        int end = Math.min(sorters.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            ManagerData.SorterInfo info = sorters.get(i);
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            boolean hovered = mouseX >= rowX && mouseX < rowRight && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            // 悬停时整行压一圈浅白，比常态亮 27%，鼠标一动就能看出来
            GuiComponent.fill(poseStack, rowX, rowY, rowRight, rowY + ROW_HEIGHT,
                    hovered ? ROW_BG_HOVER : ROW_BG);

            // 刚被拒绝的行：红色脉冲闪一下，4 秒后自然消失
            this.renderRejectionFlash(poseStack, info, rowX, rowY, rowRight);

            // 悬停时左侧亮起一条强调色竖条（反馈在鼠标移入的当帧就出现）
            if (hovered) {
                GuiComponent.fill(poseStack, rowX, rowY, rowX + ROW_ACCENT_WIDTH, rowY + ROW_HEIGHT,
                        ManagerUi.COLOR_ACCENT);
            }

            // 状态灯：灰=未绑、琥珀=只绑一半、绿=箱子和工作区都绑好、红=刚被拒绝
            int dotY = rowY + (ROW_HEIGHT - DOT_SIZE) / 2;
            int dotX = rowX + 7;
            GuiComponent.fill(poseStack, dotX, dotY, dotX + DOT_SIZE, dotY + DOT_SIZE, 0x66000000);
            GuiComponent.fill(poseStack, dotX + 1, dotY + 1,
                    dotX + 1 + DOT_CORE, dotY + 1 + DOT_CORE, statusColor(info));

            // 名字在可用区域内垂直居中，超长截断
            int nameLeft = dotX + DOT_SIZE + 6;
            int nameWidth = actionColumn() - nameLeft - 6;
            String name = ManagerUi.ellipsize(this.font, info.name(), nameWidth);
            this.font.draw(poseStack, name, nameLeft, rowY + (ROW_HEIGHT - 8) / 2.0F, ManagerUi.COLOR_TEXT);
        }

        // 内容超出可视区时，右侧画一条细滚动条
        if (sorters.size() > VISIBLE_ROWS) {
            int trackTop = this.topPos + LIST_TOP;
            int trackBottom = trackTop + VISIBLE_ROWS * ROW_SPACING - 4;
            int barX = rowRight - 2;
            GuiComponent.fill(poseStack, barX, trackTop, barX + 2, trackBottom, 0x33000000);
            float ratio = (float) VISIBLE_ROWS / sorters.size();
            int barHeight = Math.max(8, (int) ((trackBottom - trackTop) * ratio));
            int barTop = trackTop + (int) ((trackBottom - trackTop - barHeight)
                    * ((float) this.scrollOffset / (sorters.size() - VISIBLE_ROWS)));
            GuiComponent.fill(poseStack, barX, barTop, barX + 2, barTop + barHeight, ManagerUi.COLOR_ACCENT);
        }
    }

    /**
     * 被拒行的红色脉冲。用 1.2 秒一个周期的正弦波控制透明度，
     * 最后一秒线性淡出，既不刺眼又能让人一眼看到是哪一行没绑上。
     */
    private void renderRejectionFlash(PoseStack poseStack, ManagerData.SorterInfo info,
                                      int rowX, int rowY, int rowRight) {
        long remaining = ManagerRejectCache.remainingMillis(info.id());
        if (remaining <= 0) {
            return;
        }
        float fade = Math.min(1.0F, remaining / 600.0F);
        float pulse = 0.35F + 0.25F * (float) Math.sin(
                (System.currentTimeMillis() % 1200L) / 1200.0D * Math.PI * 2.0D);
        int alpha = (int) (pulse * fade * 255.0F) & 0xFF;
        GuiComponent.fill(poseStack, rowX, rowY, rowRight, rowY + ROW_HEIGHT,
                (alpha << 24) | COLOR_REJECTION);
        ManagerUi.outline(poseStack, rowX, rowY, rowRight, rowY + ROW_HEIGHT,
                (Math.min(255, alpha * 2) << 24) | COLOR_REJECTION);
    }

    /** 悬停在行信息区域时显示完整详情 */
    private void renderListTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        List<ManagerData.SorterInfo> sorters = ManagerSyncCache.get();
        int rowX = this.leftPos + LIST_X;
        int listRight = this.leftPos + WIDTH - LIST_X;
        // 按钮区域的提示由按钮自身的 onTooltip 负责，这里只处理左边的信息区
        int nameRight = this.actionColumn() - 4;
        int end = Math.min(sorters.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            int rowY = this.topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            if (mouseX < rowX || mouseX >= Math.min(nameRight, listRight) || mouseY < rowY || mouseY >= rowY + ROW_HEIGHT) {
                continue;
            }
            this.renderComponentTooltip(poseStack, this.detailLines(sorters, sorters.get(i)), mouseX, mouseY);
            return;
        }
    }

    /** 悬停详情：绑定状态、分类箱、工作区尺寸，以及冲突 / 被拒时点名占用者 */
    private List<Component> detailLines(List<ManagerData.SorterInfo> all, ManagerData.SorterInfo info) {
        List<Component> lines = new ArrayList<>(8);
        lines.add(new TextComponent(info.name()));
        lines.add(new TextComponent("§7坐标: " + formatPos(info.pos())));
        lines.add(new TextComponent("§7维度: " + dimensionName(info.dimension())));
        lines.add(new TextComponent(statusText(info)));

        String occupant = ManagerRejectCache.occupantName(info.id());
        if (occupant != null) {
            lines.add(new TextComponent("§c本次绑定被拒绝: 目标已被 " + occupant + " 占用"));
        }

        if (info.sorterBox() != null) {
            lines.add(new TextComponent("§7分类箱: §f" + formatPos(info.sorterBox())));
        } else {
            lines.add(new TextComponent("§7分类箱: §8未绑定"));
        }

        if (info.workspaceMin() != null && info.workspaceMax() != null) {
            BlockPos min = normalizeMin(info.workspaceMin(), info.workspaceMax());
            BlockPos max = normalizeMax(info.workspaceMin(), info.workspaceMax());
            lines.add(new TextComponent("§7工作区: §f" + formatPos(min) + " §7→ §f" + formatPos(max)));
            lines.add(new TextComponent("§7范围: §f" + sizeOf(min, max)));
        } else {
            lines.add(new TextComponent("§7工作区: §8未绑定"));
        }

        // 旧存档可能留下重复绑定；界面上明确指出和谁撞了，方便手动解绑
        ManagerData.SorterInfo boxConflict = findConflict(all, info, true);
        if (boxConflict != null) {
            lines.add(new TextComponent("§6分类箱与「" + boxConflict.name() + "」重复（旧存档）"));
        }
        ManagerData.SorterInfo workspaceConflict = findConflict(all, info, false);
        if (workspaceConflict != null) {
            lines.add(new TextComponent("§6工作区与「" + workspaceConflict.name() + "」重复（旧存档）"));
        }
        return lines;
    }

    /** 在同一维度的其它分类者里找定位相同的目标；workspace=false 时比较归一化后的工作区角点 */
    @Nullable
    private static ManagerData.SorterInfo findConflict(List<ManagerData.SorterInfo> all,
                                                       ManagerData.SorterInfo self, boolean box) {
        if (box ? self.sorterBox() == null : (self.workspaceMin() == null || self.workspaceMax() == null)) {
            return null;
        }
        for (ManagerData.SorterInfo other : all) {
            if (other.id().equals(self.id()) || !other.dimension().equals(self.dimension())) {
                continue;
            }
            if (box) {
                if (self.sorterBox().equals(other.sorterBox())) {
                    return other;
                }
            } else if (other.workspaceMin() != null && other.workspaceMax() != null
                    && normalizeMin(self.workspaceMin(), self.workspaceMax())
                    .equals(normalizeMin(other.workspaceMin(), other.workspaceMax()))
                    && normalizeMax(self.workspaceMin(), self.workspaceMax())
                    .equals(normalizeMax(other.workspaceMin(), other.workspaceMax()))) {
                return other;
            }
        }
        return null;
    }

    private static BlockPos normalizeMin(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos normalizeMax(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }

    private static String sizeOf(BlockPos min, BlockPos max) {
        return (max.getX() - min.getX() + 1) + "×"
                + (max.getY() - min.getY() + 1) + "×"
                + (max.getZ() - min.getZ() + 1);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String statusText(ManagerData.SorterInfo info) {
        if (info.locked()) {
            return "§c状态: 休闲中 (已锁定)";
        }
        return info.working() ? "§a状态: 工作中" : "§7状态: 休闲中";
    }

    /** 状态灯颜色：拒绝 > 全绑 > 半绑 > 未绑 */
    private static int statusColor(ManagerData.SorterInfo info) {
        if (ManagerRejectCache.isRejected(info.id())) {
            return ManagerUi.COLOR_BAD;
        }
        if (info.fullyBound()) {
            return ManagerUi.COLOR_GOOD;
        }
        if (info.hasSorterBox() || info.hasWorkspace()) {
            return ManagerUi.COLOR_WARN;
        }
        return ManagerUi.COLOR_TEXT_FAINT;
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
