package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.QuickShulkerUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "handleSetHealth", at = @At("RETURN"))
    private void injectHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (packet.getHealth() == 0 && Configs.Core.AUTO_DISABLE_PRINTER.getBooleanValue() && Configs.Core.WORK_SWITCH.getBooleanValue()) {
            MessageUtils.setOverlayMessage(I18n.AUTO_DISABLE_NOTICE.getName());
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
        }
    }

    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (QuickShulkerUtils.isOpenHandler()) {
            QuickShulkerUtils.switchFromShulker();
        }
    }

//    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
//    private void onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
//        BreakUtils.INSTANCE.confirmServerBlockUpdate(packet.getPos());
//    }
//
//    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
//    private void onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
//        packet.runUpdates((pos, state) -> BreakUtils.INSTANCE.confirmServerBlockUpdate(pos));
//    }
}