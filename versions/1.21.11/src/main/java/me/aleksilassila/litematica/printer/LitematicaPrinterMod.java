package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.event.InitializationHandler;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class LitematicaPrinterMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> QuickShulkerCompat.onPlayJoin());
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> QuickShulkerCompat.onDisconnect());
    }
}
