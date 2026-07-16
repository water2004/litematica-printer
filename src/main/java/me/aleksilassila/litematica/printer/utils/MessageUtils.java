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
        client.gui.setOverlayMessage(message, bl);
    }

    public static void addMessage(Component message) {
        //#if MC >= 260100
        //$$ client.gui.getChat().addClientSystemMessage(message);
        //#else
        client.gui.getChat().addMessage(message);
        //#endif
    }

    public static void setOverlayMessage(Component message) {
        client.gui.setOverlayMessage(message, false);
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
        //#if MC > 11802
        return Component.translatable(key);
        //#else
        //$$ return new net.minecraft.network.chat.TranslatableComponent(key);
        //#endif
    }

    public static MutableComponent translatable(String key, Object... objects) {
        //#if MC > 11802
        return Component.translatable(key, objects);
        //#else
        //$$ return new net.minecraft.network.chat.TranslatableComponent(key, objects);
        //#endif
    }

    public static MutableComponent literal(String text) {
        //#if MC > 11802
        return Component.literal(text);
        //#else
        //$$ return new net.minecraft.network.chat.TextComponent(text);
        //#endif
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
