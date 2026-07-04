package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.HighlightType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.bedrock.BedrockUtils;
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
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean canExecute() {
        if (player.isCreative()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        if (!ModUtils.isBedrockMinerLoaded() && !ModUtils.isBlockMinerLoaded()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_MOD_MISSING.getName());
            return false;
        }
        if (!BedrockUtils.isWorking()) {
            BedrockUtils.setWorking(true);
        }
        if (BedrockUtils.isBedrockMinerFeatureEnable()) {
            BedrockUtils.setBedrockMinerFeatureEnable(false);
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
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        BedrockUtils.addToBreakList(blockPos, client.level);
        addHighlight(blockPos, HighlightType.BREAK);
        setCooldown(blockPos, 100);
    }
}