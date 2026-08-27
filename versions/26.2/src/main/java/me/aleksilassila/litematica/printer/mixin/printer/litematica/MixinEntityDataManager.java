package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.ServuxHandItemConfirmation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Litematica's existing Servux entity-data channel active while authoritative hand
 * confirmation is enabled, without changing Litematica's persisted entity-sync setting.
 */
@Mixin(value = EntityDataManager.class, remap = false)
public abstract class MixinEntityDataManager {
    @ModifyExpressionValue(
            method = {
                    "onClientTick",
                    "requestMetadata",
                    "receiveServuxMetadata",
                    "requestServuxEntityData"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/malilib/config/options/ConfigBooleanHotkeyed;getBooleanValue()Z"
            )
    )
    private boolean enableEntityRequestsForHandConfirmation(boolean original) {
        return original || Configs.Print.SERVUX_HAND_CONFIRMATION.getBooleanValue();
    }

    @Inject(method = "handleEntityData", at = @At("HEAD"))
    private void receiveAuthoritativePlayerHand(
            int entityId,
            CompoundData data,
            CallbackInfoReturnable<?> cir) {
        ServuxHandItemConfirmation.handleEntityData(entityId, data);
    }
}
