package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.tidengyogvin.classifier.ClassifierManagerItem;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.WorkspaceTable;
import com.tidengyogvin.classifier.network.ManagerActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 工作区划定子界面：两行坐标输入（点 A / 点 B），一个可选的工作区名字，
 * 以及「绑定到哪台分类者」的选择器（默认最近的一台）。
 *
 * 保存时把坐标 + 目标分类者 + 名字一起发给服务端；工作区对象由服务端创建或原地更新。
 * 打开时从手持管理器的 NBT 读取缓存值，关闭界面不丢已填坐标，可分两次完成。
 */
public class WorkspaceScreen extends Screen {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 236;

    private static final int NAME_Y = 44;
    private static final int FIELD_Y_A = 76;
    private static final int FIELD_Y_B = 114;
    private static final int SORTER_Y = 142;
    private static final int BUTTON_Y = 170;
    /** 输入框列起点相对面板左边距 */
    private static final int FIELD_X = 36;
    private static final int FIELD_WIDTH = 32;
    private static final int FIELD_HEIGHT = 18;
    private static final int FIELD_GAP = 4;

    private static final int FILL_WIDTH = 52;
    private static final int CLEAR_WIDTH = 24;
    private static final int BUTTON_GAP = 4;
    private static final int CLEAR_X = WIDTH - 14 - CLEAR_WIDTH;
    private static final int FILL_X = CLEAR_X - BUTTON_GAP - FILL_WIDTH;

    /** 输入框允许的坐标范围，与命令中的世界边界保持一致 */
    private static final int MIN_COORD = -30_000_000;
    private static final int MAX_COORD = 30_000_000;

    /**
     * 跨界面临时缓存：界面关闭时把已填好的坐标留在客户端内存里，
     * 下次打开时 NBT 里没有对应端点就回退到这里，方便分两次填 A / B。
     */
    @Nullable
    private static BlockPos tempPosA;
    @Nullable
    private static BlockPos tempPosB;
    /** 保存成功后下一次打开跳过 NBT 回填，让输入框从空白开始 */
    private static boolean skipNbtRestoreOnce = false;
    /** 保存成功跳转主界面时，removed() 不要把已提交的坐标重新写成草稿 */
    private static boolean skipDraftOnRemoved = false;
    /** 选择器选中的分类者；null 表示「用最近的一台」 */
    @Nullable
    private static UUID pendingSorterId;
    @Nullable
    private static String pendingSorterName;

    private final Screen parent;
    private int leftPos;
    private int topPos;

    private EditBox nameBox;
    private EditBox ax;
    private EditBox ay;
    private EditBox az;
    private EditBox bx;
    private EditBox by;
    private EditBox bz;

    @Nullable
    private String errorText;

    public WorkspaceScreen(Screen parent) {
        super(new TextComponent("划定工作区"));
        this.parent = parent;
    }

    /** 选择器回填：由 {@link SorterPickerScreen} 调用 */
    public static void setPendingTarget(@Nullable UUID sorterId, @Nullable String sorterName) {
        pendingSorterId = sorterId;
        pendingSorterName = sorterName;
    }

    @Override
    protected void init() {
        super.init();
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        this.errorText = null;

        ItemStack stack = this.heldManager();
        BlockPos pos1;
        BlockPos pos2;
        if (skipNbtRestoreOnce) {
            // 刚保存成功：草稿已清空，不再回填旧 NBT 坐标
            skipNbtRestoreOnce = false;
            pos1 = null;
            pos2 = null;
        } else {
            boolean hasNbt1 = hasCorner(stack, ClassifierManagerItem.TAG_POS1);
            boolean hasNbt2 = hasCorner(stack, ClassifierManagerItem.TAG_POS2);
            pos1 = hasNbt1 ? ClassifierManagerItem.getWorkspaceCorner(stack, ClassifierManagerItem.TAG_POS1) : tempPosA;
            pos2 = hasNbt2 ? ClassifierManagerItem.getWorkspaceCorner(stack, ClassifierManagerItem.TAG_POS2) : tempPosB;
            // NBT 里已有的端点同步进草稿，界面关闭后也能继续沿用
            if (hasNbt1) {
                tempPosA = pos1;
            }
            if (hasNbt2) {
                tempPosB = pos2;
            }
        }

        // 名称是可选的备注，留空即未命名
        this.nameBox = new EditBox(this.font, this.leftPos + 50, this.topPos + NAME_Y, WIDTH - 64, 18,
                new TextComponent("工作区名称"));
        this.nameBox.setMaxLength(WorkspaceTable.MAX_WORKSPACE_NAME_LENGTH);
        this.nameBox.setFilter(text -> !text.startsWith("§"));
        this.addRenderableWidget(this.nameBox);

        // 缓存逻辑：NBT 优先（已保存过的坐标），没有则回退到临时草稿
        this.ax = this.addField(0, fieldY(this.topPos, FIELD_Y_A), pos1, 0);
        this.ay = this.addField(1, fieldY(this.topPos, FIELD_Y_A), pos1, 1);
        this.az = this.addField(2, fieldY(this.topPos, FIELD_Y_A), pos1, 2);
        this.addRowButtons(FIELD_Y_A, this.ax, this.ay, this.az, true);

        this.bx = this.addField(0, fieldY(this.topPos, FIELD_Y_B), pos2, 0);
        this.by = this.addField(1, fieldY(this.topPos, FIELD_Y_B), pos2, 1);
        this.bz = this.addField(2, fieldY(this.topPos, FIELD_Y_B), pos2, 2);
        this.addRowButtons(FIELD_Y_B, this.bx, this.by, this.bz, false);

        // 绑定目标：默认最近的一台，点开选择器可以指定别的分类者
        this.addRenderableWidget(new Button(this.leftPos + 60, this.topPos + SORTER_Y, WIDTH - 74, 20,
                new TextComponent(targetLabel()), b -> Minecraft.getInstance().setScreen(
                        new SorterPickerScreen(this)),
                (button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack,
                        new TextComponent("选择这片工作区归属哪台分类者"), mouseX, mouseY)));

        this.addRenderableWidget(new Button(this.leftPos + 46, this.topPos + BUTTON_Y, 70, 20,
                new TextComponent("保存"), b -> this.save()));
        this.addRenderableWidget(new Button(this.leftPos + WIDTH - 46 - 70, this.topPos + BUTTON_Y, 70, 20,
                new TextComponent("取消"), b -> this.onClose()));
    }

    private String targetLabel() {
        if (pendingSorterId == null || pendingSorterName == null) {
            return "分类者：最近的一台";
        }
        return "分类者：" + ManagerUi.ellipsize(this.font, pendingSorterName, WIDTH - 90);
    }

    private static int fieldY(int topPos, int offset) {
        return topPos + offset;
    }

    /** 在坐标行中按列追加一个输入框；col 为 0/1/2，对应 X/Y/Z */
    private EditBox addField(int col, int rowY, @Nullable BlockPos cached, int axis) {
        int fieldX = this.leftPos + FIELD_X + col * (FIELD_WIDTH + FIELD_GAP);
        String value = "";
        if (cached != null) {
            value = String.valueOf(axis == 0 ? cached.getX() : axis == 1 ? cached.getY() : cached.getZ());
        }
        EditBox box = new EditBox(this.font, fieldX, rowY, FIELD_WIDTH, FIELD_HEIGHT,
                new TextComponent(axisName(axis)));
        // 单个坐标分量最多 9 个字符（含负号），这是 ±30000000 的上限长度
        box.setMaxLength(9);
        // 只允许数字与负号，输入时就挡住明显无效的输入
        box.setFilter(text -> text.isEmpty() || text.equals("-") || text.matches("-?\\d{0,9}"));
        box.setValue(value);
        this.addRenderableWidget(box);
        return box;
    }

    /** 每行右侧并排两个小按钮：「填入当前」与「清除」 */
    private void addRowButtons(int rowOffset, EditBox xBox, EditBox yBox, EditBox zBox, boolean isA) {
        int buttonY = this.topPos + rowOffset - 1;
        Button fill = new Button(this.leftPos + FILL_X, buttonY, FILL_WIDTH, 20,
                new TextComponent("填入当前"), b -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                BlockPos pos = player.blockPosition();
                xBox.setValue(String.valueOf(pos.getX()));
                yBox.setValue(String.valueOf(pos.getY()));
                zBox.setValue(String.valueOf(pos.getZ()));
            }
        });
        this.addRenderableWidget(fill);

        Button clear = new Button(this.leftPos + CLEAR_X, buttonY, CLEAR_WIDTH, 20,
                new TextComponent("清除"), b -> {
            xBox.setValue("");
            yBox.setValue("");
            zBox.setValue("");
            if (isA) {
                tempPosA = null;
            } else {
                tempPosB = null;
            }
        });
        this.addRenderableWidget(clear);
    }

    /** NBT 里是否已有该端点的坐标 */
    private static boolean hasCorner(ItemStack stack, String key) {
        return !stack.isEmpty() && ClassifierManagerItem.getWorkspaceCorner(stack, key) != null;
    }

    private void save() {
        BlockPos pos1;
        BlockPos pos2;
        try {
            pos1 = parseCorner(this.ax, this.ay, this.az);
            pos2 = parseCorner(this.bx, this.by, this.bz);
        } catch (NumberFormatException e) {
            this.errorText = "请输入正确的整数坐标";
            return;
        } catch (IllegalArgumentException e) {
            this.errorText = "坐标超出世界边界（-30000000 ~ 30000000）";
            return;
        }
        if (pos1.equals(pos2)) {
            this.errorText = "点 A 与点 B 不能重合";
            return;
        }
        // 与客户端滚轮、服务端的体积上限保持一致，超限不写入本地 NBT
        long volume = SorterEntity.workspaceVolume(pos1, pos2);
        if (volume > SorterEntity.MAX_WORKSPACE_VOLUME) {
            this.errorText = "工作区范围过大，最大支持 " + SorterEntity.MAX_WORKSPACE_VOLUME + " 格！";
            return;
        }

        // 先写一份本地 NBT，手柄线框立即刷新；服务端保存后再以服务端为准
        ItemStack stack = this.heldManager();
        if (!stack.isEmpty()) {
            ClassifierManagerItem.setWorkspaceCorners(stack, pos1, pos2);
        }
        // 保存成功后草稿作废，下次打开从空白开始
        tempPosA = null;
        tempPosB = null;
        skipNbtRestoreOnce = true;
        skipDraftOnRemoved = true;

        String name = this.nameBox.getValue().trim();
        ModNetwork.INSTANCE.sendToServer(ManagerActionPacket.setWorkspace(pos1, pos2,
                pendingSorterId, name.isEmpty() ? null : name));
        pendingSorterId = null;
        pendingSorterName = null;
        Minecraft.getInstance().setScreen(this.parent);
    }

    /** 读取一行的三个输入框；空值或非整数抛 NumberFormatException，越界抛 IllegalArgumentException */
    private static BlockPos parseCorner(EditBox xBox, EditBox yBox, EditBox zBox) {
        return new BlockPos(parse(xBox), parse(yBox), parse(zBox));
    }

    /** 关闭时把填好的行存入草稿；空行 / 半截 / 超长 / 越界坐标一律视为该行没有草稿 */
    @Nullable
    private static BlockPos tryParseCorner(EditBox xBox, EditBox yBox, EditBox zBox) {
        try {
            return parseCorner(xBox, yBox, zBox);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Esc / 取消 / 切界面都会走到这里：离开前缓存两行输入，下次打开可继续填 */
    @Override
    public void removed() {
        if (skipDraftOnRemoved) {
            skipDraftOnRemoved = false;
        } else {
            tempPosA = tryParseCorner(this.ax, this.ay, this.az);
            tempPosB = tryParseCorner(this.bx, this.by, this.bz);
        }
        super.removed();
    }

    private static int parse(EditBox box) {
        String text = box.getValue().trim();
        // 长度硬校验：坐标分量最多 9 个字符，超出视为无效
        if (text.isEmpty() || text.length() > 9) {
            throw new NumberFormatException("empty or too long");
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new NumberFormatException(text);
        }
        if (value < MIN_COORD || value > MAX_COORD) {
            throw new IllegalArgumentException("out of world bounds: " + value);
        }
        return value;
    }

    private ItemStack heldManager() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : ClassifierManagerItem.findHeldManager(player);
    }

    private static String axisName(int axis) {
        return axis == 0 ? "X" : axis == 1 ? "Y" : "Z";
    }

    /** 一行三个输入框都填了才算“已存”，否则显示“空” */
    private static String cacheState(EditBox xBox, EditBox yBox, EditBox zBox) {
        boolean filled = !xBox.getValue().trim().isEmpty()
                && !yBox.getValue().trim().isEmpty()
                && !zBox.getValue().trim().isEmpty();
        return filled ? "§a已存" : "§8空";
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        ManagerUi.renderFrame(poseStack, this.leftPos, this.topPos, WIDTH, HEIGHT);

        int headerTextY = this.topPos + ManagerUi.centeredTextY(0, ManagerUi.HEADER_HEIGHT);
        this.font.draw(poseStack, this.title,
                this.leftPos + (WIDTH - this.font.width(this.title)) / 2.0F, headerTextY, ManagerUi.COLOR_TEXT);

        // 说明文字单独占一行，避免与输入框贴在一起
        Component intro = new TextComponent("输入两个对角坐标，或点击“填入当前”");
        this.font.draw(poseStack, intro,
                this.leftPos + (WIDTH - this.font.width(intro)) / 2.0F, this.topPos + 30, ManagerUi.COLOR_TEXT_DIM);

        this.font.draw(poseStack, "名称", this.leftPos + 14, this.topPos + NAME_Y + 5, ManagerUi.COLOR_TEXT_DIM);

        this.renderRowLabels(poseStack, FIELD_Y_A, "点 A", this.ax, this.ay, this.az);
        this.renderRowLabels(poseStack, FIELD_Y_B, "点 B", this.bx, this.by, this.bz);

        this.font.draw(poseStack, "绑定", this.leftPos + 14, this.topPos + SORTER_Y + 6, ManagerUi.COLOR_TEXT_DIM);

        // 缓存状态：A/B 行当前是否有已填好（尚未保存）的坐标
        String cacheStatus = "缓存  A=" + cacheState(this.ax, this.ay, this.az)
                + "  B=" + cacheState(this.bx, this.by, this.bz);
        Component cacheLine = new TextComponent(cacheStatus);
        this.font.draw(poseStack, cacheLine,
                this.leftPos + (WIDTH - this.font.width(cacheLine)) / 2.0F, this.topPos + 198,
                ManagerUi.COLOR_TEXT_DIM);

        // 底部提示与错误信息
        Component hint;
        int hintColor;
        if (this.errorText != null) {
            hint = new TextComponent(this.errorText);
            hintColor = ManagerUi.COLOR_BAD;
        } else {
            hint = new TextComponent("名称可留空；重名会被服务端拒绝");
            hintColor = ManagerUi.COLOR_TEXT_FAINT;
        }
        this.font.draw(poseStack, hint,
                this.leftPos + (WIDTH - this.font.width(hint)) / 2.0F, this.topPos + 214, hintColor);

        super.render(poseStack, mouseX, mouseY, partialTick);
    }

    /** 行首标签 + 三个轴向说明，轴向说明画在输入框下方 */
    private void renderRowLabels(PoseStack poseStack, int rowOffset, String label,
                                 EditBox xBox, EditBox yBox, EditBox zBox) {
        int rowY = this.topPos + rowOffset;
        this.font.draw(poseStack, label, this.leftPos + 14, rowY + 5, ManagerUi.COLOR_TEXT_DIM);
        drawAxis(poseStack, xBox, rowY, "X");
        drawAxis(poseStack, yBox, rowY, "Y");
        drawAxis(poseStack, zBox, rowY, "Z");
    }

    private void drawAxis(PoseStack poseStack, EditBox box, int rowY, String axis) {
        this.font.draw(poseStack, axis,
                box.x + FIELD_WIDTH / 2.0F - this.font.width(axis) / 2.0F,
                rowY + FIELD_HEIGHT + 3, ManagerUi.COLOR_TEXT_FAINT);
        // 输入框之间的浅色分隔，强调 XYZ 三列关系
        if (!"Z".equals(axis)) {
            int dividerX = box.x + FIELD_WIDTH + FIELD_GAP / 2;
            GuiComponent.fill(poseStack, dividerX, rowY + 4, dividerX + 1, rowY + FIELD_HEIGHT - 4, 0x33FFFFFF);
        }
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
