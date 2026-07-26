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

    //#if MC > 11802
    @Shadow
    public abstract InteractionResult useItemOn(
            LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult);
    //#else
    //$$ @Shadow
    //$$ public abstract InteractionResult useItemOn(
    //$$         LocalPlayer player, net.minecraft.client.multiplayer.ClientLevel level,
    //$$         InteractionHand hand, BlockHitResult blockHitResult);
    //#endif

    @Override
    public void litematica_printer$startPrediction(PredictiveAction predictiveAction) {
        PacketUtils.sendPacket(predictiveAction);
    }

    @Override
    public InteractionResult litematica_printer$useItemOn(
            boolean localPrediction, InteractionHand hand, BlockHitResult blockHit) {
        if (localPrediction) {
            //#if MC > 11802
            return useItemOn(minecraft.player, hand, blockHit);
            //#else
            //$$ return useItemOn(minecraft.player, minecraft.level, hand, blockHit);
            //#endif
        }

        ensureHasSentCarriedItem();
        if (!minecraft.level.getWorldBorder().isWithinBounds(blockHit.getBlockPos())) {
            return InteractionResult.FAIL;
        }

        //#if MC > 11802
        litematica_printer$startPrediction(
                sequence -> new ServerboundUseItemOnPacket(hand, blockHit, sequence));
        //#else
        //$$ litematica_printer$startPrediction(
        //$$         sequence -> new ServerboundUseItemOnPacket(hand, blockHit));
        //#endif
        return InteractionResult.PASS;
    }
}
