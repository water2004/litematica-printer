package me.aleksilassila.litematica.printer.smoke.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exercise the real mouse -> screen -> button path in the isolated test JAR. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccess {
    @Invoker("onMove")
    void printerAuditMove(long window, double x, double y);

    @Invoker("onButton")
    void printerAuditButton(long window, MouseButtonInfo button, int action);
}
