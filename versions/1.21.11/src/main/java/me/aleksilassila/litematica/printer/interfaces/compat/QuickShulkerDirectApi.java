package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Reflection-only boundary for the optional Quick Shulker direct protocol. */
final class QuickShulkerDirectApi {
    private static final int MAX_TRANSFER_AMOUNT = 4096;

    private final Method available;
    private final Method submit;
    private final Method handleDone;
    private final Method handleResult;
    private final Method resultMovedCount;
    private final Method sameItemFilter;
    private final Constructor<?> playerSlot;
    private final Constructor<?> shulkerSlot;
    private final Constructor<?> request;

    private boolean broken;

    private QuickShulkerDirectApi(Method available,
                                  Method submit,
                                  Method handleDone,
                                  Method handleResult,
                                  Method resultMovedCount,
                                  Method sameItemFilter,
                                  Constructor<?> playerSlot,
                                  Constructor<?> shulkerSlot,
                                  Constructor<?> request) {
        this.available = available;
        this.submit = submit;
        this.handleDone = handleDone;
        this.handleResult = handleResult;
        this.resultMovedCount = resultMovedCount;
        this.sameItemFilter = sameItemFilter;
        this.playerSlot = playerSlot;
        this.shulkerSlot = shulkerSlot;
        this.request = request;
    }

    static QuickShulkerDirectApi load() {
        if (!ModUtils.isQuickShulkerLoaded()) return null;
        try {
            ClassLoader loader = QuickShulkerDirectApi.class.getClassLoader();
            Class<?> client = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.client.ShulkerTransferClient");
            Class<?> handle = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.client.ShulkerTransferHandle");
            Class<?> result = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.ShulkerTransferResult");
            Class<?> endpoint = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.ShulkerTransferEndpoint");
            Class<?> player = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.PlayerSlotEndpoint");
            Class<?> shulker = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.CarriedShulkerSlotEndpoint");
            Class<?> filter = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.ShulkerItemFilter");
            Class<?> transfer = load(loader,
                    "net.kyrptonaught.quickshulker.api.shulker.ShulkerTransferRequest");

            return new QuickShulkerDirectApi(
                    client.getMethod("isAvailable"),
                    client.getMethod("submit", transfer),
                    handle.getMethod("isDone"),
                    handle.getMethod("resultOrNull"),
                    result.getMethod("movedCount"),
                    filter.getMethod("sameItem", ItemStack.class),
                    player.getConstructor(int.class),
                    shulker.getConstructor(int.class, int.class),
                    transfer.getConstructor(endpoint, endpoint, filter, int.class));
        } catch (ReflectiveOperationException | LinkageError error) {
            return null;
        }
    }

    boolean probeServerCapability() {
        if (broken) return false;
        try {
            return Boolean.TRUE.equals(available.invoke(null));
        } catch (ReflectiveOperationException | LinkageError error) {
            broken = true;
            return false;
        }
    }

    boolean isUsable() {
        return !broken;
    }

    void reset() {
        broken = false;
    }

    Object submitExtract(int boxSlot, int boxItemSlot,
                         int outputSlot, Item item) {
        return submit(boxSlot, boxItemSlot, outputSlot, item, true);
    }

    Object submitReturn(int sourceSlot, int boxSlot,
                        int boxDestinationSlot, Item item) {
        return submit(boxSlot, boxDestinationSlot, sourceSlot, item, false);
    }

    boolean isDone(Object handle) {
        try {
            return Boolean.TRUE.equals(handleDone.invoke(handle));
        } catch (ReflectiveOperationException | LinkageError error) {
            broken = true;
            return true;
        }
    }

    int movedCount(Object handle) {
        try {
            Object result = handleResult.invoke(handle);
            return result == null ? 0 : (int) resultMovedCount.invoke(result);
        } catch (ReflectiveOperationException | LinkageError error) {
            broken = true;
            return 0;
        }
    }

    private Object submit(int boxSlot, int boxItemSlot, int playerInventorySlot,
                          Item item, boolean extracting) {
        if (broken) return null;
        try {
            Object storageEndpoint = shulkerSlot.newInstance(boxSlot, boxItemSlot);
            Object playerEndpoint = playerSlot.newInstance(playerInventorySlot);
            Object filter = sameItemFilter.invoke(null, new ItemStack(item));
            Object transfer = request.newInstance(
                    extracting ? storageEndpoint : playerEndpoint,
                    extracting ? playerEndpoint : storageEndpoint,
                    filter,
                    MAX_TRANSFER_AMOUNT);
            return submit.invoke(null, transfer);
        } catch (ReflectiveOperationException | LinkageError error) {
            broken = true;
            return null;
        }
    }

    private static Class<?> load(ClassLoader loader, String name)
            throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
}
