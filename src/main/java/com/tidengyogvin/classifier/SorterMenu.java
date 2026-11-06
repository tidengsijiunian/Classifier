package com.tidengyogvin.classifier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class SorterMenu extends AbstractContainerMenu {

    private final int entityId;
    private final Level level;

    public SorterMenu(int windowId, Inventory inv, FriendlyByteBuf buf) {
        this(windowId, inv, buf.readInt());
    }

    public SorterMenu(int windowId, Inventory inv, int entityId) {
        super(ModMenuTypes.SORTER_MENU.get(), windowId);
        this.entityId = entityId;
        this.level = inv.player.level;
    }

    public int getEntityId() {
        return entityId;
    }

    public Level getLevel() {
        return level;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
