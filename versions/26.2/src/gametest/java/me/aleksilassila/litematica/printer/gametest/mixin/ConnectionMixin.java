package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.NetworkFaultController;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static void dropAnyInboundPacket(
            Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (NetworkFaultController.dropRandomPacket(packet)) ci.cancel();
    }
}
