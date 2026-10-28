package me.aleksilassila.litematica.printer.audit.mixin;
import me.aleksilassila.litematica.printer.audit.AuditServuxCounters;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Pseudo;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Observe only: all packets are still handled by the genuine Servux release. */
@Pseudo
@Mixin(targets = "fi.dy.masa.servux.network.packet.ServuxLitematicaHandler", remap = false)
public class AuditServuxServerMixin {
    @Inject(method = "receivePlayPayload", at = @At("HEAD"), remap = false)
    private void observeServuxRequest(CustomPacketPayload payload, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (!payload.getClass().getName().equals("fi.dy.masa.servux.network.packet.ServuxLitematicaPacket$Payload")) return;
        try {
            Object data = payload.getClass().getMethod("data").invoke(payload);
            String type = String.valueOf(data.getClass().getMethod("getType").invoke(data));
            if (type.equals("PACKET_C2S_METADATA_REQUEST")) AuditServuxCounters.METADATA.incrementAndGet();
            if (type.equals("PACKET_C2S_ENTITY_REQUEST")) AuditServuxCounters.ENTITY_REQUESTS.incrementAndGet();
        } catch (ReflectiveOperationException error) { throw new AssertionError("Cannot observe actual Servux packet", error); }
    }
}
