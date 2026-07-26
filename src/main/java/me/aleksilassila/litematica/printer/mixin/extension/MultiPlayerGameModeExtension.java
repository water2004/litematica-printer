package me.aleksilassila.litematica.printer.mixin.extension;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

@SuppressWarnings("UnusedReturnValue")
public interface MultiPlayerGameModeExtension {
    InteractionResult litematica_printer$useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit);

    void litematica_printer$startPrediction(PredictiveAction predictiveAction);

    @FunctionalInterface
    interface PredictiveAction {
        Packet<ServerGamePacketListener> predict(int sequence);
    }
}
