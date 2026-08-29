package me.aleksilassila.litematica.printer.interfaces.compat;

import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;

/** Shared inventory queries used by both Quick Shulker protocol implementations. */
final class QuickShulkerInventory {
    private static final int FIRST_STORAGE_SLOT = 0;
    private static final int HOTBAR_SIZE = 9;
    private static final int INVENTORY_MENU_HOTBAR_START = 36;

    private QuickShulkerInventory() {
    }

    static int findEmptySlot(Inventory inventory) {
        for (int slot = 0; slot < storageSize(inventory); slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /** Converts a player inventory index to its slot id in the vanilla inventory menu. */
    static int inventoryMenuSlot(int inventorySlot) {
        return inventorySlot >= 0 && inventorySlot < HOTBAR_SIZE
                ? INVENTORY_MENU_HOTBAR_START + inventorySlot
                : inventorySlot;
    }

    static int findItem(Inventory inventory, Item item) {
        for (int slot = 0; slot < storageSize(inventory); slot++) {
            if (inventory.getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    static int findShulkerWithItem(Inventory inventory, Item item) {
        for (int slot = FIRST_STORAGE_SLOT; slot < storageSize(inventory); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isSingleShulker(stack)
                    && contents(stack).stream().anyMatch(stored -> stored.is(item))) {
                return slot;
            }
        }
        return -1;
    }

    static ShulkerSlot findShulkerSlotWithItem(Inventory inventory, Item item) {
        for (int inventorySlot = FIRST_STORAGE_SLOT;
             inventorySlot < storageSize(inventory); inventorySlot++) {
            ItemStack box = inventory.getItem(inventorySlot);
            if (!isSingleShulker(box)) continue;

            List<ItemStack> slots = shulkerSlots(box);
            for (int shulkerSlot = 0; shulkerSlot < slots.size(); shulkerSlot++) {
                if (slots.get(shulkerSlot).is(item)) {
                    return new ShulkerSlot(inventorySlot, shulkerSlot);
                }
            }
        }
        return null;
    }

    static int findAnyShulker(Inventory inventory) {
        for (int slot = FIRST_STORAGE_SLOT; slot < storageSize(inventory); slot++) {
            if (isSingleShulker(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    static boolean isInventoryFull(Inventory inventory) {
        return findEmptySlot(inventory) < 0;
    }

    static boolean isSingleShulker(ItemStack stack) {
        return isShulker(stack) && stack.getCount() == 1;
    }

    static boolean isShulker(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
    }

    static List<ItemStack> contents(ItemStack stack) {
        NonNullList<ItemStack> stored = InventoryUtils.getStoredItems(stack, -1);
        return copyNonEmpty(stored);
    }

    static int findInsertSlot(ItemStack box, ItemStack inserted) {
        if (!isSingleShulker(box) || inserted == null || inserted.isEmpty()) return -1;

        List<ItemStack> slots = shulkerSlots(box);
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack stored = slots.get(slot);
            if (ItemStack.isSameItemSameComponents(stored, inserted)
                    && stored.getCount() < stored.getMaxStackSize()) {
                return slot;
            }
        }
        for (int slot = 0; slot < slots.size(); slot++) {
            if (slots.get(slot).isEmpty()) return slot;
        }
        return slots.size();
    }

    static List<ItemStack> containerContents(AbstractContainerMenu container, int ownSlots) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < ownSlots; slot++) {
            ItemStack stack = container.slots.get(slot).getItem();
            if (!stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    static boolean sameContents(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        boolean[] matched = new boolean[second.size()];
        for (ItemStack item : first) {
            boolean found = false;
            for (int index = 0; index < second.size(); index++) {
                if (!matched[index] && InventoryUtils.areStacksEqual(item, second.get(index))) {
                    matched[index] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    static int storageSize(Inventory inventory) {
        return inventory.getNonEquipmentItems().size();
    }

    private static List<ItemStack> shulkerSlots(ItemStack box) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents == null ? List.of() : contents.allItemsCopyStream().toList();
    }

    private static List<ItemStack> copyNonEmpty(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    record ShulkerSlot(int inventorySlot, int shulkerSlot) {
    }
}
