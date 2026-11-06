package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

/**
 * 管理器系列界面的公共绘制样式。
 *
 * 风格约定：先由原版 renderBackground 铺一层暗化模糊，再在之上画深灰面板、
 * 1px 亮灰边框、标题栏和金色强调条。所有坐标参数都是绝对屏幕坐标。
 */
public final class ManagerUi {

    /** 标题栏高度 */
    public static final int HEADER_HEIGHT = 22;

    public static final int COLOR_SHADOW = 0x50000000;
    public static final int COLOR_PANEL_EDGE = 0xE60D0F12;
    public static final int COLOR_PANEL = 0xF0171A1F;
    public static final int COLOR_HEADER = 0xFF12151A;
    public static final int COLOR_BORDER = 0xFF4A505B;
    public static final int COLOR_HEADER_DIVIDER = 0xFF2C3138;
    public static final int COLOR_ACCENT = 0xFFE0B15A;

    public static final int COLOR_TEXT = 0xFFECECEC;
    public static final int COLOR_TEXT_DIM = 0xFF939AA4;
    public static final int COLOR_TEXT_FAINT = 0xFF666C75;
    public static final int COLOR_GOOD = 0xFF6FD08C;
    public static final int COLOR_BAD = 0xFFE07070;
    /** 半绑定状态的琥珀色，与金色强调条区分开 */
    public static final int COLOR_WARN = 0xFFE3B341;

    private ManagerUi() {
    }

    /** 面板 + 投影 + 边角描边 + 标题栏，不含文字 */
    public static void renderFrame(PoseStack poseStack, int left, int top, int width, int height) {
        int right = left + width;
        int bottom = top + height;

        // 投影：向右下偏移 3px 的半透明黑，制造悬浮感
        GuiComponent.fill(poseStack, left + 3, top + 3, right + 3, bottom + 3, COLOR_SHADOW);
        // 外圈深色描边 + 内层面板，形成 1px 的层次
        GuiComponent.fill(poseStack, left, top, right, bottom, COLOR_PANEL_EDGE);
        GuiComponent.fill(poseStack, left + 1, top + 1, right - 1, bottom - 1, COLOR_PANEL);
        // 标题栏与分割线
        GuiComponent.fill(poseStack, left + 1, top + 1, right - 1, top + HEADER_HEIGHT, COLOR_HEADER);
        GuiComponent.fill(poseStack, left + 1, top + HEADER_HEIGHT - 1, right - 1, top + HEADER_HEIGHT,
                COLOR_HEADER_DIVIDER);
        // 标题左侧的金色强调条
        GuiComponent.fill(poseStack, left + 9, top + 7, left + 11, top + 15, COLOR_ACCENT);
        // 1px 亮灰边框
        outline(poseStack, left, top, right, bottom, COLOR_BORDER);
        // 顶部内高光
        GuiComponent.fill(poseStack, left + 1, top + 1, right - 1, top + 2, 0x12FFFFFF);
    }

    public static void outline(PoseStack poseStack, int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill(poseStack, x1, y1, x2, y1 + 1, color);
        GuiComponent.fill(poseStack, x1, y2 - 1, x2, y2, color);
        GuiComponent.fill(poseStack, x1, y1 + 1, x1 + 1, y2 - 1, color);
        GuiComponent.fill(poseStack, x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    /** 文本在指定高度内垂直居中的 y 坐标 */
    public static int centeredTextY(int top, int height) {
        return top + (height - 8) / 2;
    }

    /** 按像素宽度截断文本，超出时补省略号 */
    public static String ellipsize(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int room = maxWidth - font.width(ellipsis);
        if (room <= 0) {
            return ellipsis;
        }
        return font.plainSubstrByWidth(text, room) + ellipsis;
    }
}
