package me.aleksilassila.litematica.printer.printer.placement;

import java.util.Map;
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
import net.minecraft.world.level.block.Block;

/** Log transformations provided by this version's Fabric registry. */
final class LogTransforms {
    static final Map<Block, Block> STRIPPED_LOGS = AxeItemAccessor.getStrippables();

    private LogTransforms() {}
}
