package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Single optional-dependency boundary for all Quick Shulker material access.
 * Protocol selection is intentionally hidden from printer business logic.
 */
public final class QuickShulkerCompat {
    private static Path selectedPath = Path.UNRESOLVED;

    private QuickShulkerCompat() {
    }

    /** Selects one Quick Shulker implementation for the lifetime of this play connection. */
    public static void onPlayJoin() {
        QuickShulkerDirectBridge.reset();
        selectedPath = QuickShulkerDirectBridge.isAvailable()
                ? Path.DIRECT
                : Path.LEGACY;
    }

    public static void onDisconnect() {
        selectedPath = Path.UNRESOLVED;
        QuickShulkerDirectBridge.reset();
    }

    public static boolean requestShulkerItem(LocalPlayer player, Item[] items) {
        if (!Configs.Print.USE_QUICK_SHULKER.getBooleanValue()) return false;
        ShulkerSource source = (ShulkerSource) Configs.Print.SHULKER_SOURCE.getOptionListValue();
        if (source == ShulkerSource.MOD) {
            return switch (selectedPath()) {
                case DIRECT -> QuickShulkerDirectBridge.request(player, items);
                case LEGACY -> QuickShulkerUtils.requestLegacyShulkerItem(player, items);
                case UNRESOLVED -> false;
            };
        }
        return QuickShulkerUtils.requestLegacyShulkerItem(player, items);
    }

    public static void tick() {
        QuickShulkerDirectBridge.tick();
        QuickShulkerUtils.tick();
    }

    public static boolean isBusy() {
        return QuickShulkerDirectBridge.isBusy() || QuickShulkerUtils.isBusy();
    }

    public static int getCooldown() {
        return Math.max(QuickShulkerDirectBridge.cooldown(),
                QuickShulkerUtils.getShulkerCooldown());
    }

    public static boolean isLegacyOpenHandler() {
        return QuickShulkerUtils.isOpenHandler();
    }

    public static void handleLegacyContainerContent() {
        QuickShulkerUtils.switchFromShulker();
    }

    public static boolean openLegacyShulker(ItemStack stack, int inventorySlot) {
        return QuickShulkerLegacyBridge.open(stack, inventorySlot);
    }

    private static Path selectedPath() {
        if (selectedPath == Path.UNRESOLVED) onPlayJoin();
        return selectedPath;
    }

    private enum Path {
        UNRESOLVED,
        DIRECT,
        LEGACY
    }
}
