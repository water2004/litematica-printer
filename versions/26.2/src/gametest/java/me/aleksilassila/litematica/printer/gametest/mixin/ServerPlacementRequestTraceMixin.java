package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.diagnostics.StackBoundaryPlacementTrace;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPlacementRequestTraceMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void begin(ServerboundUseItemOnPacket packet, CallbackInfo callback) {
        StackBoundaryPlacementTrace.begin(player, packet);
    }

    @Inject(method = "handleUseItemOn", at = @At("RETURN"))
    private void end(ServerboundUseItemOnPacket packet, CallbackInfo callback) {
        StackBoundaryPlacementTrace.endRequest();
    }
}
