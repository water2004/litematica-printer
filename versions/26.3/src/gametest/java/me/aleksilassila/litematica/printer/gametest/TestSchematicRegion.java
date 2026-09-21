package me.aleksilassila.litematica.printer.gametest;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.List;

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

    public static boolean isActive() {
        return bounds != null;
    }

    public static List<PrinterBox> snapshotIntersection(PrinterBox limit) {
        Bounds current = bounds;
        if (current == null || limit == null) return List.of();
        int minX = Math.max(current.minX, limit.minX);
        int minY = Math.max(current.minY, limit.minY);
        int minZ = Math.max(current.minZ, limit.minZ);
        int maxX = Math.min(current.maxX, limit.maxX);
        int maxY = Math.min(current.maxY, limit.maxY);
        int maxZ = Math.min(current.maxZ, limit.maxZ);
        return minX <= maxX && minY <= maxY && minZ <= maxZ
                ? List.of(new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ))
                : List.of();
    }

    private record Bounds(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
    }
}
