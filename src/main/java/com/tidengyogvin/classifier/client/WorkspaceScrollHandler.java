package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tidengyogvin.classifier.ClassifierManagerItem;
import com.tidengyogvin.classifier.ModNetwork;
import com.tidengyogvin.classifier.SorterEntity;
import com.tidengyogvin.classifier.network.ManagerActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;

/**
 * Alt + 滚轮微调工作区。
 *
 * 手持管理器且已保存 Pos1/Pos2 时：按住 Alt 滚动滚轮，把边界沿玩家视线所在的主轴移动 1 格。
 * 滚轮向上 = 朝视线方向拉伸，滚轮向下 = 收缩（至少保留 1 格）。
 * 本地立即写 NBT 让线框实时刷新，同时发送 C2S 包让服务端写入并同步给附近分类者。
 */
public final class WorkspaceScrollHandler {

    /** 同一次滚动产生的连续事件太密集，节流到 90ms 一次 */
    private static final long DEBOUNCE_MILLIS = 90;
    /** 世界坐标与工作区体积上限，和服务端保持一致 */
    private static final int MAX_COORD = SorterEntity.MAX_WORLD_COORD;

    private static long lastAdjustAt = 0L;

    private WorkspaceScrollHandler() {
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollEvent event) {
        Minecraft mc = Minecraft.getInstance();
        // 任何 GUI 打开、或鼠标未被游戏捕获（窗口失焦/自由光标）时一律不处理
        if (mc.screen != null || !mc.mouseHandler.isMouseGrabbed()) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (!isAltDown(mc)) {
            return;
        }
        ItemStack stack = ClassifierManagerItem.findHeldManager(player);
        if (stack.isEmpty()) {
            return;
        }
        BlockPos pos1 = ClassifierManagerItem.getWorkspaceCorner(stack, ClassifierManagerItem.TAG_POS1);
        BlockPos pos2 = ClassifierManagerItem.getWorkspaceCorner(stack, ClassifierManagerItem.TAG_POS2);
        if (pos1 == null || pos2 == null) {
            return;
        }
        // 读取到的坐标先做边界校验，坏数据不参与调整
        if (!isWithinWorld(pos1) || !isWithinWorld(pos2)) {
            return;
        }
        double scroll = event.getScrollDelta();
        if (scroll == 0) {
            return;
        }
        // 无论是否节流都要吞掉这次滚动，防止 Alt+滚轮顺带切换快捷栏
        event.setCanceled(true);

        long now = System.currentTimeMillis();
        if (now - lastAdjustAt < DEBOUNCE_MILLIS) {
            return;
        }
        lastAdjustAt = now;

        int direction = scroll > 0 ? 1 : -1;
        BlockPos[] corners = resizeAlongView(pos1, pos2, player.getLookAngle(), direction);
        if (corners == null) {
            player.displayClientMessage(new TextComponent("§c工作区已达体积上限或世界边界"), true);
            return;
        }

        // 先写本地 NBT：手持线框下一帧立即反映新边界
        ClassifierManagerItem.setWorkspaceCorners(stack, corners[0], corners[1]);
        // 再交给服务端：写入物品并同步给附近分类者
        ModNetwork.INSTANCE.sendToServer(ManagerActionPacket.setWorkspace(corners[0], corners[1]));

        player.displayClientMessage(new TextComponent("§e工作区: " + describeSize(corners[0], corners[1])), true);
    }

    private static boolean isAltDown(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    /**
     * 沿视线主轴的“远侧”边界移动 1 格：direction > 0 拉伸，< 0 收缩。
     * 返回新的两个角点；无法再缩小时返回 null。
     */
    @Nullable
    private static BlockPos[] resizeAlongView(BlockPos pos1, BlockPos pos2, Vec3 look, int direction) {
        int axis;
        int sign;
        if (Math.abs(look.x) >= Math.abs(look.y) && Math.abs(look.x) >= Math.abs(look.z)) {
            axis = 0;
            sign = look.x >= 0 ? 1 : -1;
        } else if (Math.abs(look.y) >= Math.abs(look.z)) {
            axis = 1;
            sign = look.y >= 0 ? 1 : -1;
        } else {
            axis = 2;
            sign = look.z >= 0 ? 1 : -1;
        }

        int v1 = coord(pos1, axis);
        int v2 = coord(pos2, axis);
        int min = Math.min(v1, v2);
        int max = Math.max(v1, v2);

        // 视线正方向那一侧的边：向上滚动时向外拉伸，向下滚动时向内收缩
        int farValue = sign > 0 ? max : min;
        int otherValue = sign > 0 ? min : max;
        int moved = farValue + sign * direction;
        // 至少保留 1 格：far 不能越过另一侧边界
        if (sign > 0) {
            moved = Math.max(moved, otherValue);
        } else {
            moved = Math.min(moved, otherValue);
        }
        if (moved == farValue) {
            return null;
        }
        // 边界校验：移动后不能越出世界坐标范围
        if (moved < -MAX_COORD || moved > MAX_COORD) {
            return null;
        }
        // 体积校验：滚轮不能在不知不觉间把工作区撑过上限
        BlockPos candidate1 = v1 == farValue ? withCoord(pos1, axis, moved) : pos1;
        BlockPos candidate2 = v1 == farValue ? pos2 : withCoord(pos2, axis, moved);
        if (!SorterEntity.isWorkspaceVolumeValid(candidate1, candidate2)) {
            return null;
        }

        // 把新值写回“原来等于 farValue”的那个角点
        if (v1 == farValue || v2 == farValue) {
            return new BlockPos[]{candidate1, candidate2};
        }
        // 两端相等时（理论上被保存逻辑排除）默认移动第二个角点
        return new BlockPos[]{pos1, withCoord(pos2, axis, moved)};
    }

    private static boolean isWithinWorld(BlockPos pos) {
        return Math.abs(pos.getX()) <= MAX_COORD
                && Math.abs(pos.getY()) <= MAX_COORD
                && Math.abs(pos.getZ()) <= MAX_COORD;
    }

    private static int coord(BlockPos pos, int axis) {
        return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
    }

    private static BlockPos withCoord(BlockPos pos, int axis, int value) {
        return new BlockPos(axis == 0 ? value : pos.getX(),
                axis == 1 ? value : pos.getY(),
                axis == 2 ? value : pos.getZ());
    }

    /** 区域大小：各轴格数 */
    private static String describeSize(BlockPos pos1, BlockPos pos2) {
        int dx = Math.abs(pos1.getX() - pos2.getX()) + 1;
        int dy = Math.abs(pos1.getY() - pos2.getY()) + 1;
        int dz = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
        return dx + "×" + dy + "×" + dz;
    }
}
