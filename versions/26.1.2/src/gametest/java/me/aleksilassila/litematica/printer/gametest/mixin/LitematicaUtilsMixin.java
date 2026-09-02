package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.TestSchematicRegion;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = LitematicaUtils.class, remap = false)
abstract class LitematicaUtilsMixin {
    @Inject(method = "isSchematicBlock", at = @At("HEAD"), cancellable = true)
    private static void acceptGameTestSchematicRegion(
            BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (TestSchematicRegion.contains(pos)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getSchematicBoxesSnapshot", at = @At("HEAD"), cancellable = true)
    private static void captureGameTestSchematicRegion(
            PrinterBox limit,
            CallbackInfoReturnable<List<PrinterBox>> cir) {
        if (TestSchematicRegion.isActive()) {
            cir.setReturnValue(TestSchematicRegion.snapshotIntersection(limit));
        }
    }
}
