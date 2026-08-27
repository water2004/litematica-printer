package me.aleksilassila.litematica.printer.interfaces.compat;

import fi.dy.masa.malilib.util.InventoryUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** Screen-free material access backed by Quick Shulker's direct protocol. */
final class QuickShulkerDirectBridge {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Api API = Api.load();
    private static final LinkedList<ReturnRequest> RETURNS = new LinkedList<>();

    private static Pending pending;
    private static int cooldown;
    private static boolean broken;

    private QuickShulkerDirectBridge() {
    }

    static boolean isAvailable() {
        return !broken && API != null && ModUtils.isQuickShulkerLoaded()
                && API.isServerCapable();
    }

    static boolean request(LocalPlayer player, Item[] items) {
        if (player == null || items == null || items.length == 0
                || pending != null || cooldown > 0 || !isAvailable()) {
            return false;
        }

        Inventory inventory = player.getInventory();
        if (isInventoryFull(inventory) && !RETURNS.isEmpty()) {
            return requestReturn(inventory, RETURNS.getFirst());
        }

        int emptySlot = findEmptySlot(inventory);
        if (emptySlot < 0) return false;
        for (Item item : items) {
            int boxSlot = findShulkerWithItem(inventory, item);
            if (boxSlot < 0) continue;
            ItemStack host = inventory.getItem(boxSlot);
            Object handle = API.submitExtract(boxSlot, host, emptySlot, item);
            if (handle == null) return false;
            pending = Pending.extract(handle, boxSlot, item);
            cooldown = Configs.Print.SHULKER_COOLDOWN.getIntegerValue();
            return true;
        }
        return false;
    }

    static void tick() {
        if (cooldown > 0) cooldown--;
        LocalPlayer player = MC.player;
        if (player == null) {
            pending = null;
            RETURNS.clear();
            cooldown = 0;
            return;
        }
        if (pending == null || !API.isDone(pending.handle())) return;

        int moved = API.movedCount(pending.handle());
        API.forget(pending.handle());
        if (pending.kind() == Kind.EXTRACT && moved > 0) {
            ItemStack host = player.getInventory().getItem(pending.boxSlot());
            if (isShulker(host)) {
                RETURNS.addLast(new ReturnRequest(
                        pending.item(),
                        new TrackedShulker(host.getItem(), contents(host), pending.boxSlot())));
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
        broken = false;
    }

    private static boolean requestReturn(Inventory inventory, ReturnRequest request) {
        int sourceSlot = findItem(inventory, request.item());
        if (sourceSlot < 0) {
            RETURNS.removeFirstOccurrence(request);
            return false;
        }

        int boxSlot = Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                ? findReturnShulker(inventory, request)
                : findAnyShulker(inventory);
        if (boxSlot < 0) return false;

        Object handle = API.submitReturn(
                sourceSlot, boxSlot, inventory.getItem(boxSlot), request.item());
        if (handle == null) return false;
        pending = Pending.returning(handle, boxSlot, request);
        cooldown = Configs.Print.SHULKER_COOLDOWN.getIntegerValue();
        return true;
    }

    private static int findEmptySlot(Inventory inventory) {
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static int findItem(Inventory inventory, Item item) {
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static int findShulkerWithItem(Inventory inventory, Item item) {
        for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isShulker(stack) && stack.getCount() == 1
                    && contents(stack).stream().anyMatch(stored -> stored.is(item))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findReturnShulker(Inventory inventory, ReturnRequest request) {
        TrackedShulker tracked = request.shulker();
        for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(tracked.boxItem()) && sameContents(contents(stack), tracked.contents())) {
                return slot;
            }
        }

        int lastSlot = tracked.lastKnownSlot();
        if (lastSlot >= 9 && lastSlot < Math.min(36, inventory.getContainerSize())
                && inventory.getItem(lastSlot).is(tracked.boxItem())) {
            return lastSlot;
        }

        for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!isShulker(stack) || stack.getCount() != 1) continue;
            List<ItemStack> stored = contents(stack);
            if (stored.size() < 27
                    && stored.stream().anyMatch(item -> item.is(request.item()))) {
                return slot;
            }
        }
        return -1;
    }

    private static int findAnyShulker(Inventory inventory) {
        for (int slot = 9; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isShulker(stack) && stack.getCount() == 1) return slot;
        }
        return -1;
    }

    private static boolean isInventoryFull(Inventory inventory) {
        return findEmptySlot(inventory) < 0;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                .contains("shulker_box");
    }

    private static List<ItemStack> contents(ItemStack stack) {
        NonNullList<ItemStack> stored = InventoryUtils.getStoredItems(stack, -1);
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : stored) {
            if (!item.isEmpty()) result.add(item.copy());
        }
        return result;
    }

    private static boolean sameContents(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) return false;
        boolean[] matched = new boolean[second.size()];
        for (ItemStack item : first) {
            boolean found = false;
            for (int i = 0; i < second.size(); i++) {
                if (!matched[i] && InventoryUtils.areStacksEqual(item, second.get(i))) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private enum Kind { EXTRACT, RETURN }

    private record TrackedShulker(Item boxItem, List<ItemStack> contents, int lastKnownSlot) {
    }

    private record ReturnRequest(Item item, TrackedShulker shulker) {
    }

    private record Pending(Kind kind, Object handle, int boxSlot, Item item,
                           ReturnRequest returnRequest) {
        private static Pending extract(Object handle, int boxSlot, Item item) {
            return new Pending(Kind.EXTRACT, handle, boxSlot, item, null);
        }

        private static Pending returning(Object handle, int boxSlot, ReturnRequest request) {
            return new Pending(Kind.RETURN, handle, boxSlot, request.item(), request);
        }
    }

    private record Api(Method available,
                       Method submit,
                       Method handleDone,
                       Method handleResult,
                       Method resultMovedCount,
                       Method forget,
                       Method exactIndex,
                       Method anySlot,
                       Method emptySlot,
                       Method emptyExactSlot,
                       Method nonEmptyExactSlot,
                       Method itemMatcher,
                       Constructor<?> storageSelector,
                       Constructor<?> carriedStorage,
                       Constructor<?> playerInventory,
                       Constructor<?> transferLimit,
                       Constructor<?> transferSpec) {
        private static Api load() {
            if (!ModUtils.isQuickShulkerLoaded()) return null;
            try {
                ClassLoader loader = QuickShulkerDirectBridge.class.getClassLoader();
                Class<?> client = Class.forName(
                        "net.kyrptonaught.quickshulker.client.api.QuickStorageClient", false, loader);
                Class<?> handle = Class.forName(
                        "net.kyrptonaught.quickshulker.client.api.TransferHandle", false, loader);
                Class<?> result = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.TransferResult", false, loader);
                Class<?> endpoint = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.TransferEndpoint", false, loader);
                Class<?> index = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.IndexSelector", false, loader);
                Class<?> slots = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.SlotSelector", false, loader);
                Class<?> matcher = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.StackMatcher", false, loader);
                Class<?> selector = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.StorageSelector", false, loader);
                Class<?> carried = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.CarriedStorageEndpoint", false, loader);
                Class<?> player = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.PlayerInventoryEndpoint", false, loader);
                Class<?> limit = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.TransferLimit", false, loader);
                Class<?> spec = Class.forName(
                        "net.kyrptonaught.quickshulker.api.storage.TransferSpec", false, loader);

                return new Api(
                        client.getMethod("isAvailable"),
                        client.getMethod("submit", spec),
                        handle.getMethod("isDone"),
                        handle.getMethod("result"),
                        result.getMethod("movedCount"),
                        client.getMethod("forget", handle),
                        index.getMethod("exact", int.class),
                        slots.getMethod("any"),
                        slots.getMethod("empty"),
                        slots.getMethod("empty", int.class),
                        slots.getMethod("nonEmpty", int.class),
                        matcher.getMethod("item", ItemStack.class),
                        selector.getConstructor(index, matcher),
                        carried.getConstructor(selector, slots),
                        player.getConstructor(slots),
                        limit.getConstructor(int.class, int.class, int.class),
                        spec.getConstructor(endpoint, endpoint, matcher, limit));
            } catch (ReflectiveOperationException | LinkageError error) {
                return null;
            }
        }

        private boolean isServerCapable() {
            try {
                return Boolean.TRUE.equals(available.invoke(null));
            } catch (ReflectiveOperationException | LinkageError error) {
                broken = true;
                return false;
            }
        }

        private Object submitExtract(int boxSlot, ItemStack host, int outputSlot, Item item) {
            return submit(boxSlot, host, outputSlot, item, true);
        }

        private Object submitReturn(int sourceSlot, int boxSlot, ItemStack host, Item item) {
            return submit(boxSlot, host, sourceSlot, item, false);
        }

        private Object submit(int boxSlot, ItemStack host, int playerSlot,
                              Item item, boolean extracting) {
            try {
                Object hostMatcher = itemMatcher.invoke(null, host);
                Object storage = storageSelector.newInstance(
                        exactIndex.invoke(null, boxSlot), hostMatcher);
                Object storageEndpoint = carriedStorage.newInstance(
                        storage, extracting ? anySlot.invoke(null) : emptySlot.invoke(null));
                Object playerEndpoint = playerInventory.newInstance(
                        extracting
                                ? emptyExactSlot.invoke(null, playerSlot)
                                : nonEmptyExactSlot.invoke(null, playerSlot));
                Object stackMatcher = itemMatcher.invoke(null, new ItemStack(item));
                Object limits = transferLimit.newInstance(Integer.MAX_VALUE, 1, 1);
                Object transfer = transferSpec.newInstance(
                        extracting ? storageEndpoint : playerEndpoint,
                        extracting ? playerEndpoint : storageEndpoint,
                        stackMatcher,
                        limits);
                return submit.invoke(null, transfer);
            } catch (ReflectiveOperationException | LinkageError error) {
                broken = true;
                return null;
            }
        }

        private boolean isDone(Object handle) {
            try {
                return Boolean.TRUE.equals(handleDone.invoke(handle));
            } catch (ReflectiveOperationException | LinkageError error) {
                broken = true;
                return true;
            }
        }

        private int movedCount(Object handle) {
            try {
                Object result = handleResult.invoke(handle);
                return result == null ? 0 : (int) resultMovedCount.invoke(result);
            } catch (ReflectiveOperationException | LinkageError error) {
                broken = true;
                return 0;
            }
        }

        private void forget(Object handle) {
            try {
                forget.invoke(null, handle);
            } catch (ReflectiveOperationException | LinkageError error) {
                broken = true;
            }
        }
    }
}
