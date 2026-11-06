package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tidengyogvin.classifier.CategoryRules;
import com.tidengyogvin.classifier.ClassifierManagerItem;
import com.tidengyogvin.classifier.ItemInit;
import com.tidengyogvin.classifier.ManagerData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 管理器与仓储雷达的世界内渲染：
 * - 已选中容器时，在容器周围画一圈半透明白色线框；
 * - 已划定工作区时，用与界面强调色一致的金色线框画出一个 AABB；
 * - 雷达选中的箱子用类别色透视高亮（隔墙可见）。
 * 管理器相关线框只在本机手持时显示，不影响其他玩家。
 */
public class ManagerRenderer {

    private static final float OPACITY = 0.55F;
    private static final float WORKSPACE_OPACITY = 0.6F;
    private static final float WORKSPACE_RED = 0.88F;
    private static final float WORKSPACE_GREEN = 0.69F;
    private static final float WORKSPACE_BLUE = 0.35F;
    /** 雷达高亮线框的不透明度 */
    private static final float RADAR_OPACITY = 0.9F;
    /**
     * 单条线框允许的最大边长（格）。超过这个尺度说明数据已损坏或工作区失控，
     * 渲染上万格的线段会让 GPU 管线崩溃（驱动 0xC0000005）。
     */
    private static final double MAX_RENDER_EDGE = 256.0D;

    /**
     * 透视专用的线框渲染类型：与 RenderType.LINES 同参数，但深度测试状态为“始终通过”。
     * 原版 LINES 在 setupRenderState 里会重新启用深度测试，只在外层 disableDepthTest 不会生效，
     * 所以必须让渲染状态本身不依赖深度测试，才能真正做到隔墙透视。
     *
     * 这里故意不用 static final 立即初始化：RenderType 的组装会触碰 Blaze3D 的渲染状态，
     * 若类加载发生在渲染线程 / OpenGL 上下文就绪之前，会直接触发原生崩溃（0xC0000005）。
     * 改为首次真正渲染时才创建，只在 RenderLevelStageEvent（渲染线程）里调用。
     */
    private static RenderType radarLinesCache = null;

    private static RenderType getRadarLines() {
        if (radarLinesCache == null) {
            radarLinesCache = RadarLineType.create();
        }
        return radarLinesCache;
    }

    /**
     * 借用 RenderStateShard 子类访问 protected 的状态常量，避免修改游戏源码/AT。
     * 自身不参与渲染，只用来在同一个类体里组装 CompositeState。
     */
    private static final class RadarLineType extends RenderStateShard {

        private RadarLineType() {
            super("classifier_radar_lines", () -> {
            }, () -> {
            });
        }

        private static RenderType create() {
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_LINES_SHADER)
                    .setLineState(new LineStateShard(java.util.OptionalDouble.empty()))
                    .setLayeringState(NO_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setTextureState(NO_TEXTURE)
                    .setWriteMaskState(COLOR_WRITE)
                    .setCullState(NO_CULL)
                    .setDepthTestState(NO_DEPTH_TEST)
                    .setOutputState(ITEM_ENTITY_TARGET)
                    .setTexturingState(DEFAULT_TEXTURING)
                    .setLightmapState(NO_LIGHTMAP)
                    .setOverlayState(NO_OVERLAY)
                    .createCompositeState(false);
            return RenderType.create("classifier_radar_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                    VertexFormat.Mode.LINES, 256, false, false, state);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null) {
            return;
        }

        ItemStack managerStack = findManagerStack(player);
        BlockPos radarPos = RadarHighlight.get();
        // 雷达高亮只对当前维度的箱子有效：离开维度后条目不在了，高亮自动消失
        if (radarPos != null && (RadarHighlight.entryOf(radarPos) == null || !level.hasChunkAt(radarPos))) {
            radarPos = null;
        }
        if (managerStack.isEmpty() && radarPos == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        if (!managerStack.isEmpty()) {
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            // 工作区边界：坐标直接来自物品 NBT，保存后无需等待同步即可显示
            AABB workspace = ClassifierManagerItem.getWorkspaceBox(managerStack);
            if (workspace != null && isRenderable(workspace)) {
                LevelRenderer.renderLineBox(poseStack, lines, workspace.inflate(0.004D),
                        WORKSPACE_RED, WORKSPACE_GREEN, WORKSPACE_BLUE, WORKSPACE_OPACITY);
            }

            // 已选中的容器（双箱合并后的 AABB 同样经过尺寸校验）
            BlockPos selected = ClassifierManagerItem.getSelectedAnchor(level, managerStack);
            if (selected != null && ClassifierManagerItem.hasSelection(managerStack, level)) {
                AABB selectedBox = ClassifierManagerItem.getContainerBox(level, selected);
                if (selectedBox != null && isRenderable(selectedBox)) {
                    LevelRenderer.renderLineBox(poseStack, lines, selectedBox, 1.0F, 1.0F, 1.0F, OPACITY);
                }
            }
            // 普通线框以常规深度测试冲刷，先落地，保证雷达透视是独立的一批
            buffers.endBatch(RenderType.lines());
        }

        // 雷达选中的箱子：使用“无深度测试”的线框类型，隔墙可见
        AABB radarBox = radarPos == null ? null : ManagerRenderer.boxOf(level, radarPos);
        if (radarBox != null && isRenderable(radarBox)) {
            ManagerData.RadarEntry entry = RadarHighlight.entryOf(radarPos);
            float[] color = CategoryRules.color(entry == null ? null : entry.category());
            RenderType radarLinesType = getRadarLines();
            VertexConsumer radarLines = buffers.getBuffer(radarLinesType);
            LevelRenderer.renderLineBox(poseStack, radarLines, radarBox.inflate(0.006D),
                    color[0], color[1], color[2], RADAR_OPACITY);

            RenderSystem.disableDepthTest();
            try {
                buffers.endBatch(radarLinesType);
            } finally {
                // 无论冲刷过程发生什么，都必须恢复深度测试，否则整局画面都会“穿透”
                RenderSystem.enableDepthTest();
            }
        }

        poseStack.popPose();
    }

    /** 线框尺寸校验：任一边超过 MAX_RENDER_EDGE 直接跳过渲染 */
    private static boolean isRenderable(AABB box) {
        return box.maxX - box.minX <= MAX_RENDER_EDGE
                && box.maxY - box.minY <= MAX_RENDER_EDGE
                && box.maxZ - box.minZ <= MAX_RENDER_EDGE;
    }

    /** 方块实际形状的包围盒（箱子等不规则方块也能贴合） */
    private static AABB boxOf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        return shape.isEmpty()
                ? new AABB(pos)
                : shape.bounds().move(pos.getX(), pos.getY(), pos.getZ());
    }

    private static ItemStack findManagerStack(LocalPlayer player) {
        if (player.getMainHandItem().is(ItemInit.CLASSIFIER_MANAGER.get())) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().is(ItemInit.CLASSIFIER_MANAGER.get())) {
            return player.getOffhandItem();
        }
        return ItemStack.EMPTY;
    }
}
