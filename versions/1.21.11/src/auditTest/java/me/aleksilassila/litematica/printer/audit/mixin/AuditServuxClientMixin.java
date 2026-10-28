package me.aleksilassila.litematica.printer.audit.mixin;
import me.aleksilassila.litematica.printer.audit.AuditServuxCounters;
import me.aleksilassila.litematica.printer.audit.AuditPacketTrace;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import me.aleksilassila.litematica.printer.utils.ServuxHandItemConfirmation;
import me.aleksilassila.litematica.printer.core.network.HandConfirmationGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Record responses and accepted confirmation checks without changing decisions. */
@Mixin(value = ServuxHandItemConfirmation.class, remap = false)
public class AuditServuxClientMixin {
    @Inject(method = "handleEntityData", at = @At("HEAD"))
    private static void observeResponse(int entityId, CompoundTag data, CallbackInfo ci) {
        if (ServuxHandItemConfirmation.isEnabled()) AuditServuxCounters.ENTITY_RESPONSES.incrementAndGet();
        if (AuditPacketTrace.ENABLED) AuditPacketTrace.log("SERVUX_RESPONSE",
                "entity=" + entityId + " " + AuditPacketTrace.authoritativeHand(data));
    }
    @Inject(method = "verify", at = @At("RETURN"))
    private static void observeConfirmation(LocalPlayer player, Item[] accepted,
            CallbackInfoReturnable<HandConfirmationGate.Status> cir) {
        if (ServuxHandItemConfirmation.isEnabled() && cir.getReturnValue() == HandConfirmationGate.Status.CONFIRMED)
            AuditServuxCounters.CONFIRMED.incrementAndGet();
        if (AuditPacketTrace.ENABLED) AuditPacketTrace.log("CLIENT_VERIFY",
                "status=" + cir.getReturnValue() + " " + AuditPacketTrace.hand(player)
                        + " accepted=" + java.util.Arrays.toString(accepted));
    }
}
