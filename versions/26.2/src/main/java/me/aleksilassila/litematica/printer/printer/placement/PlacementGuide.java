package me.aleksilassila.litematica.printer.printer.placement;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ChainBreakAction;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("IfCanBeSwitch")
public final class PlacementGuide {
    private final @NotNull Minecraft mc;
    public PlacementGuide(@NotNull Minecraft client) {
        this.mc = client;
    }

    public @Nullable Action getAction(SchematicBlockContext ctx) {
        BlockMatchingType state = BlockMatchingType.get(ctx);
        if (state == BlockMatchingType.CORRECT) return null;
        // canSurvive 只阻拦放置（MISSING），不阻拦破坏（ERROR_BLOCK 走 BREAK_WRONG_BLOCK）
        if (state == BlockMatchingType.MISSING_BLOCK && !ctx.canRequiredSurvive()) return null;
        // 双格方块（玫瑰丛等）：MISSING 时上半部分由下半部分自动生成，不独立放置
        if (state == BlockMatchingType.MISSING_BLOCK
                && ctx.requiredState.getBlock() instanceof DoublePlantBlock
                && ctx.requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        BlockKind hook = BlockKind.forBlockClass(
                ctx.requiredState.getBlock().getClass());
        return buildAction(ctx, hook, state);
    }

    @SuppressWarnings("EnhancedSwitchMigration")
    private @Nullable Action buildAction(SchematicBlockContext ctx, BlockKind requiredType, BlockMatchingType state) {
        // 跳过含水方块
        if (Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue() && BlockUtils.isNeedsWater(ctx.requiredState)) {
            return null;
        }
        if (Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()
                && BlockUtils.isNeedsWater(ctx.requiredState)) {
            if (ctx.currentState.getBlock() instanceof IceBlock) {
                return new ChainBreakAction();
            }
            if (!BlockUtils.isWaterSource(ctx.currentState) && state == BlockMatchingType.MISSING_BLOCK) {
                if (!ctx.isSnapshot() && mc.gameMode == null) {
                    return null;
                }
                var downBlockState = ctx.getCurrentState(ctx.blockPos.below());
                // Match vanilla IceBlock.playerDestroy: ice needs support to leave water.
                if (!downBlockState.blocksMotion() && !downBlockState.liquid()) {
                    return null;
                }
                if (!ctx.isSnapshot() && mc.gameMode.getPlayerMode().isCreative()) {
                    MessageUtils.setOverlayMessage(I18n.ICE_CREATIVE_MODE.getName());
                    return null;
                }
                return new Action().setItem(Items.ICE);
            }
        }
        Action action;
        switch (state) {
            case MISSING_BLOCK:
                action = MissingBlockPlacement.build(ctx, requiredType);
                break;
            case ERROR_BLOCK:
                action = BlockReplacement.build(ctx, requiredType);
                break;
            case ERROR_BLOCK_STATE:
                action = BlockStateAdjustment.build(ctx, requiredType, mc);
                break;
            default:
                action = null;
                break;
        }
        return action;
    }
}
