package com.tidengyogvin.classifier.client;

import net.minecraft.core.BlockPos;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 雷达高亮的交互锁定：
 * 右键打开了被高亮的目标箱时取消高亮；如果玩家在雷达界面里点了 [锁定]，
 * 则必须回到雷达手动解锁，右键开箱不会取消。
 */
public final class RadarInteractionHandler {

    private RadarInteractionHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // 主副手都会触发，只处理主手；高亮状态只存在于逻辑客户端
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || event.getSide() != net.minecraftforge.fml.LogicalSide.CLIENT) {
            return;
        }
        BlockPos highlighted = RadarHighlight.get();
        if (highlighted == null || !highlighted.equals(event.getPos())) {
            return;
        }
        if (RadarHighlight.isLocked(highlighted)) {
            return; // 已锁定：保持高亮，必须回雷达手动解锁
        }
        RadarHighlight.clear();
    }
}
