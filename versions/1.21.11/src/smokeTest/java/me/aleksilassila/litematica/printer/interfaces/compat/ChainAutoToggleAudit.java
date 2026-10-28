package me.aleksilassila.litematica.printer.interfaces.compat;

import me.aleksilassila.litematica.printer.smoke.mixin.MouseHandlerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/** Backport the seven upstream 4.1.2 AUTO-toggle input assertions. */
final class ChainAutoToggleAudit {
    // Ctrl modifier, resulting enabled state, resulting AUTO state.
    private static final boolean[][] STEPS = {
            {true, true, true}, {false, false, false}, {false, true, false},
            {true, true, true}, {true, false, false}, {false, true, false},
            {false, false, false}
    };
    private final Minecraft client;
    private final Class<?> integration;
    private final Object config;
    private final Field enabled;
    private final Field mode;
    private final boolean originalEnabled;
    private final Object originalMode;
    private int step;

    ChainAutoToggleAudit(Minecraft client) throws Exception {
        this.client = client;
        integration = Class.forName("org.edtp.chainveinfabric.client.ChainveinfabricClient");
        config = integration.getField("CONFIG").get(null);
        enabled = config.getClass().getField("isChainVeinEnabled");
        mode = config.getClass().getField("mode");
        originalEnabled = enabled.getBoolean(config);
        originalMode = mode.get(config);
        integration.getMethod("disarmAutoMining").invoke(null);
        enabled.setBoolean(config, false);
        Object mining = java.util.Arrays.stream(mode.getType().getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals("CHAIN_MINE"))
                .findFirst().orElseThrow();
        mode.set(config, mining);
        Class<?> gui = Class.forName("org.edtp.chainveinfabric.client.gui.malilib.GuiChainVein");
        client.setScreen((Screen) gui.getConstructor().newInstance());
        Class<?> tab = Class.forName(gui.getName() + "$Tab");
        var select = gui.getDeclaredMethod("selectTab", tab);
        select.setAccessible(true);
        select.invoke(client.screen, tab.getEnumConstants()[0]);
    }

    boolean tick() throws Exception {
        if (step > 0) {
            boolean[] expected = STEPS[step - 1];
            boolean automatic = (boolean) integration.getMethod("isAutoMiningArmed").invoke(null);
            if (enabled.getBoolean(config) != expected[1] || automatic != expected[2]) {
                throw new AssertionError("Real Chain AUTO click transition " + step
                        + " produced enabled=" + enabled.getBoolean(config) + ", auto=" + automatic);
            }
        }
        if (step == STEPS.length) {
            integration.getMethod("disarmAutoMining").invoke(null);
            enabled.setBoolean(config, originalEnabled);
            mode.set(config, originalMode);
            config.getClass().getMethod("save").invoke(config);
            client.setScreen(null);
            return true;
        }
        Object layout = field(client.screen, "basicLayout");
        Object toggle = field(layout, "toggle");
        double x = ((Number) field(toggle, "x")).doubleValue()
                + ((Number) field(toggle, "width")).doubleValue() / 2;
        double y = ((Number) field(toggle, "y")).doubleValue()
                + ((Number) field(toggle, "height")).doubleValue() / 2;
        var window = client.getWindow();
        var mouse = (MouseHandlerAccess) client.mouseHandler;
        mouse.printerAuditMove(window.handle(), x * window.getScreenWidth() / window.getGuiScaledWidth(),
                y * window.getScreenHeight() / window.getGuiScaledHeight());
        var click = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT,
                STEPS[step][0] ? GLFW.GLFW_MOD_CONTROL : 0);
        mouse.printerAuditButton(window.handle(), click, GLFW.GLFW_PRESS);
        mouse.printerAuditButton(window.handle(), click, GLFW.GLFW_RELEASE);
        step++;
        return false;
    }

    private static Object field(Object instance, String name) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
