package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Quarantines the old screen-based Quick Shulker and plugin integration.
 * Nothing outside {@link QuickShulkerCompat} should depend on this state machine.
 */
final class QuickShulkerLegacySession {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int MAX_DEFERRED_CLOSE_RETRIES = 10;

    private static final LinkedList<ReturnRequest> itemsToReturn = new LinkedList<>();
    private static final List<TrackedShulker> trackedShulkers = new ArrayList<>();

    private static boolean openHandler;
    private static int cooldown;
    private static Item requestedItem;
    private static ReturnRequest activeReturnRequest;
    private static TrackedShulker activeShulker;
    private static int deferredCloseTicks;
    private static int deferredCloseRetries;

    private QuickShulkerLegacySession() {
    }

    static boolean request(LocalPlayer player, Item[] items, ShulkerSource source) {
        if (player == null || items == null || items.length == 0 || source == ShulkerSource.TAKE_IT_OUT
                || (source == ShulkerSource.MOD && !ModUtils.isQuickShulkerLoaded())
                || isBusy() || cooldown > 0) {
            return false;
        }

        Inventory inventory = player.getInventory();
        discardReturnsWithoutMaterial(inventory);

        if (QuickShulkerInventory.isInventoryFull(inventory) && !itemsToReturn.isEmpty()) {
            ReturnRequest request = itemsToReturn.getFirst();
            int boxSlot = Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                    ? findReturnShulker(inventory, request)
                    : QuickShulkerInventory.findAnyShulker(inventory);
            if (boxSlot < 0) return false;

            activeReturnRequest = request;
            activeShulker = Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                    ? request.shulker()
                    : null;
            return open(inventory, boxSlot, source);
        }

        for (Item item : items) {
            int boxSlot = QuickShulkerInventory.findShulkerWithItem(inventory, item);
            if (boxSlot < 0) continue;

            ItemStack box = inventory.getItem(boxSlot);
            activeReturnRequest = null;
            activeShulker = findTrackedShulker(box);
            if (activeShulker == null) {
                activeShulker = new TrackedShulker(box.getItem(), QuickShulkerInventory.contents(box));
                trackedShulkers.add(activeShulker);
            }
            activeShulker.setLastKnownSlot(boxSlot);
            requestedItem = item;
            return open(inventory, boxSlot, source);
        }
        return false;
    }

    static void tick() {
        if (cooldown > 0) cooldown--;
        if (deferredCloseTicks <= 0 || --deferredCloseTicks != 0) return;

        LocalPlayer player = MC.player;
        if (player == null || player.containerMenu.equals(player.inventoryMenu)) {
            deferredCloseRetries = 0;
        } else if (player.containerMenu.getCarried().isEmpty()
                || ++deferredCloseRetries >= MAX_DEFERRED_CLOSE_RETRIES) {
            player.closeContainer();
            deferredCloseRetries = 0;
        } else {
            deferredCloseTicks = 1;
        }
    }

    static boolean isBusy() {
        return openHandler || deferredCloseTicks > 0;
    }

    static int cooldown() {
        return cooldown;
    }

    static boolean isOpenHandler() {
        return openHandler;
    }

    static void reset() {
        openHandler = false;
        cooldown = 0;
        requestedItem = null;
        itemsToReturn.clear();
        trackedShulkers.clear();
        activeReturnRequest = null;
        activeShulker = null;
        deferredCloseTicks = 0;
        deferredCloseRetries = 0;
        ModUtils.closeScreen = 0;
    }

    /**
     * Advances the legacy transaction only after the server supplied container contents.
     */
    static void handleContainerContent() {
        LocalPlayer player = MC.player;
        if (player == null || player.containerMenu.equals(player.inventoryMenu)) {
            openHandler = false;
            return;
        }

        AbstractContainerMenu container = player.containerMenu;
        Inventory inventory = player.getInventory();
        if (activeReturnRequest != null) {
            returnItem(player, container, inventory, activeReturnRequest);
            finish(player);
            return;
        }

        if (requestedItem == null) {
            finish(player);
            return;
        }

        int ownSlots = container.slots.size() - QuickShulkerInventory.storageSize(inventory);
        for (int slotIndex = 0; slotIndex < ownSlots; slotIndex++) {
            Slot slot = container.slots.get(slotIndex);
            if (!slot.hasItem() || !slot.getItem().is(requestedItem)) continue;

            int emptyInventorySlot = QuickShulkerInventory.findEmptySlot(inventory);
            if (emptyInventorySlot < 0 || MC.gameMode == null) {
                finish(player);
                return;
            }

            int containerTarget = emptyInventorySlot < 9
                    ? ownSlots + 27 + emptyInventorySlot
                    : ownSlots + emptyInventorySlot - 9;
            MC.gameMode.handleContainerInput(
                    container.containerId, slot.index, 0, ContainerInput.PICKUP, player);
            MC.gameMode.handleContainerInput(
                    container.containerId, containerTarget, 0, ContainerInput.PICKUP, player);
            if (activeShulker != null) {
                activeShulker.updateContents(
                        QuickShulkerInventory.containerContents(container, ownSlots));
                itemsToReturn.addLast(new ReturnRequest(requestedItem, activeShulker));
            }
            finish(null);
            return;
        }

        finish(player);
    }

    private static boolean open(Inventory inventory, int boxSlot, ShulkerSource source) {
        boolean opened = source == ShulkerSource.PLUGIN
                ? openPluginShulker(boxSlot)
                : QuickShulkerLegacyBridge.open(inventory.getItem(boxSlot), boxSlot);
        if (!opened) return false;

        ModUtils.closeScreen++;
        openHandler = true;
        cooldown = Configs.Print.SHULKER_COOLDOWN.getIntegerValue();
        return true;
    }

    private static boolean openPluginShulker(int inventorySlot) {
        LocalPlayer player = MC.player;
        if (player == null || MC.gameMode == null) return false;
        MC.gameMode.handleContainerInput(
                player.containerMenu.containerId,
                QuickShulkerInventory.inventoryMenuSlot(inventorySlot),
                1,
                ContainerInput.PICKUP,
                player);
        return true;
    }

    private static void returnItem(LocalPlayer player, AbstractContainerMenu container,
                                   Inventory inventory, ReturnRequest request) {
        if (MC.gameMode == null) {
            itemsToReturn.removeFirstOccurrence(request);
            return;
        }

        int ownSlots = container.slots.size() - QuickShulkerInventory.storageSize(inventory);
        int sourceSlot = QuickShulkerInventory.findItem(inventory, request.item());
        if (sourceSlot < 0) {
            itemsToReturn.removeFirstOccurrence(request);
            return;
        }

        for (int shulkerSlot = 0; shulkerSlot < ownSlots; shulkerSlot++) {
            if (container.slots.get(shulkerSlot).hasItem()) continue;

            int containerSource = sourceSlot < 9
                    ? ownSlots + 27 + sourceSlot
                    : ownSlots + sourceSlot - 9;
            MC.gameMode.handleContainerInput(
                    container.containerId, containerSource, 0, ContainerInput.PICKUP, player);
            MC.gameMode.handleContainerInput(
                    container.containerId, shulkerSlot, 0, ContainerInput.PICKUP, player);
            itemsToReturn.removeFirstOccurrence(request);
            if (request.shulker() != null) {
                request.shulker().updateContents(
                        QuickShulkerInventory.containerContents(container, ownSlots));
            }
            return;
        }

        itemsToReturn.removeFirstOccurrence(request);
    }

    private static void finish(LocalPlayer player) {
        boolean wasReturn = activeReturnRequest != null;
        if (player == null) {
            deferredCloseTicks = 2;
            deferredCloseRetries = 0;
        } else {
            player.closeContainer();
            deferredCloseTicks = 0;
            deferredCloseRetries = 0;
        }

        openHandler = false;
        requestedItem = null;
        activeReturnRequest = null;
        activeShulker = null;
        if (itemsToReturn.isEmpty()) trackedShulkers.clear();
        if (wasReturn) cooldown = 0;
    }

    private static void discardReturnsWithoutMaterial(Inventory inventory) {
        itemsToReturn.removeIf(request ->
                QuickShulkerInventory.findItem(inventory, request.item()) < 0);
        if (itemsToReturn.isEmpty()) trackedShulkers.clear();
    }

    private static int findReturnShulker(Inventory inventory, ReturnRequest request) {
        TrackedShulker tracked = request.shulker();
        int exact = findTrackedShulker(inventory, tracked);
        if (exact >= 0) return exact;

        int lastSlot = tracked.lastKnownSlot();
        if (lastSlot >= 0 && lastSlot < QuickShulkerInventory.storageSize(inventory)
                && inventory.getItem(lastSlot).is(tracked.boxItem())) {
            return lastSlot;
        }

        for (int slot = 0; slot < QuickShulkerInventory.storageSize(inventory); slot++) {
            ItemStack box = inventory.getItem(slot);
            if (!QuickShulkerInventory.isSingleShulker(box)) continue;
            List<ItemStack> contents = QuickShulkerInventory.contents(box);
            if (contents.size() < 27
                    && contents.stream().anyMatch(stored -> stored.is(request.item()))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findTrackedShulker(Inventory inventory, TrackedShulker tracked) {
        for (int slot = 0; slot < QuickShulkerInventory.storageSize(inventory); slot++) {
            ItemStack box = inventory.getItem(slot);
            if (box.is(tracked.boxItem())
                    && QuickShulkerInventory.sameContents(
                    QuickShulkerInventory.contents(box), tracked.contents())) {
                return slot;
            }
        }
        return -1;
    }

    private static TrackedShulker findTrackedShulker(ItemStack box) {
        List<ItemStack> contents = QuickShulkerInventory.contents(box);
        for (TrackedShulker tracked : trackedShulkers) {
            if (box.is(tracked.boxItem())
                    && QuickShulkerInventory.sameContents(contents, tracked.contents())) {
                return tracked;
            }
        }
        return null;
    }

    private record ReturnRequest(Item item, TrackedShulker shulker) {
    }

    private static final class TrackedShulker {
        private final Item boxItem;
        private List<ItemStack> contents;
        private int lastKnownSlot = -1;

        private TrackedShulker(Item boxItem, List<ItemStack> contents) {
            this.boxItem = boxItem;
            this.contents = contents;
        }

        private Item boxItem() {
            return boxItem;
        }

        private List<ItemStack> contents() {
            return contents;
        }

        private int lastKnownSlot() {
            return lastKnownSlot;
        }

        private void setLastKnownSlot(int slot) {
            lastKnownSlot = slot;
        }

        private void updateContents(List<ItemStack> newContents) {
            contents = newContents;
        }
    }
}
