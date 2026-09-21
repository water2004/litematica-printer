package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.FullPrintProfileMetrics;
import me.aleksilassila.litematica.printer.handler.handlers.Print;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
                    target = "Lme/aleksilassila/litematica/printer/utils/InventoryUtils;switchToItemsResult(Lnet/minecraft/client/player/LocalPlayer;[Lnet/minecraft/world/item/Item;)Lme/aleksilassila/litematica/printer/utils/InventoryUtils$ItemSwitchResult;"))
    private InventoryUtils.ItemSwitchResult recordItemSwitch(
            LocalPlayer player, Item[] items) {
        InventoryUtils.ItemSwitchResult result =
                InventoryUtils.switchToItemsResult(player, items);
        if (result == InventoryUtils.ItemSwitchResult.WAITING) {
            FullPrintProfileMetrics.recordItemSwitchWaiting();
        } else if (result == InventoryUtils.ItemSwitchResult.UNAVAILABLE) {
            FullPrintProfileMetrics.recordItemUnavailable();
        }
        return result;
    }
}
