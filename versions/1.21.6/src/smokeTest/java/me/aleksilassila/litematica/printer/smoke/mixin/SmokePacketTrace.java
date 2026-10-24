package me.aleksilassila.litematica.printer.smoke.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Passive observations only; this class exists only in the smoke-test JAR. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SmokePacketTrace {
    @Shadow public ServerPlayer player;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Unique private int smokeTraceCount;

    @Unique private void smokeTrace(String event, Object detail) {
        if (!player.level().getServer().isSameThread() || smokeTraceCount++ >= 80) return;
        LoggerFactory.getLogger("SmokePacketTrace").info(
            "SMOKE_PACKET event={} detail={} loaded={} awaitingTeleport={} player={} hand={} target={} floor={} tick={}",
            event, detail, player.hasClientLoaded(), awaitingPositionFromClient, player.position(),
            player.getMainHandItem(), player.level().getBlockState(new BlockPos(1, 64, 0)),
            player.level().getBlockState(new BlockPos(1, 63, 0)), player.tickCount);
    }

    @Inject(method="handleUseItemOn", at=@At("HEAD"))
    private void beforeUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        smokeTrace("USE_HEAD", "seq=" + packet.getSequence() + " hit=" + packet.getHitResult().getBlockPos()
            + " face=" + packet.getHitResult().getDirection() + " at=" + packet.getHitResult().getLocation()
            + " reach=" + player.canInteractWithBlock(packet.getHitResult().getBlockPos(), 1.0));
    }
    @Inject(method="handleUseItemOn", at=@At("RETURN"))
    private void afterUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        smokeTrace("USE_RETURN", packet.getSequence());
    }
    @Inject(method="handleAcceptTeleportPacket", at=@At("HEAD"))
    private void beforeTeleport(ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        smokeTrace("TELEPORT_HEAD", packet.getId());
    }
    @Inject(method="handleAcceptTeleportPacket", at=@At("RETURN"))
    private void afterTeleport(ServerboundAcceptTeleportationPacket packet, CallbackInfo ci) {
        smokeTrace("TELEPORT_RETURN", packet.getId());
    }
    @Inject(method="handleAcceptPlayerLoad", at=@At("HEAD"))
    private void beforeLoaded(ServerboundPlayerLoadedPacket packet, CallbackInfo ci) {
        smokeTrace("LOADED_HEAD", "");
    }
    @Inject(method="handleAcceptPlayerLoad", at=@At("RETURN"))
    private void afterLoaded(ServerboundPlayerLoadedPacket packet, CallbackInfo ci) {
        smokeTrace("LOADED_RETURN", "");
    }
    @Inject(method="handleContainerClick", at=@At("RETURN"))
    private void afterClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        smokeTrace("CLICK_RETURN", packet);
    }
    @Inject(method="handleSetCarriedItem", at=@At("RETURN"))
    private void afterCarried(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        smokeTrace("CARRIED_RETURN", packet.getSlot());
    }
}
