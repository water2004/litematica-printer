package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.FullPrintProfileMetrics;
import me.aleksilassila.litematica.printer.handler.handlers.Print;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.inventory.MaterialPreparation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;

/** Test-only classification of consumer attempts that produce no interaction packet. */
@Mixin(Print.class)
public abstract class PrintConsumerReasonMixin {
    @Redirect(
            method = "executeIteration",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/aleksilassila/litematica/printer/printer/action/Action;getValidSide(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;"))
    private Direction recordValidSide(Action action, ClientLevel level, BlockPos pos) {
        Direction side = action.getValidSide(level, pos);
        if (side == null) FullPrintProfileMetrics.recordNoValidSide();
        return side;
    }

    @Redirect(
            method = "executeIteration",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/aleksilassila/litematica/printer/printer/inventory/MaterialPreparation;prepare(Lnet/minecraft/client/player/LocalPlayer;[Lnet/minecraft/world/item/Item;)Lme/aleksilassila/litematica/printer/printer/inventory/MaterialPreparation$Result;"))
    private MaterialPreparation.Result recordItemSwitch(
            LocalPlayer player, Item[] items) {
        MaterialPreparation.Result result =
                MaterialPreparation.prepare(player, items);
        if (result.isWaiting()) {
            FullPrintProfileMetrics.recordItemSwitchWaiting();
        } else if (result == MaterialPreparation.Result.UNAVAILABLE) {
            FullPrintProfileMetrics.recordItemUnavailable();
        }
        return result;
    }
}
