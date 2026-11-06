package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.SorterMenu;
import com.tidengyogvin.classifier.network.SelectSkinPacket;
import com.tidengyogvin.classifier.network.ToggleWorkingPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类者的设置界面：选择皮肤玩家、改名、切换工作状态。
 * 沿用管理器界面的视觉规范：原版暗化背景 + 深灰面板 + 1px 边框。
 */
public class SorterScreen extends AbstractContainerScreen<SorterMenu> {

    private static final int LIST_X = 20;
    private static final int LIST_TOP = 60;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_SPACING = 22;
    /** 皮肤列表按钮最多 4 行，再多就会与底部操作按钮挤在一起 */
    private static final int MAX_BUTTONS = 4;

    private static final int FOOTER_Y = 172;
    private static final int FOOTER_BUTTON_WIDTH = 76;
    private static final int FOOTER_BUTTON_HEIGHT = 20;

    private Button toggleButton;

    public SorterScreen(SorterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 200;
        this.imageHeight = 200;
    }

    @Override
    protected void init() {
        super.init();

        // 在线玩家按钮（选择皮肤）：宽度固定 160，左右留出 20 的呼吸空间
        List<String> names = getOnlinePlayerNames();
        int count = Math.min(names.size(), MAX_BUTTONS);
        for (int i = 0; i < count; i++) {
            final String name = names.get(i);
            int x = this.leftPos + LIST_X;
            int y = this.topPos + LIST_TOP + i * ROW_SPACING;
            this.addRenderableWidget(new Button(x, y, 160, ROW_HEIGHT, new TextComponent(name), b -> {
                ModNetwork.INSTANCE.sendToServer(new SelectSkinPacket(this.menu.getEntityId(), name));
            }, (button, poseStack, mouseX, mouseY) ->
                    this.renderTooltip(poseStack, new TextComponent("使用 " + name + " 的皮肤"), mouseX, mouseY)));
        }

        // 底部并排：工作表 / 改名
        this.toggleButton = new Button(this.leftPos + 20, this.topPos + FOOTER_Y,
                FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT, toggleButtonText(), b -> {
            // 客户端只发包，不修改本地实体状态
            ModNetwork.INSTANCE.sendToServer(new ToggleWorkingPacket(this.menu.getEntityId()));
        });
        this.addRenderableWidget(this.toggleButton);

        this.addRenderableWidget(new Button(this.leftPos + 104, this.topPos + FOOTER_Y,
                FOOTER_BUTTON_WIDTH, FOOTER_BUTTON_HEIGHT, new TextComponent("改名"), b -> {
            Minecraft.getInstance().setScreen(new SorterRenameScreen(this.menu));
        }));
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (this.toggleButton != null) {
            this.toggleButton.setMessage(toggleButtonText());
        }
    }

    /** 按钮文字表达“点击后会发生什么”，当前状态显示在标题栏下方 */
    private Component toggleButtonText() {
        SorterEntity sorter = getSorterEntity();
        return new TextComponent(sorter != null && sorter.isWorking() ? "§e转为休闲" : "§a转为工作");
    }

    @Nullable
    private SorterEntity getSorterEntity() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        Entity entity = this.minecraft.level.getEntity(this.menu.getEntityId());
        return entity instanceof SorterEntity ? (SorterEntity) entity : null;
    }

    private List<String> getOnlinePlayerNames() {
        List<String> result = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                result.add(info.getProfile().getName());
            }
        }
        return result;
    }

    @Override
    public void renderBackground(PoseStack poseStack, int vOffset) {
        // 1.18.2 的原版暗化背景；容器界面本身不会调用这里，实际入口在 renderBg
        super.renderBackground(poseStack, vOffset);
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        // 每次渲染只画一次暗化背景，再叠加自定义面板，避免出现两层渐变
        this.renderBackground(poseStack, 0);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);

        // 皮肤列表区的行底色，给每行一个轻量容器感
        int count = Math.min(getOnlinePlayerNames().size(), MAX_BUTTONS);
        for (int i = 0; i < count; i++) {
            int rowY = this.topPos + LIST_TOP + i * ROW_SPACING;
            fill(poseStack, this.leftPos + 14, rowY, this.leftPos + 186, rowY + ROW_HEIGHT, 0x14FFFFFF);
        }

        // 底部操作区上方的分隔线，拉开列表与按钮的层次
        fill(poseStack, this.leftPos + 16, this.topPos + FOOTER_Y - 10,
                this.leftPos + this.imageWidth - 16, this.topPos + FOOTER_Y - 9, 0x22FFFFFF);
    }

    @Override
    protected void renderLabels(PoseStack poseStack, int mouseX, int mouseY) {
        // 标题在 22px 标题栏内居中
        int titleX = (this.imageWidth - this.font.width(this.title)) / 2;
        int titleY = (ManagerUi.HEADER_HEIGHT - 8) / 2;
        this.font.draw(poseStack, this.title, titleX, titleY, ManagerUi.COLOR_TEXT);

        // 当前状态
        this.font.draw(poseStack, statusText(), 14, 27, ManagerUi.COLOR_TEXT);

        // 分类箱绑定状态
        SorterEntity sorter = getSorterEntity();
        BlockPos boxPos = sorter == null ? null : sorter.getSorterBoxPos();
        Component boxText;
        int boxColor;
        if (boxPos == null) {
            boxText = new TextComponent("未绑定分类箱（Shift+右键箱子）");
            boxColor = ManagerUi.COLOR_BAD;
        } else if (!isContainerBlock(boxPos)) {
            // 方块被拆掉或换成了非容器：旧坐标不能继续显示，否则玩家会误以为仍然绑定着
            boxText = new TextComponent("§c分类箱已被破坏");
            boxColor = ManagerUi.COLOR_BAD;
        } else {
            boxText = new TextComponent("分类箱 " + formatPos(boxPos));
            boxColor = ManagerUi.COLOR_TEXT_DIM;
        }
        this.font.draw(poseStack, boxText, 14, 39, boxColor);

        // 皮肤列表标题与人数
        List<String> names = getOnlinePlayerNames();
        this.font.draw(poseStack, "选择皮肤", LIST_X - 6, LIST_TOP - 10, ManagerUi.COLOR_TEXT_DIM);
        String countText = names.size() + " 位在线";
        this.font.draw(poseStack, countText,
                this.imageWidth - LIST_X + 6 - this.font.width(countText), LIST_TOP - 10, ManagerUi.COLOR_TEXT_FAINT);
        if (names.isEmpty()) {
            this.font.draw(poseStack, "当前没有其他在线玩家", LIST_X, LIST_TOP + 6, ManagerUi.COLOR_TEXT_FAINT);
        } else if (names.size() > MAX_BUTTONS) {
            String more = "还有 " + (names.size() - MAX_BUTTONS) + " 位未显示";
            this.font.draw(poseStack, more, LIST_X, LIST_TOP + MAX_BUTTONS * ROW_SPACING + 2,
                    ManagerUi.COLOR_TEXT_FAINT);
        }
    }

    private Component statusText() {
        SorterEntity sorter = getSorterEntity();
        if (sorter == null) {
            return new TextComponent("§8状态：加载中");
        }
        if (sorter.isWorking()) {
            return new TextComponent("§a状态：工作中");
        }
        if (sorter.isManualPause()) {
            return new TextComponent("§c状态：休闲中（已锁定）");
        }
        return new TextComponent("§7状态：休闲中");
    }

    /** 该坐标当前是否仍是合法容器：已被挖掉或换成了别的方块都算失效 */
    private boolean isContainerBlock(BlockPos pos) {
        if (this.minecraft == null || this.minecraft.level == null || !this.minecraft.level.hasChunkAt(pos)) {
            // 区块未加载时无法判定，按“仍然有效”处理，避免走远就误报已被破坏
            return true;
        }
        var block = this.minecraft.level.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
