package me.aleksilassila.litematica.printer.audit.mixin;

import me.aleksilassila.litematica.printer.audit.AuditPacketTrace;
import me.aleksilassila.litematica.printer.audit.PrinterAuditServer;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class AuditServerPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At("RETURN"))
    private void receivedCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        AuditPacketTrace.log("SERVER_CARRIED", AuditPacketTrace.packet(packet) + " " + AuditPacketTrace.hand(player));
    }

    @Inject(method = "handleContainerClick", at = @At("RETURN"))
    private void receivedClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        AuditPacketTrace.log("SERVER_CLICK", AuditPacketTrace.packet(packet) + " " + AuditPacketTrace.hand(player));
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void beforeUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!AuditPacketTrace.ENABLED || !player.level().getServer().isSameThread()) return;
        AuditPacketTrace.log("SERVER_USE_BEFORE", detail(packet));
    }

    @Inject(method = "handleUseItemOn", at = @At("RETURN"))
    private void afterUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (AuditPacketTrace.ENABLED) AuditPacketTrace.log("SERVER_USE_AFTER", detail(packet));
    }

    private String detail(ServerboundUseItemOnPacket packet) {
        var hit = packet.getHitResult();
        return AuditPacketTrace.packet(packet) + " " + AuditPacketTrace.hand(player)
                + " clicked=" + PrinterAuditServer.describeTarget(player.level(), hit.getBlockPos())
                + " adjacent=" + PrinterAuditServer.describeTarget(player.level(), hit.getBlockPos().relative(hit.getDirection()));
    }
}
