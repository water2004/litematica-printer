package me.aleksilassila.litematica.printer.printer.placement;

import java.util.Map;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.*;

/** Vanilla log transformations for this Minecraft version. */
final class LogTransforms {
    private LogTransforms() {}
    /**
     * The axe item was replaced by data-driven block transformers in 26.3 and
     * Fabric no longer exposes the old {@code AxeItemAccessor}.  Keep the
     * printer's lookup local to this version adapter; these are the vanilla
     * axe transformations (including the 26.3 poplar blocks).
     */
    static final Map<Block, Block> STRIPPED_LOGS = Map.ofEntries(
            Map.entry(Blocks.OAK_LOG, Blocks.STRIPPED_OAK_LOG),
            Map.entry(Blocks.OAK_WOOD, Blocks.STRIPPED_OAK_WOOD),
            Map.entry(Blocks.SPRUCE_LOG, Blocks.STRIPPED_SPRUCE_LOG),
            Map.entry(Blocks.SPRUCE_WOOD, Blocks.STRIPPED_SPRUCE_WOOD),
            Map.entry(Blocks.BIRCH_LOG, Blocks.STRIPPED_BIRCH_LOG),
            Map.entry(Blocks.BIRCH_WOOD, Blocks.STRIPPED_BIRCH_WOOD),
            Map.entry(Blocks.JUNGLE_LOG, Blocks.STRIPPED_JUNGLE_LOG),
            Map.entry(Blocks.JUNGLE_WOOD, Blocks.STRIPPED_JUNGLE_WOOD),
            Map.entry(Blocks.ACACIA_LOG, Blocks.STRIPPED_ACACIA_LOG),
            Map.entry(Blocks.ACACIA_WOOD, Blocks.STRIPPED_ACACIA_WOOD),
            Map.entry(Blocks.DARK_OAK_LOG, Blocks.STRIPPED_DARK_OAK_LOG),
            Map.entry(Blocks.DARK_OAK_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD),
            Map.entry(Blocks.MANGROVE_LOG, Blocks.STRIPPED_MANGROVE_LOG),
            Map.entry(Blocks.MANGROVE_WOOD, Blocks.STRIPPED_MANGROVE_WOOD),
            Map.entry(Blocks.CHERRY_LOG, Blocks.STRIPPED_CHERRY_LOG),
            Map.entry(Blocks.CHERRY_WOOD, Blocks.STRIPPED_CHERRY_WOOD),
            Map.entry(Blocks.PALE_OAK_LOG, Blocks.STRIPPED_PALE_OAK_LOG),
            Map.entry(Blocks.PALE_OAK_WOOD, Blocks.STRIPPED_PALE_OAK_WOOD),
            Map.entry(Blocks.POPLAR_LOG, Blocks.STRIPPED_POPLAR_LOG),
            Map.entry(Blocks.POPLAR_WOOD, Blocks.STRIPPED_POPLAR_WOOD),
            Map.entry(Blocks.CRIMSON_STEM, Blocks.STRIPPED_CRIMSON_STEM),
            Map.entry(Blocks.CRIMSON_HYPHAE, Blocks.STRIPPED_CRIMSON_HYPHAE),
            Map.entry(Blocks.WARPED_STEM, Blocks.STRIPPED_WARPED_STEM),
            Map.entry(Blocks.WARPED_HYPHAE, Blocks.STRIPPED_WARPED_HYPHAE),
            Map.entry(Blocks.BAMBOO_BLOCK, Blocks.STRIPPED_BAMBOO_BLOCK)
    );
}
