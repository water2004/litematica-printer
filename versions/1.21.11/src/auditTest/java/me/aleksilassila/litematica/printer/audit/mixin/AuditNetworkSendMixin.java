package me.aleksilassila.litematica.printer.audit.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import me.aleksilassila.litematica.printer.audit.AuditPacketTrace;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.edtp.networkchaos.internal.NetworkChaosRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NetworkChaosRuntime.class, remap = false)
public class AuditNetworkSendMixin {
    @Inject(method = "intercept", at = @At("RETURN"))
    private static void observeActualDecision(Connection connection, Packet<?> packet,
            ChannelFutureListener listener, Channel channel, boolean flush,
            CallbackInfoReturnable<Boolean> result) {
        if (!AuditPacketTrace.ENABLED) return;
        String detail = AuditPacketTrace.packet(packet);
        if (detail != null) AuditPacketTrace.log(result.getReturnValue()
                ? "CHAOS_INTERCEPTED" : "PASSED_TO_CONNECTION", detail);
        // The current fixture sets both delay and duplication to zero. An
        // intercepted relevant packet is therefore an actual Chaos drop.
    }
}
