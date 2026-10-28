package me.aleksilassila.litematica.printer.audit.mixin;

import me.aleksilassila.litematica.printer.audit.AuditPacketTrace;
import me.aleksilassila.litematica.printer.audit.PrinterAuditServer;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ActionManager.class, remap = false)
public class AuditClientUseMixin {
    @Shadow public BlockPos target;
    @Shadow public Direction side;
    @Inject(method = "sendQueue", at = @At("HEAD"))
    private void observeLocalWorld(LocalPlayer player, CallbackInfoReturnable<ActionManager> result) {
        if (!AuditPacketTrace.ENABLED || target == null || side == null) return;
        AuditPacketTrace.log("CLIENT_USE", AuditPacketTrace.hand(player)
                + " hit=" + target.toShortString() + " face=" + side
                + " clicked=" + PrinterAuditServer.describeTarget(player.level(), target)
                + " adjacent=" + PrinterAuditServer.describeTarget(player.level(), target.relative(side)));
    }
}
