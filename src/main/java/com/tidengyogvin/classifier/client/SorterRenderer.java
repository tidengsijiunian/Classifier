package com.tidengyogvin.classifier.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;
import com.tidengyogvin.classifier.SorterEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;

public class SorterRenderer extends LivingEntityRenderer<SorterEntity, PlayerModel<SorterEntity>> {

    private static final ResourceLocation DEFAULT_SKIN =
            new ResourceLocation("textures/entity/steve.png");

    // 头顶双层文字的最大渲染距离（64 格，用平方值比较）
    private static final double NAME_TAG_RANGE_SQR = 64.0D * 64.0D;

    public SorterRenderer(EntityRendererProvider.Context context) {
        super(context, createModel(context), 0.5F);
    }

    private static PlayerModel<SorterEntity> createModel(EntityRendererProvider.Context context) {
        ModelPart root = context.bakeLayer(ModelLayers.PLAYER);
        // 只隐藏真正的披风与死亡之帽耳朵：
        // 1) 1.18.2 的 PlayerModel 中披风字段是 private 的 cloak，不存在 cape 字段，
        //    所以 this.model.cloak.visible = false 无法编译；
        // 2) ModelPart.children 也是 private，无法遍历子部件；
        // 3) 原版 PlayerModel 构造器内部同样用 root.getChild("cloak") / root.getChild("ear")
        //    取这两个部件，这里从同一根节点取到的是同一实例，与 cloak.visible = false 完全等效。
        // hat/jacket/sleeves/pants 属于皮肤第二层装饰，保持原版可见，不做任何隐藏。
        ModelPart cloak = root.getChild("cloak");
        cloak.visible = false;
        ModelPart ear = root.getChild("ear");
        ear.visible = false;
        return new PlayerModel<>(root, false);
    }

    @Override
    protected void scale(SorterEntity entity, PoseStack poseStack, float partialTick) {
        // 与原版玩家渲染保持一致：模型比碰撞箱略小
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    protected boolean shouldShowName(SorterEntity entity) {
        // 始终显示头顶双层文字（原版默认要求实体有自定义名称才会调用 renderNameTag）
        return true;
    }

    @Override
    public ResourceLocation getTextureLocation(SorterEntity entity) {
        String skinPlayer = entity.getSkinPlayer();
        if (skinPlayer != null && !skinPlayer.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(skinPlayer);
                if (info != null) {
                    return info.getSkinLocation();
                }
            }
        }
        return DEFAULT_SKIN;
    }

    @Override
    protected void renderNameTag(SorterEntity entity, Component displayName, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        // 超过 64 格不渲染
        if (this.entityRenderDispatcher.distanceToSqr(entity) > NAME_TAG_RANGE_SQR) {
            return;
        }
        // 处于 discrete 状态（隐藏/潜行语义）时不渲染
        if (entity.isDiscrete()) {
            return;
        }
        // 注意：这里不再调用 super.renderNameTag(...)，避免原版单行名字覆盖双层文字

        Component statusText = new TextComponent(entity.isWorking() ? "工作中" : "休闲中");

        poseStack.pushPose();
        poseStack.translate(0.0D, entity.getBbHeight() + 0.5D, 0.0D);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();

        Font font = this.getFont();
        int backgroundColor = (int) (Minecraft.getInstance().options.getBackgroundOpacity(0.25F) * 255.0F) << 24;

        // 第一行：实体名
        float nameX = (float) (-font.width(displayName) / 2);
        font.drawInBatch(displayName, nameX, 0.0F, 553648127, false, matrix, bufferSource, true, backgroundColor, packedLight);
        font.drawInBatch(displayName, nameX, 0.0F, -1, false, matrix, bufferSource, false, 0, packedLight);

        // 第二行：工作状态（名字正下方）
        float statusX = (float) (-font.width(statusText) / 2);
        font.drawInBatch(statusText, statusX, 10.0F, 553648127, false, matrix, bufferSource, true, backgroundColor, packedLight);
        font.drawInBatch(statusText, statusX, 10.0F, -1, false, matrix, bufferSource, false, 0, packedLight);

        poseStack.popPose();
    }
}
