package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;

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
        QuickShulkerLegacySession.reset();
        selectedPath = QuickShulkerDirectBridge.isAvailable()
                ? Path.DIRECT
                : Path.LEGACY;
    }

    public static void onDisconnect() {
        selectedPath = Path.UNRESOLVED;
        QuickShulkerDirectBridge.reset();
        QuickShulkerLegacySession.reset();
    }

    public static boolean requestShulkerItem(LocalPlayer player, Item[] items) {
        if (!Configs.Print.USE_QUICK_SHULKER.getBooleanValue()
                || player == null || items == null || items.length == 0
                || isBusy() || getCooldown() > 0) {
            return false;
        }

        ShulkerSource source = (ShulkerSource) Configs.Print.SHULKER_SOURCE.getOptionListValue();
        return switch (source) {
            case TAKE_IT_OUT -> TakeItOutCompat.tryExtract(player, items);
            case PLUGIN -> QuickShulkerLegacySession.request(player, items, source);
            case MOD -> switch (selectedPath()) {
                case DIRECT -> QuickShulkerDirectBridge.request(player, items);
                case LEGACY -> QuickShulkerLegacySession.request(player, items, source);
                case UNRESOLVED -> false;
            };
        };
    }

    public static void tick() {
        QuickShulkerDirectBridge.tick();
        QuickShulkerLegacySession.tick();
    }

    public static boolean isBusy() {
        return QuickShulkerDirectBridge.isBusy() || QuickShulkerLegacySession.isBusy();
    }

    public static int getCooldown() {
        return Math.max(QuickShulkerDirectBridge.cooldown(),
                QuickShulkerLegacySession.cooldown());
    }

    public static boolean isLegacyOpenHandler() {
        return QuickShulkerLegacySession.isOpenHandler();
    }

    public static void handleLegacyContainerContent() {
        QuickShulkerLegacySession.handleContainerContent();
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
