package me.aleksilassila.litematica.printer.gametest.mixin;

import me.aleksilassila.litematica.printer.gametest.FullPrintProfileMetrics;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.handler.handlers.Print;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only accounting of active consumer work; inter-tick throttling is excluded. */
@Mixin(Module.class)
public abstract class ModuleConsumerProfileMixin {
    @Unique
    private long litematicaPrinter$consumerStarted;
    @Unique
    private long litematicaPrinter$validationStarted;
    @Unique
    private long litematicaPrinter$executionStarted;

    @Inject(method = "executePooledPhase", at = @At("HEAD"))
    private void beginConsumerPhase(int maxExecs, CallbackInfo callback) {
        if (litematicaPrinter$isProfiledPrint()) {
            litematicaPrinter$consumerStarted = System.nanoTime();
        }
    }

    @Inject(method = "executePooledPhase", at = @At("RETURN"))
    private void finishConsumerPhase(int maxExecs, CallbackInfo callback) {
        long started = litematicaPrinter$consumerStarted;
        litematicaPrinter$consumerStarted = 0L;
        if (started != 0L) {
            FullPrintProfileMetrics.recordConsumerPhase(
                    System.nanoTime() - started);
        }
    }

    @Inject(method = "prepareMatchingPooledJob", at = @At("HEAD"))
    private void beginConsumerValidation(
            BlockPos pos,
            TransactionKey expectedKey,
            CallbackInfoReturnable<Boolean> callback) {
        if (litematicaPrinter$isProfiledPrint()) {
            litematicaPrinter$validationStarted = System.nanoTime();
        }
    }

    @Inject(method = "prepareMatchingPooledJob", at = @At("RETURN"))
    private void finishConsumerValidation(
            BlockPos pos,
            TransactionKey expectedKey,
            CallbackInfoReturnable<Boolean> callback) {
        long started = litematicaPrinter$validationStarted;
        litematicaPrinter$validationStarted = 0L;
        if (started != 0L) {
            FullPrintProfileMetrics.recordConsumerValidation(
                    System.nanoTime() - started,
                    callback.getReturnValueZ());
        }
    }

    @Inject(method = "executePreparedPooledJob", at = @At("HEAD"))
    private void beginConsumerExecution(
            BlockPos pos,
            TransactionKey expectedKey,
            CallbackInfo callback) {
        if (litematicaPrinter$isProfiledPrint()) {
            litematicaPrinter$executionStarted = System.nanoTime();
        }
    }

    @Inject(method = "executePreparedPooledJob", at = @At("RETURN"))
    private void finishConsumerExecution(
            BlockPos pos,
            TransactionKey expectedKey,
            CallbackInfo callback) {
        long started = litematicaPrinter$executionStarted;
        litematicaPrinter$executionStarted = 0L;
        if (started != 0L) {
            FullPrintProfileMetrics.recordConsumerExecution(
                    System.nanoTime() - started);
        }
    }

    @Unique
    private boolean litematicaPrinter$isProfiledPrint() {
        return FullPrintProfileMetrics.isActive()
                && (Object) this instanceof Print;
    }
}
