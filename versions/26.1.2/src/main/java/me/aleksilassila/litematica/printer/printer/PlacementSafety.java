package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.core.dependency.DependencyCheck;
import me.aleksilassila.litematica.printer.core.dependency.DependencyCheck.Node;
import me.aleksilassila.litematica.printer.core.dependency.DependencyCheck.Reason;
import me.aleksilassila.litematica.printer.core.dependency.DependencyCheck.Result;
import me.aleksilassila.litematica.printer.enums.BlockMatchingType;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.WallSide;

import java.util.ArrayList;
import java.util.List;

/**
 * Construction ordering only, not a redstone simulator. No retained graph,
 * cooldowns or scheduler state. Call with live data immediately before consuming.
 */
public final class PlacementSafety {
    private static final int MAX_DEPENDENCIES = 4096;

    private PlacementSafety() {}

    public static boolean appliesTo(BlockState state) {
        return state.getBlock() instanceof ObserverBlock
                || state.getBlock() instanceof PistonBaseBlock;
    }

    public static Result<BlockPos> evaluate(SchematicBlockContext ctx) {
        if (!appliesTo(ctx.requiredState)) return new Result<>(Reason.READY, ctx.blockPos);
        return DependencyCheck.evaluate(ctx.blockPos, MAX_DEPENDENCIES,
                pos -> describe(ctx, pos));
    }

    private static Node<BlockPos> describe(SchematicBlockContext ctx, BlockPos pos) {
        if (!ctx.hasStatesAt(pos)) return new Node<>(Reason.UNKNOWN, List.of());
        BlockState required = ctx.getRequiredState(pos);
        boolean root = pos.equals(ctx.blockPos);
        List<BlockPos> dependencies = new ArrayList<>();
        Reason condition = root || matchesDependency(ctx, pos, required)
                ? Reason.READY : Reason.MISMATCH;

        if (required.getBlock() instanceof ObserverBlock) {
            Direction facing = required.getValue(ObserverBlock.FACING);
            dependencies.add(pos.relative(facing));
            if (root) {
                // Keep the existing local guard when the output still needs construction.
                // Do not pretend that an arbitrary redstone circuit can be proven safe here.
                BlockPos output = pos.relative(facing.getOpposite());
                if (!ctx.hasStatesAt(output)) return new Node<>(Reason.UNKNOWN, List.of());
                if (BlockMatchingType.get(ctx.getRequiredState(output), ctx.getCurrentState(output))
                        != BlockMatchingType.CORRECT) {
                    for (Direction direction : Direction.values()) {
                        BlockPos neighbor = output.relative(direction);
                        if (neighbor.equals(pos)) continue;
                        if (!ctx.hasStatesAt(neighbor)) return new Node<>(Reason.UNKNOWN, List.of());
                        if (ctx.getCurrentState(neighbor).getBlock() instanceof PistonBaseBlock) {
                            condition = Reason.BLOCKED;
                        }
                    }
                }
            }
        } else if (root && required.getBlock() instanceof PistonBaseBlock) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (!ctx.hasStatesAt(neighbor)) return new Node<>(Reason.UNKNOWN, List.of());
                BlockState state = ctx.getRequiredState(neighbor);
                // Observer's FACING points at its input; the opposite face is its output.
                if (state.getBlock() instanceof ObserverBlock
                        && state.getValue(ObserverBlock.FACING) == direction) {
                    dependencies.add(neighbor);
                }
            }
        } else if (required.getBlock() instanceof FallingBlock) {
            dependencies.add(pos.below());
        }
        return new Node<>(condition, dependencies);
    }

    private static boolean matchesDependency(
            SchematicBlockContext ctx, BlockPos pos, BlockState required) {
        List<Property<?>> derivedConnections = new ArrayList<>();
        BlockState current = ctx.getCurrentState(pos);
        if (required.getBlock() != current.getBlock()) {
            return BlockMatchingType.get(required, current) == BlockMatchingType.CORRECT;
        }
        // Only the face touching this placement is derived from the missing candidate.
        // Orientation, power, other connections and unrelated properties still must match.
        for (Direction towardCandidate : Direction.values()) {
            if (!pos.relative(towardCandidate).equals(ctx.blockPos)) continue;
            if (required.getBlock() instanceof WallBlock) {
                BlockUtils.getWallFacingProperty(towardCandidate).ifPresent(derivedConnections::add);
                if (towardCandidate == Direction.UP) {
                    // A block above changes wall heights/post, but cannot create side connections.
                    for (var property : WallBlock.PROPERTY_BY_DIRECTION.values()) {
                        if (required.getValue(property) != WallSide.NONE
                                && current.getValue(property) != WallSide.NONE) {
                            derivedConnections.add(property);
                        }
                    }
                } else if (towardCandidate.getAxis().isHorizontal()) {
                    var side = WallBlock.PROPERTY_BY_DIRECTION.get(towardCandidate);
                    // A new side can change the central post. Do not conceal a change caused
                    // by unfinished work above the wall instead of this candidate.
                    if (required.getValue(side) != current.getValue(side)
                            && ctx.hasStatesAt(pos.above())
                            && BlockMatchingType.get(ctx.getRequiredState(pos.above()),
                                    ctx.getCurrentState(pos.above())) == BlockMatchingType.CORRECT) {
                        derivedConnections.add(WallBlock.UP);
                    }
                }
            } else if (required.getBlock() instanceof CrossCollisionBlock) {
                BlockUtils.getCrossCollisionBlock(towardCandidate).ifPresent(derivedConnections::add);
            }
        }
        return BlockMatchingType.get(required, current,
                derivedConnections.toArray(new Property<?>[0])) == BlockMatchingType.CORRECT;
    }
}

