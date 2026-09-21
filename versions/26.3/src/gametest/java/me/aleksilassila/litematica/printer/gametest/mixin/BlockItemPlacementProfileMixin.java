package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.FullPrintProfileMetrics;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemPlacementProfileMixin {
    @Inject(method = "place", at = @At("HEAD"))
    private void beginPrinterPlacement(
            BlockPlaceContext context,
            CallbackInfoReturnable<InteractionResult> callback) {
        FullPrintProfileMetrics.beginBlockItemPlacement(context.getLevel());
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void finishPrinterPlacement(
            BlockPlaceContext context,
            CallbackInfoReturnable<InteractionResult> callback) {
        FullPrintProfileMetrics.endBlockItemPlacement(callback.getReturnValue());
    }
}
