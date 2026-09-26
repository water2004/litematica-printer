package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedList;
import java.util.List;

/** Screen-free material transactions backed by the Quick Shulker 4 direct protocol. */
final class QuickShulkerDirectBridge {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final QuickShulkerDirectApi API = QuickShulkerDirectApi.load();
    private static final LinkedList<ReturnRequest> RETURNS = new LinkedList<>();

    private static Pending pending;
    private static int cooldown;

    private QuickShulkerDirectBridge() {
    }

    /**
     * Probes once when a play connection starts. Requests never fall back to the legacy path.
     */
    static boolean isAvailable() {
        return API != null && API.isUsable() && API.probeServerCapability();
    }

    static boolean request(LocalPlayer player, Item[] items) {
        if (player == null || items == null || items.length == 0
                || pending != null || cooldown > 0 || API == null || !API.isUsable()) {
            return false;
        }

        Inventory inventory = player.getInventory();
        if (QuickShulkerInventory.isInventoryFull(inventory) && !RETURNS.isEmpty()) {
            return requestReturn(inventory, RETURNS.getFirst());
        }

        int emptySlot = QuickShulkerInventory.findEmptySlot(inventory);
        if (emptySlot < 0) return false;

        for (Item item : items) {
            QuickShulkerInventory.ShulkerSlot source =
                    QuickShulkerInventory.findShulkerSlotWithItem(inventory, item);
            if (source == null) continue;

            Object handle = API.submitExtract(
                    source.inventorySlot(), source.shulkerSlot(), emptySlot, item);
            if (handle == null) return false;

            pending = Pending.extract(handle, source.inventorySlot(), item);
            cooldown = Configs.Print.SHULKER_COOLDOWN.getIntegerValue();
            return true;
        }
        return false;
    }

    static void tick() {
        if (cooldown > 0) cooldown--;

        LocalPlayer player = MC.player;
        if (player == null) {
            reset();
            return;
        }
        if (pending == null || !API.isDone(pending.handle())) return;

        int moved = API.movedCount(pending.handle());
        if (pending.kind() == Kind.EXTRACT && moved > 0) {
            ItemStack host = player.getInventory().getItem(pending.boxSlot());
            if (QuickShulkerInventory.isShulker(host)) {
                RETURNS.addLast(new ReturnRequest(
                        pending.item(),
                        new TrackedShulker(
                                host.getItem(),
                                QuickShulkerInventory.contents(host),
                                pending.boxSlot())));
            }
        } else if (pending.kind() == Kind.RETURN) {
            RETURNS.removeFirstOccurrence(pending.returnRequest());
            cooldown = 0;
        }
        pending = null;
    }

    static boolean isBusy() {
        return pending != null;
    }

    static int cooldown() {
        return cooldown;
    }

    static void reset() {
        pending = null;
        RETURNS.clear();
        cooldown = 0;
        if (API != null) API.reset();
    }

    private static boolean requestReturn(Inventory inventory, ReturnRequest request) {
        int sourceSlot = QuickShulkerInventory.findItem(inventory, request.item());
        if (sourceSlot < 0) {
            RETURNS.removeFirstOccurrence(request);
            return false;
        }

        int boxSlot = Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                ? findReturnShulker(inventory, request)
                : QuickShulkerInventory.findAnyShulker(inventory);
        if (boxSlot < 0) return false;

        int destinationSlot = QuickShulkerInventory.findInsertSlot(
                inventory.getItem(boxSlot), inventory.getItem(sourceSlot));
        if (destinationSlot < 0) return false;

        Object handle = API.submitReturn(
                sourceSlot, boxSlot, destinationSlot, request.item());
        if (handle == null) return false;

        pending = Pending.returning(handle, request);
        cooldown = Configs.Print.SHULKER_COOLDOWN.getIntegerValue();
        return true;
    }

    private static int findReturnShulker(Inventory inventory, ReturnRequest request) {
        TrackedShulker tracked = request.shulker();
        for (int slot = 0; slot < QuickShulkerInventory.storageSize(inventory); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(tracked.boxItem()) && QuickShulkerInventory.sameContents(
                    QuickShulkerInventory.contents(stack), tracked.contents())) {
                return slot;
            }
        }

        int lastSlot = tracked.lastKnownSlot();
        if (lastSlot >= 0 && lastSlot < QuickShulkerInventory.storageSize(inventory)
                && inventory.getItem(lastSlot).is(tracked.boxItem())) {
            return lastSlot;
        }

        for (int slot = 0; slot < QuickShulkerInventory.storageSize(inventory); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!QuickShulkerInventory.isSingleShulker(stack)) continue;
            List<ItemStack> stored = QuickShulkerInventory.contents(stack);
            if (QuickShulkerInventory.findInsertSlot(
                    stack, new ItemStack(request.item())) >= 0
                    && stored.stream().anyMatch(item -> item.is(request.item()))) {
                return slot;
            }
        }
        return -1;
    }

    private enum Kind {
        EXTRACT,
        RETURN
    }

    private record TrackedShulker(Item boxItem, List<ItemStack> contents, int lastKnownSlot) {
    }

    private record ReturnRequest(Item item, TrackedShulker shulker) {
    }

    private record Pending(Kind kind,
                           Object handle,
                           int boxSlot,
                           Item item,
                           ReturnRequest returnRequest) {
        private static Pending extract(Object handle, int boxSlot, Item item) {
            return new Pending(Kind.EXTRACT, handle, boxSlot, item, null);
        }

        private static Pending returning(Object handle, ReturnRequest request) {
            return new Pending(Kind.RETURN, handle, -1, request.item(), request);
        }
    }
}
