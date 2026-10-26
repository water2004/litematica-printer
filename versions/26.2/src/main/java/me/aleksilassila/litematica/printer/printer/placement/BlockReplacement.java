package me.aleksilassila.litematica.printer.printer.placement;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.interfaces.compat.ChainVeinCompat;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ChainBreakAction;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;
import org.jetbrains.annotations.Nullable;
import static me.aleksilassila.litematica.printer.printer.placement.LogTransforms.STRIPPED_LOGS;

@SuppressWarnings("IfCanBeSwitch")
final class BlockReplacement {
    private BlockReplacement() {}

    static @Nullable Action build(SchematicBlockContext ctx, BlockKind requiredType) {
        switch (requiredType) {
            case FARMLAND -> {
                Block[] soilBlocks = new Block[]{Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.DIRT_PATH, Blocks.COARSE_DIRT};
                for (Block soilBlock : soilBlocks) {
                    if (ctx.currentState.getBlock().equals(soilBlock)) {
                        return new ClickAction().setItems(Reference.HOE_ITEMS);
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                        && canBreakBlock(ctx)) return new ChainBreakAction();
            }
            case DIRT_PATH -> {
                Block[] soilBlocks = new Block[]{Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MYCELIUM, Blocks.PODZOL};
                for (Block soilBlock : soilBlocks) {
                    if (ctx.currentState.getBlock().equals(soilBlock)) {
                        return new ClickAction().setItems(Reference.SHOVEL_ITEMS);
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                        && canBreakBlock(ctx)) return new ChainBreakAction();
            }
            case FLOWER_POT -> {
                if (ctx.requiredState.getBlock() instanceof FlowerPotBlock potBlock) {
                    Block content = potBlock.getPotted();
                    if (content != Blocks.AIR) {
                        return new ClickAction().setItem(content.asItem());
                    }
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                        && canBreakBlock(ctx)) return new ChainBreakAction();
            }
            case CAULDRON -> {
                if (requiredType.containsExactClass(ctx.currentState.getBlock().getClass())) {
                    return null;
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                        && canBreakBlock(ctx)) {
                    return new ChainBreakAction();
                }
            }
            case STRIP_LOG -> {
                Block stripped = STRIPPED_LOGS.get(ctx.currentState.getBlock());
                if (stripped != null && stripped == ctx.requiredState.getBlock()) {
                    return new ClickAction().setItems(Reference.AXE_ITEMS);
                }
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                        && canBreakBlock(ctx)) return new ChainBreakAction();
            }
            case SIGN -> {
                if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                        && canBreakBlock(ctx)) {
                    boolean isLegitimateSign = ctx.currentState.getBlock() instanceof StandingSignBlock
                            || ctx.currentState.getBlock() instanceof WallSignBlock
                            || ctx.currentState.getBlock() instanceof WallHangingSignBlock
                            || ctx.currentState.getBlock() instanceof CeilingHangingSignBlock
                            ;
                    if (!isLegitimateSign) {
                        return new ChainBreakAction();
                    }
                }
            }
            case CROPS -> {
                String requiredBlockKey = BlockUtils.getKeyString(ctx.requiredState.getBlock());
                String currentBlockKey = BlockUtils.getKeyString(ctx.currentState.getBlock());
                if (requiredBlockKey.contains("pumpkin_stem") && !currentBlockKey.contains("pumpkin_stem")) {
                    return new ChainBreakAction();
                } else if (requiredBlockKey.contains("melon_stem") && !currentBlockKey.contains("melon_stem")) {
                    return new ChainBreakAction();
                }
            }
            default -> {
                if (Configs.Print.REPLACE_CORAL.getBooleanValue() && ctx.requiredState.getBlock().getDescriptionId().contains("coral")) {
                    break;
                }
                if ((Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue() && !ctx.requiredState.isAir())
                        || (Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue() && ctx.requiredState.isAir())) {
                    if (canBreakBlock(ctx)) return new ChainBreakAction();
                }
            }
        }
        return null;
    }

    private static boolean canBreakBlock(SchematicBlockContext ctx) {
        if (!ctx.isSnapshot()) {
            return ChainVeinCompat.canBreakBlock(ctx.blockPos);
        }
        return ChainVeinCompat.isAvailable()
                && !ctx.currentState.isAir()
                && !(ctx.currentState.getBlock() instanceof LiquidBlock);
    }
}
