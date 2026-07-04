package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility layer for TakeItOut (mod ID: takeitout).
 * <p>
 * All interactions use reflection so this class compiles and runs safely
 * when TakeItOut is not installed.
 */
public class TakeItOutCompat {
    private static final String CLIENT = "net.maxbel.takeitout.client";
    private static final String MAIN   = "net.maxbel.takeitout";

    @Nullable private static Method getShulkerWithStackMethod;
    @Nullable private static Method getSlotWithStackMethod;
    @Nullable private static Method getInventoryFromShulkerMethod;
    @Nullable private static Field  awaitingStackField;
    @Nullable private static Constructor<?> payloadConstructor;

    private static boolean initAttempted = false;

    private static void init() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            Class<?> util = Class.forName(CLIENT + ".Util");
            getShulkerWithStackMethod = util.getMethod("getShulkerWithStack", Inventory.class, ItemStack.class);
            getSlotWithStackMethod     = util.getMethod("getSlotWithStack", Container.class, ItemStack.class);

            Class<?> inv = Class.forName(CLIENT + ".ItemStackInventory");
            getInventoryFromShulkerMethod = inv.getMethod("getInventoryFromShulker", ItemStack.class);

            Class<?> client = Class.forName(CLIENT + ".TakeitoutClient");
            awaitingStackField = client.getField("awaitingStack");

            Class<?> payload = Class.forName(MAIN + ".Takeitout$GetShulkerStackPayload");
            payloadConstructor = payload.getConstructor(int.class, int.class);
        } catch (Exception ignored) {
            clear();
        }
    }

    private static void clear() {
        getShulkerWithStackMethod = null;
        getSlotWithStackMethod = null;
        getInventoryFromShulkerMethod = null;
        awaitingStackField = null;
        payloadConstructor = null;
    }

    /** True while TakeItOut is waiting for a server reply. */
    public static boolean isAwaitingItem() {
        if (!ModUtils.isTakeItOutLoaded()) return false;
        init();
        if (awaitingStackField == null) return false;
        try {
            ItemStack s = (ItemStack) awaitingStackField.get(null);
            return s != null && !s.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * If any required item lives inside a shulker, ask the TakeItOut server
     * handler to extract it and return true (caller should await).
     */
    public static boolean tryExtract(LocalPlayer player, Item... items) {
        if (!ModUtils.isTakeItOutLoaded()) return false;
        if (items == null || items.length == 0) return false;
        if (isAwaitingItem()) return false;

        init();
        if (payloadConstructor == null) return false;

        try {
            Inventory inv = player.getInventory();

            for (Item item : items) {
                if (item == null) continue;
                ItemStack needed = new ItemStack(item);

                // Skip if already in inventory
                if (inv.findSlotMatchingItem(needed) != -1) continue;

                int shulkerSlot = (int) getShulkerWithStackMethod.invoke(null, inv, needed);
                if (shulkerSlot == -1) continue;

                ItemStack shulkerStack = inv.getItem(shulkerSlot);
                Object shulkerInv = getInventoryFromShulkerMethod.invoke(null, shulkerStack);
                int slotInShulker = (int) getSlotWithStackMethod.invoke(null, shulkerInv, needed);
                if (slotInShulker == -1) continue;

                // Build payload and send
                CustomPacketPayload payload = (CustomPacketPayload) payloadConstructor.newInstance(slotInShulker, shulkerSlot);
                ClientPlayNetworking.send(payload);

                // Block until server replies
                awaitingStackField.set(null, needed);
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
