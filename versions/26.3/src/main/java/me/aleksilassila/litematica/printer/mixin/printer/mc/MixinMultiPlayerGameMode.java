package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.mixin.extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = MultiPlayerGameMode.class, priority = 1020)
public abstract class MixinMultiPlayerGameMode implements MultiPlayerGameModeExtension {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    protected abstract void ensureHasSentCarriedItem();

    @Shadow
    public abstract InteractionResult useItemOn(
            LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult);

    @Override
    public void litematica_printer$startPrediction(PredictiveAction predictiveAction) {
        PacketUtils.sendPacket(predictiveAction);
    }

    @Override
    public InteractionResult litematica_printer$useItemOn(
            boolean localPrediction, InteractionHand hand, BlockHitResult blockHit) {
        if (localPrediction) {
            return useItemOn(minecraft.player, hand, blockHit);
        }

        ensureHasSentCarriedItem();
        if (!minecraft.level.getWorldBorder().isWithinBounds(blockHit.getBlockPos())) {
            return InteractionResult.FAIL;
        }

        litematica_printer$startPrediction(
                sequence -> new ServerboundUseItemOnPacket(hand, blockHit, sequence));
        return InteractionResult.PASS;
    }
}
