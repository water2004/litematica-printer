package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicReference;

public class Bedrock extends Module {
    public final static String NAME = "bedrock";

    public Bedrock() {
        super(NAME, Configs.Bedrock.ENABLED, null, true);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Bedrock.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Bedrock.BREAK_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean usesJobPool() {
        return true;
    }

    @Override
    protected boolean canExecute() {
        if (player.isCreative()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        if (!ModUtils.isBedrockMinerLoaded() && !ModUtils.isBlockMinerLoaded()) {
            if (ModUtils.isLoadMod("bedrock-miner"))
                MessageUtils.setOverlayMessage(I18n.BEDROCK_NOT_SUPPORT.getName());
            MessageUtils.setOverlayMessage(I18n.BEDROCK_MOD_MISSING.getName());
            return false;
        }
        if (!BedrockCompat.isWorking()) {
            BedrockCompat.setWorking(true);
        }
        if (BedrockCompat.isFeatureEnable()) {
            BedrockCompat.setFeatureEnable(false);
        }
        return true;
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.BEDROCK);
    }

    @Override
    public boolean isCorrectBlock(BlockPos pos) {
        return !level.getBlockState(pos).is(Blocks.BEDROCK);
    }

    @Override
    protected TransactionKey getSearchTransactionKey(
            AsyncSearchCoordinator.SearchBlockSnapshot block,
            Object searchContext) {
        return block.currentState().is(Blocks.BEDROCK)
                ? TransactionKey.HOMOGENEOUS : null;
    }

    @Override
    protected boolean canSearch() {
        return super.canSearch()
                && (ModUtils.isBedrockMinerLoaded() || ModUtils.isBlockMinerLoaded());
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        BedrockCompat.addToBreakList(blockPos, client.level);
        addHighlight(blockPos, HighlightType.BREAK);
        setCooldown(blockPos, 100);
    }
}
