package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.NetworkFaultController;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void dropCarriedItemChange(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (NetworkFaultController.dropIfArmed(
                NetworkFaultController.Fault.DROP_CARRIED_ITEM_UNTIL_USE_ITEM_ON)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void dropUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        NetworkFaultController.finishCarriedItemLossBurst();
        if (NetworkFaultController.dropIfArmed(
                NetworkFaultController.Fault.DROP_USE_ITEM_ON_BURST)) {
            ci.cancel();
        }
    }
}
