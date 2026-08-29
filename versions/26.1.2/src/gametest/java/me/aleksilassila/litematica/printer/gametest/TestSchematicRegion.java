package me.aleksilassila.litematica.printer.gametest;

import net.minecraft.core.BlockPos;

public final class TestSchematicRegion {
    private static volatile Bounds bounds;

    private TestSchematicRegion() {
    }

    public static void activate(BlockPos first, BlockPos second) {
        bounds = new Bounds(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()),
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ()));
    }

    public static void clear() {
        bounds = null;
    }

    public static boolean contains(BlockPos pos) {
        Bounds current = bounds;
        return current != null
                && pos.getX() >= current.minX && pos.getX() <= current.maxX
                && pos.getY() >= current.minY && pos.getY() <= current.maxY
                && pos.getZ() >= current.minZ && pos.getZ() <= current.maxZ;
    }

    private record Bounds(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
    }
}
