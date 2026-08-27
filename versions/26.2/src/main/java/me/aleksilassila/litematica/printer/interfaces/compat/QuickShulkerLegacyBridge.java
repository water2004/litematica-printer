package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/** Isolates the original screen-opening Quick Shulker API. */
final class QuickShulkerLegacyBridge {
    private static boolean resolved;
    private static Method checkAndSend;

    private QuickShulkerLegacyBridge() {
    }

    static boolean open(ItemStack stack, int inventorySlot) {
        resolve();
        if (checkAndSend == null) return false;
        try {
            return Boolean.TRUE.equals(checkAndSend.invoke(null, stack, inventorySlot));
        } catch (ReflectiveOperationException | LinkageError error) {
            checkAndSend = null;
            return false;
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        if (!ModUtils.isQuickShulkerLoaded()) return;
        try {
            Class<?> clientUtil = Class.forName(
                    "net.kyrptonaught.quickshulker.client.ClientUtil");
            checkAndSend = clientUtil.getMethod(
                    "CheckAndSend", ItemStack.class, int.class);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            checkAndSend = null;
        }
    }
}
