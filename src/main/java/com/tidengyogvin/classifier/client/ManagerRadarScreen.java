package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.CategoryRules;
import com.tidengyogvin.classifier.ManagerData;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.network.ManagerActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 仓储雷达界面：列出当前维度所有已分配存放类型的箱子及其类别，支持滚轮翻行与锁定。
 *
 * - 列表按“常用 → 类别 → 坐标”排序，方便快速找到高频目标箱；
 * - 左侧色块对应类别颜色（与世界中高亮线框一致）；
 * - 选中一行后，管理器中该箱子会以透视方式高亮（隔墙可见）；
 * - [锁定] 后即使右键打开该箱子，高亮也不会被取消。
 */
public class ManagerRadarScreen extends Screen {

    private static final int WIDTH = 260;
    private static final int HEIGHT = 176;

    private static final int LIST_X = 8;
    private static final int LIST_TOP = 56;
    private static final int ROW_HEIGHT = 18;
    private static final int ROW_SPACING = 20;
    private static final int VISIBLE_ROWS = 4;

    private static final int ROW_BG = 0x1AFFFFFF;
    private static final int ROW_BG_HOVER = 0x33FFFFFF;
    private static final int ROW_BG_SELECTED = 0x40E0B15A;
    private static final int ROW_BG_LOCKED = 0x22E07070;

    private final List<ManagerData.RadarEntry> entries = new ArrayList<>();
    private int scrollOffset;
    private int selectedIndex = -1;
    private int lastSyncVersion = -1;

    public ManagerRadarScreen() {
        super(new TextComponent("仓储雷达"));
    }

    public static void open() {
        // 打开时主动请求一次最新快照，避免显示进服前的旧数据
        ModNetwork.INSTANCE.sendToServer(ManagerActionPacket.requestRadar());
        Minecraft.getInstance().setScreen(new ManagerRadarScreen());
    }

    @Override
    protected void init() {
        super.init();
        this.lastSyncVersion = ManagerSyncCache.getVersion();
        this.refreshEntries();
        this.rebuildWidgets();
    }

    private int leftPos() {
        return (this.width - WIDTH) / 2;
    }

    private int topPos() {
        return (this.height - HEIGHT) / 2;
    }

    /** 只显示当前维度的条目，并按常用程度排序 */
    private void refreshEntries() {
        ResourceLocation dimension = Minecraft.getInstance().level == null
                ? null : Minecraft.getInstance().level.dimension().location();
        this.entries.clear();
        for (ManagerData.RadarEntry entry : ManagerSyncCache.getRadar()) {
            if (dimension == null || entry.dimension().equals(dimension)) {
                this.entries.add(entry);
            }
        }
        this.entries.sort(Comparator
                .comparingInt(ManagerData.RadarEntry::priority).reversed()
                .thenComparing(entry -> entry.category() == null ? "" : entry.category())
                .thenComparingInt(entry -> entry.pos().getY())
                .thenComparingInt(entry -> entry.pos().getX())
                .thenComparingInt(entry -> entry.pos().getZ()));
        int maxScroll = Math.max(0, this.entries.size() - VISIBLE_ROWS);
        this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, maxScroll));
        if (this.selectedIndex >= this.entries.size()) {
            this.selectedIndex = this.entries.isEmpty() ? -1 : this.entries.size() - 1;
        }
    }

    private boolean isSelectedLocked() {
        ManagerData.RadarEntry entry = this.selectedEntry();
        return entry != null && entry.locked();
    }

    private ManagerData.RadarEntry selectedEntry() {
        return this.selectedIndex >= 0 && this.selectedIndex < this.entries.size()
                ? this.entries.get(this.selectedIndex) : null;
    }

    private void toggleLock() {
        ManagerData.RadarEntry entry = this.selectedEntry();
        if (entry == null) {
            return;
        }
        ModNetwork.INSTANCE.sendToServer(
                ManagerActionPacket.setRadarLock(!entry.locked(), entry.sorterId(), entry.pos()));
    }

    /** 点击行：选中（并交由渲染层高亮） */
    private void selectRow(int index) {
        if (index < 0 || index >= this.entries.size()) {
            return;
        }
        boolean changed = this.selectedIndex != index;
        this.selectedIndex = index;
        if (changed) {
            // 选中项变化时刷新锁定按钮文字
            this.rebuildWidgets();
        }
        ManagerData.RadarEntry entry = this.entries.get(index);
        RadarHighlight.select(entry.pos());
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(new TextComponent(
                    "§e雷达已选中 " + CategoryRules.displayName(entry.category())
                            + " §7(" + entry.pos().getX() + ", " + entry.pos().getY() + ", "
                            + entry.pos().getZ() + ")"), true);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // 服务端推送新快照后刷新（分类者自动开仓、锁定状态变化都会触发）
        if (ManagerSyncCache.getVersion() != this.lastSyncVersion) {
            this.lastSyncVersion = ManagerSyncCache.getVersion();
            this.refreshEntries();
            // 锁定按钮的文字需要跟着最新状态变化，重建控件
            this.rebuildWidgets();
        }
    }

    /** 只重建底部按钮，不重建列表选中状态 */
    private void rebuildWidgets() {
        this.clearWidgets();
        int leftPos = this.leftPos();
        int topPos = this.topPos();
        int buttonY = topPos + HEIGHT - 30;
        this.addRenderableWidget(new Button(leftPos + 14, buttonY, 70, 20,
                new TextComponent("返回"), b -> Minecraft.getInstance().setScreen(new ManagerScreen())));
        this.addRenderableWidget(new Button(leftPos + WIDTH - 14 - 70, buttonY, 70, 20,
                new TextComponent("关闭"), b -> this.onClose()));
        int centerX = leftPos + WIDTH / 2;
        this.addRenderableWidget(new Button(centerX - 38, buttonY, 76, 20,
                new TextComponent(this.isSelectedLocked() ? "解除锁定" : "锁定"),
                b -> this.toggleLock()));
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        int leftPos = this.leftPos();
        int topPos = this.topPos();
        ManagerUi.renderFrame(poseStack, leftPos, topPos, WIDTH, HEIGHT);

        int headerTextY = topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);

        String count = this.entries.size() + " 个箱子";
        this.font.draw(poseStack, count,
                leftPos + WIDTH - 12 - this.font.width(count), headerTextY, ManagerUi.COLOR_TEXT_FAINT);

        this.renderList(poseStack, mouseX, mouseY);
        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    private void renderList(PoseStack poseStack, int mouseX, int mouseY) {
        int leftPos = this.leftPos();
        int topPos = this.topPos();
        int rowX = leftPos + LIST_X;
        int rowRight = leftPos + WIDTH - LIST_X;

        if (this.entries.isEmpty()) {
            String empty = "当前维度还没有已分配类型的箱子";
            this.font.draw(poseStack, empty,
                    leftPos + (WIDTH - this.font.width(empty)) / 2.0F, topPos + LIST_TOP + 16,
                    ManagerUi.COLOR_TEXT_FAINT);
            String hint = "分类者首次把物品放入空箱后会自动建仓";
            this.font.draw(poseStack, hint,
                    leftPos + (WIDTH - this.font.width(hint)) / 2.0F, topPos + LIST_TOP + 30,
                    ManagerUi.COLOR_TEXT_FAINT);
            return;
        }

        int end = Math.min(this.entries.size(), this.scrollOffset + VISIBLE_ROWS);
        for (int i = this.scrollOffset; i < end; i++) {
            ManagerData.RadarEntry entry = this.entries.get(i);
            int rowY = topPos + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
            boolean hovered = mouseX >= rowX && mouseX < rowRight && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            int background = i == this.selectedIndex ? ROW_BG_SELECTED
                    : entry.locked() ? ROW_BG_LOCKED
                    : hovered ? ROW_BG_HOVER : ROW_BG;
            GuiComponent.fill(poseStack, rowX, rowY, rowRight, rowY + ROW_HEIGHT, background);

            // 类别色块
            float[] color = CategoryRules.color(entry.category());
            int colorInt = 0xFF000000
                    | ((int) (color[0] * 255) << 16)
                    | ((int) (color[1] * 255) << 8)
                    | (int) (color[2] * 255);
            GuiComponent.fill(poseStack, rowX + 4, rowY + 4, rowX + 8, rowY + ROW_HEIGHT - 4, colorInt);

            // 类别名 + 使用次数（category 为 null 的是分类箱自身的物品来源）
            String label = entry.category() == null ? "§6物品来源箱" : CategoryRules.displayName(entry.category());
            if (entry.priority() > 0) {
                label += " §7×" + entry.priority();
            }
            if (entry.locked()) {
                label += " §c[锁]";
            }
            this.font.draw(poseStack, label, rowX + 12, rowY + 5, ManagerUi.COLOR_TEXT);

            // 坐标右对齐
            String coords = entry.pos().getX() + "," + entry.pos().getY() + "," + entry.pos().getZ();
            this.font.draw(poseStack, coords,
                    rowRight - 6 - this.font.width(coords), rowY + 5, ManagerUi.COLOR_TEXT_DIM);
        }

        // 滚动条
        if (this.entries.size() > VISIBLE_ROWS) {
            int trackTop = topPos + LIST_TOP;
            int trackBottom = trackTop + VISIBLE_ROWS * ROW_SPACING - 2;
            int barX = rowRight - 2;
            GuiComponent.fill(poseStack, barX, trackTop, barX + 2, trackBottom, 0x33000000);
            float ratio = (float) VISIBLE_ROWS / this.entries.size();
            int barHeight = Math.max(8, (int) ((trackBottom - trackTop) * ratio));
            int barTop = trackTop + (int) ((trackBottom - trackTop - barHeight)
                    * ((float) this.scrollOffset / (this.entries.size() - VISIBLE_ROWS)));
            GuiComponent.fill(poseStack, barX, barTop, barX + 2, barTop + barHeight, ManagerUi.COLOR_ACCENT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int rowX = this.leftPos() + LIST_X;
            int rowRight = this.leftPos() + WIDTH - LIST_X;
            int end = Math.min(this.entries.size(), this.scrollOffset + VISIBLE_ROWS);
            for (int i = this.scrollOffset; i < end; i++) {
                int rowY = this.topPos() + LIST_TOP + (i - this.scrollOffset) * ROW_SPACING;
                if (mouseX >= rowX && mouseX < rowRight && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    this.selectRow(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, this.entries.size() - VISIBLE_ROWS);
        if (maxScroll > 0) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll,
                    this.scrollOffset - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        // 关闭界面时保留选中高亮：玩家需要回到世界里看到透视目标；
        // 高亮会在右键打开未锁定的箱子、或退出服务器时自动取消。
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
