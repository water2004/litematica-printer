package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
@Mixin(value = ClientCommonPacketListenerImpl.class)
public class MixinClientCommonPacketListener {
    @Final
    @Shadow
    protected Connection connection;

    @Final
    @Shadow
    protected Minecraft minecraft;

    /**
     * @author BiliXWhite
     * @reason 修改移动视角数据包，以实现欺骗服务器的效果
     */
    @ModifyArg(at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"), method = "send")
    private Packet<?> modifySendPacket(Packet<?> packet) {
        return PacketUtils.getFixedPacket(packet);
    }
}
