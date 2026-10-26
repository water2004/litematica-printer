package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.handler.TransactionKey;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;

/**
 * Surface-restricted filling. Jobs address supports inside the area selection;
 * the placement above them may be one block beyond its upper boundary.
 */
public final class SpawnProofing extends Fill {
    public static final String NAME = "spawnProofing";

    public SpawnProofing() {
        super(NAME, Configs.SpawnProofing.ENABLED, Configs.SpawnProofing.SELECTION_TYPE,
                new MaterialSettings(Configs.SpawnProofing.BLOCK_MODE,
                        Configs.SpawnProofing.BLOCK_LIST, Configs.SpawnProofing.HANDHELD_BLACKLIST));
    }

    @Override
    protected int snapshotHalo() {
        return 1;
    }

    public static boolean hasFullTop(BlockGetter world, BlockPos support) {
        var state = world.getBlockState(support);
        return Block.isFaceFull(state.getCollisionShape(world, support), Direction.UP);
    }

    public static boolean needsCover(BlockGetter world, BlockPos support) {
        if (support.getY() >= world.getMaxY()) return false;
        var target = world.getBlockState(support.above());
        return target.canBeReplaced() && target.getFluidState().isEmpty()
                && hasFullTop(world, support);
    }

    @Override
    public boolean canProcessPos(BlockPos support) {
        return needsCover(level, support);
    }

    @Override
    public boolean isCorrectBlock(BlockPos support) {
        return !needsCover(level, support);
    }

    @Override
    protected TransactionKey getSearchTransactionKey(
            AsyncSearchCoordinator.SearchBlockSnapshot block, Object context) {
        if (block.currentState().isAir()) return null;
        return needsCover(block.currentView(), block.pos()) ? TransactionKey.HOMOGENEOUS : null;
    }

    @Override
    protected Object captureSearchContext() {
        return null;
    }

    @Override
    protected BlockPos getPlacementPos(BlockPos support) {
        return support.above();
    }

    @Override
    protected Action createPlacementAction(BlockPos target) {
        // Always click the real top face, even when Print's place-in-air is enabled.
        // Sneaking prevents chests and other interactive supports from opening.
        return new Action().setRequiresSupport()
                .queueAction(target, Direction.DOWN, true, player);
    }
}
