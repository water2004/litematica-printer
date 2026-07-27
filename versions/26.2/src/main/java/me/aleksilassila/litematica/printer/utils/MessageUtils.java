package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

public class MessageUtils {

    public static final Minecraft client = Minecraft.getInstance();

    public static void setOverlayMessage(Component message, boolean bl) {
        client.gui.hud.setOverlayMessage(message, bl);
    }

    public static void addMessage(Component message) {
        client.gui.hud.getChat().addClientSystemMessage(message);
    }

    public static void setOverlayMessage(Component message) {
        client.gui.hud.setOverlayMessage(message, false);
    }

    // 扩展方法，普通字符串形式, 但并不建议使用, 因为没有做I18n
    public static void setOverlayMessage(String message) {
        setOverlayMessage(MessageUtils.literal(message));
    }

    // 扩展方法，普通字符串形式, 但并不建议使用, 因为没有做I18n
    public static void addMessage(String message) {
        addMessage(MessageUtils.literal(message));
    }

    public final static MutableComponent EMPTY = literal("");

    public static MutableComponent translatable(String key) {
        return Component.translatable(key);
    }

    public static MutableComponent translatable(String key, Object... objects) {
        return Component.translatable(key, objects);
    }

    public static MutableComponent literal(String text) {
        return Component.literal(text);
    }

    public static MutableComponent nullToEmpty(@Nullable String string) {
        return string != null ? literal(string) : EMPTY;
    }

    public static void debugMessage(String message) {
        if (Configs.Core.DEBUG_OUTPUT.getBooleanValue()) {
            addMessage(literal(message));
        }
    }

    public static void debugLog(String message) {
        if (Configs.Core.DEBUG_OUTPUT.getBooleanValue()) {
            Reference.LOGGER.info(message);
        }
    }
}
