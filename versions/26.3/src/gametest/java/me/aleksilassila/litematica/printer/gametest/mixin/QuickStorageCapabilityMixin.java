package me.aleksilassila.litematica.printer.gametest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Simulates a new client API connected to a server that does not advertise the protocol. */
@Pseudo
@Mixin(targets = "net.kyrptonaught.quickshulker.api.shulker.client.ShulkerTransferClient", remap = false)
public abstract class QuickStorageCapabilityMixin {
    @Inject(method = "isAvailable", at = @At("HEAD"), cancellable = true, require = 0)
    private static void litematicaPrinter$forceUnavailable(
            CallbackInfoReturnable<Boolean> cir) {
        if ("direct-fallback".equals(System.getProperty(
                "litematica-printer.gametest.quickshulker", "none"))) {
            cir.setReturnValue(false);
        }
    }
}
