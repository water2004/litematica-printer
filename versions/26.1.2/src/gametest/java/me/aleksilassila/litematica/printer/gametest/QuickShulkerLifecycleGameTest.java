package me.aleksilassila.litematica.printer.gametest;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Regression coverage for Quick Shulker state crossing play connections. */
@SuppressWarnings("UnstableApiUsage")
public final class QuickShulkerLifecycleGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;
        List<String> failures = new ArrayList<>();
        String disconnectFailure = context.computeOnClient(
                client -> disconnectLeakDescription());
        if (disconnectFailure != null) failures.add(disconnectFailure);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var inventory = player.getInventory();
                for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
                    inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
                }
                player.inventoryMenu.sendAllDataToRemote();
            });
            context.waitFor(client -> client.player != null
                    && isInventoryFull(client.player.getInventory()));
            String staleReturnFailure = context.computeOnClient(
                    client -> staleReturnDescription(client.player));
            if (staleReturnFailure != null) failures.add(staleReturnFailure);
        }

        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("; ", failures));
        }
    }

    private static String disconnectLeakDescription() {
        LegacyStateAccess state = LegacyStateAccess.resolve();
        state.seedBusyOperation();

        List<String> leaked;
        try {
            QuickShulkerCompat.onDisconnect();
            leaked = state.leakedState();
        } finally {
            // Keep this reproducer isolated even while its assertion fails on the old implementation.
            state.forceClear();
        }

        return leaked.isEmpty() ? null
                : "Quick Shulker legacy state survived disconnect: "
                + String.join(", ", leaked);
    }

    private static String staleReturnDescription(net.minecraft.client.player.LocalPlayer player) {
        LegacyStateAccess state = LegacyStateAccess.resolve();
        boolean oldUseQuickShulker = Configs.Print.USE_QUICK_SHULKER.getBooleanValue();
        ShulkerSource oldSource = (ShulkerSource) Configs.Print.SHULKER_SOURCE.getOptionListValue();
        boolean oldReturnWhenFull = Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue();
        state.seedStaleReturn();
        try {
            Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
            Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.PLUGIN);
            Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);

            QuickShulkerCompat.requestShulkerItem(player, new Item[]{Items.COBBLESTONE});
            return state.returnQueueSize() == 0 ? null
                    : "legacy path retained a return request whose material and shulker no longer exist";
        } finally {
            Configs.Print.USE_QUICK_SHULKER.setBooleanValue(oldUseQuickShulker);
            Configs.Print.SHULKER_SOURCE.setOptionListValue(oldSource);
            Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(oldReturnWhenFull);
            state.forceClear();
        }
    }

    private static boolean isInventoryFull(net.minecraft.world.entity.player.Inventory inventory) {
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    private record LegacyStateAccess(
            Field openHandler,
            Field cooldown,
            Field requestedItem,
            Field itemsToReturn,
            Field trackedShulkers,
            Field activeReturnRequest,
            Field activeShulker,
            Field deferredCloseTicks,
            Field deferredCloseRetries,
            Constructor<?> trackedShulkerConstructor,
            Constructor<?> returnRequestConstructor) {

        private static LegacyStateAccess resolve() {
            try {
                Class<?> utils = Class.forName(
                        "me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerLegacySession");
                Class<?> trackedShulker = Class.forName(utils.getName() + "$TrackedShulker");
                Class<?> returnRequest = Class.forName(utils.getName() + "$ReturnRequest");
                return new LegacyStateAccess(
                        accessibleField(utils, "openHandler"),
                        accessibleField(utils, "cooldown"),
                        accessibleField(utils, "requestedItem"),
                        accessibleField(utils, "itemsToReturn"),
                        accessibleField(utils, "trackedShulkers"),
                        accessibleField(utils, "activeReturnRequest"),
                        accessibleField(utils, "activeShulker"),
                        accessibleField(utils, "deferredCloseTicks"),
                        accessibleField(utils, "deferredCloseRetries"),
                        accessibleConstructor(trackedShulker, Item.class, List.class),
                        accessibleConstructor(returnRequest, Item.class, trackedShulker));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not inspect Quick Shulker legacy state", error);
            }
        }

        private void seedBusyOperation() {
            forceClear();
            try {
                Object tracked = newTrackedShulker();
                Object request = newReturnRequest(tracked);
                collection(trackedShulkers).add(tracked);
                collection(itemsToReturn).add(request);
                activeShulker.set(null, tracked);
                activeReturnRequest.set(null, request);
                deferredCloseTicks.setInt(null, 2);
                deferredCloseRetries.setInt(null, 1);
                openHandler.setBoolean(null, true);
                cooldown.setInt(null, 7);
                requestedItem.set(null, Items.STONE);
                ModUtils.closeScreen = 2;
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not seed Quick Shulker legacy state", error);
            }
        }

        private void seedStaleReturn() {
            forceClear();
            try {
                Object tracked = newTrackedShulker();
                collection(trackedShulkers).add(tracked);
                collection(itemsToReturn).add(newReturnRequest(tracked));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not seed stale Quick Shulker return", error);
            }
        }

        private Object newTrackedShulker() throws ReflectiveOperationException {
            return trackedShulkerConstructor.newInstance(
                    Items.SHULKER_BOX, List.of(new ItemStack(Items.STONE, 4)));
        }

        private Object newReturnRequest(Object tracked) throws ReflectiveOperationException {
            return returnRequestConstructor.newInstance(Items.STONE, tracked);
        }

        private int returnQueueSize() {
            try {
                return collection(itemsToReturn).size();
            } catch (IllegalAccessException error) {
                throw new AssertionError("Could not read Quick Shulker return queue", error);
            }
        }

        private List<String> leakedState() {
            try {
                List<String> leaked = new ArrayList<>();
                if (openHandler.getBoolean(null)) leaked.add("open handler");
                if (cooldown.getInt(null) != 0) leaked.add("cooldown");
                if (requestedItem.get(null) != null) leaked.add("requested item");
                if (!collection(itemsToReturn).isEmpty()) leaked.add("return queue");
                if (!collection(trackedShulkers).isEmpty()) leaked.add("tracked shulkers");
                if (activeReturnRequest.get(null) != null) leaked.add("active return");
                if (activeShulker.get(null) != null) leaked.add("active shulker");
                if (deferredCloseTicks.getInt(null) != 0) leaked.add("deferred close");
                if (deferredCloseRetries.getInt(null) != 0) leaked.add("close retries");
                if (ModUtils.closeScreen != 0) leaked.add("screen suppression");
                return leaked;
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not read Quick Shulker legacy state", error);
            }
        }

        private void forceClear() {
            try {
                openHandler.setBoolean(null, false);
                cooldown.setInt(null, 0);
                requestedItem.set(null, null);
                collection(itemsToReturn).clear();
                collection(trackedShulkers).clear();
                activeReturnRequest.set(null, null);
                activeShulker.set(null, null);
                deferredCloseTicks.setInt(null, 0);
                deferredCloseRetries.setInt(null, 0);
                ModUtils.closeScreen = 0;
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not clear Quick Shulker legacy test state", error);
            }
        }

        @SuppressWarnings("unchecked")
        private static Collection<Object> collection(Field field) throws IllegalAccessException {
            return (Collection<Object>) field.get(null);
        }

        private static Field accessibleField(Class<?> owner, String name)
                throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static Constructor<?> accessibleConstructor(Class<?> owner, Class<?>... parameters)
                throws NoSuchMethodException {
            Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor;
        }
    }
}
