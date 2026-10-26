package me.aleksilassila.litematica.printer.handler.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.handler.search.AsyncSearchCoordinator.SearchRequest;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Compiled shape masks, selection intersections and traversal order. */
public final class SearchGeometry {
    private SearchGeometry() {}

    static final int MASK_TILE_MAX_VOLUME = 128;
    static final Map<ShapeMaskKey, CompiledShapeMask> SHAPE_MASKS =
            new ConcurrentHashMap<>();

    public record SearchBounds(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            IterationOrderType iterationOrder,
            boolean xIncrement,
            boolean yIncrement,
            boolean zIncrement) {
        public boolean contains(BlockPos pos) {
            return contains(pos.getX(), pos.getY(), pos.getZ());
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        boolean contains(TileSpec tile) {
            return tile.minX() >= minX && tile.maxX() <= maxX
                    && tile.minY() >= minY && tile.maxY() <= maxY
                    && tile.minZ() >= minZ && tile.maxZ() <= maxZ;
        }

        boolean intersects(TileSpec tile) {
            return tile.maxX() >= minX && tile.minX() <= maxX
                    && tile.maxY() >= minY && tile.minY() <= maxY
                    && tile.maxZ() >= minZ && tile.minZ() <= maxZ;
        }

        public long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }
    }

    public enum WorkspaceFilter {
        NONE,
        SCHEMATIC,
        SELECTION
    }

    enum CoverageMode {
        EMPTY,
        PARTIAL,
        FULL
    }

    enum ShapeRelation {
        EMPTY,
        PARTIAL,
        FULL
    }

    record ShapeMaskKey(RadiusShapeType shape, long rangeBits) {
    }

    record RelativeTile(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            boolean shapeFull) {
        long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }

        @Nullable
        TileSpec translateAndClip(
                int ordinal,
                int originX,
                int originY,
                int originZ,
                SearchBounds bounds) {
            int worldMinX = Math.max(bounds.minX(), originX + minX);
            int worldMinY = Math.max(bounds.minY(), originY + minY);
            int worldMinZ = Math.max(bounds.minZ(), originZ + minZ);
            int worldMaxX = Math.min(bounds.maxX(), originX + maxX);
            int worldMaxY = Math.min(bounds.maxY(), originY + maxY);
            int worldMaxZ = Math.min(bounds.maxZ(), originZ + maxZ);
            if (worldMinX > worldMaxX
                    || worldMinY > worldMaxY
                    || worldMinZ > worldMaxZ) return null;
            return new TileSpec(
                    ordinal,
                    worldMinX, worldMinY, worldMinZ,
                    worldMaxX, worldMaxY, worldMaxZ);
        }
    }

    static final class CompiledShapeMask {
        final RadiusShapeType shape;
        final double range;
        final List<RelativeTile> tiles;

        CompiledShapeMask(
                RadiusShapeType shape,
                double range,
                List<RelativeTile> tiles) {
            this.shape = shape;
            this.range = range;
            this.tiles = List.copyOf(tiles);
        }

        static CompiledShapeMask compile(RadiusShapeType shape, double range) {
            int extent = Math.max(0, (int) Math.ceil(range));
            List<RelativeTile> tiles = new ArrayList<>();
            split(shape, range,
                    -extent, -extent, -extent,
                    extent, extent, extent,
                    tiles);
            return new CompiledShapeMask(shape, range, tiles);
        }

        static void split(
                RadiusShapeType shape,
                double range,
                int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ,
                List<RelativeTile> output) {
            ShapeRelation relation = relation(
                    shape, range, minX, minY, minZ, maxX, maxY, maxZ);
            if (relation == ShapeRelation.EMPTY) return;
            long volume = ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
            if (volume <= MASK_TILE_MAX_VOLUME) {
                output.add(new RelativeTile(
                        minX, minY, minZ, maxX, maxY, maxZ,
                        relation == ShapeRelation.FULL));
                return;
            }

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;
            if (sizeX >= sizeY && sizeX >= sizeZ) {
                int middle = minX + (sizeX >>> 1) - 1;
                split(shape, range, minX, minY, minZ,
                        middle, maxY, maxZ, output);
                split(shape, range, middle + 1, minY, minZ,
                        maxX, maxY, maxZ, output);
            } else if (sizeY >= sizeZ) {
                int middle = minY + (sizeY >>> 1) - 1;
                split(shape, range, minX, minY, minZ,
                        maxX, middle, maxZ, output);
                split(shape, range, minX, middle + 1, minZ,
                        maxX, maxY, maxZ, output);
            } else {
                int middle = minZ + (sizeZ >>> 1) - 1;
                split(shape, range, minX, minY, minZ,
                        maxX, maxY, middle, output);
                split(shape, range, minX, minY, middle + 1,
                        maxX, maxY, maxZ, output);
            }
        }

        static ShapeRelation relation(
                RadiusShapeType shape,
                double range,
                int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ) {
            long minAbsX = intervalMinAbs(minX, maxX);
            long minAbsY = intervalMinAbs(minY, maxY);
            long minAbsZ = intervalMinAbs(minZ, maxZ);
            long maxAbsX = Math.max(Math.abs((long) minX), Math.abs((long) maxX));
            long maxAbsY = Math.max(Math.abs((long) minY), Math.abs((long) maxY));
            long maxAbsZ = Math.max(Math.abs((long) minZ), Math.abs((long) maxZ));
            double minimum;
            double maximum;
            switch (shape) {
                case SPHERE -> {
                    minimum = minAbsX * minAbsX
                            + minAbsY * minAbsY
                            + minAbsZ * minAbsZ;
                    maximum = maxAbsX * maxAbsX
                            + maxAbsY * maxAbsY
                            + maxAbsZ * maxAbsZ;
                    range *= range;
                }
                case OCTAHEDRON -> {
                    minimum = minAbsX + minAbsY + minAbsZ;
                    maximum = maxAbsX + maxAbsY + maxAbsZ;
                }
                case CUBE -> {
                    minimum = Math.max(minAbsX, Math.max(minAbsY, minAbsZ));
                    maximum = Math.max(maxAbsX, Math.max(maxAbsY, maxAbsZ));
                }
                default -> throw new IllegalStateException("Unknown shape " + shape);
            }
            if (minimum > range) return ShapeRelation.EMPTY;
            return maximum <= range ? ShapeRelation.FULL : ShapeRelation.PARTIAL;
        }

        static long intervalMinAbs(int min, int max) {
            if (min <= 0 && max >= 0) return 0L;
            return Math.min(Math.abs((long) min), Math.abs((long) max));
        }

        boolean containsWorld(
                int x, int y, int z,
                int originX, int originY, int originZ) {
            long dx = (long) x - originX;
            long dy = (long) y - originY;
            long dz = (long) z - originZ;
            return switch (shape) {
                case SPHERE -> dx * dx + dy * dy + dz * dz <= range * range;
                case OCTAHEDRON -> Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= range;
                case CUBE -> Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) <= range;
            };
        }

        List<RelativeTile> orderedTiles(SearchBounds bounds) {
            List<RelativeTile> ordered = new ArrayList<>(tiles);
            ordered.sort(tileComparator(bounds));
            return ordered;
        }

        static Comparator<RelativeTile> tileComparator(SearchBounds bounds) {
            int[] axes = switch (bounds.iterationOrder()) {
                case XYZ -> new int[]{2, 1, 0};
                case XZY -> new int[]{1, 2, 0};
                case YXZ -> new int[]{2, 0, 1};
                case YZX -> new int[]{0, 2, 1};
                case ZXY -> new int[]{1, 0, 2};
                case ZYX -> new int[]{0, 1, 2};
            };
            return (left, right) -> {
                for (int axis : axes) {
                    boolean increment = switch (axis) {
                        case 0 -> bounds.xIncrement();
                        case 1 -> bounds.yIncrement();
                        default -> bounds.zIncrement();
                    };
                    int leftValue = coordinate(left, axis, increment);
                    int rightValue = coordinate(right, axis, increment);
                    int compared = increment
                            ? Integer.compare(leftValue, rightValue)
                            : Integer.compare(rightValue, leftValue);
                    if (compared != 0) return compared;
                }
                return 0;
            };
        }

        static int coordinate(
                RelativeTile tile, int axis, boolean increment) {
            return switch (axis) {
                case 0 -> increment ? tile.minX() : tile.maxX();
                case 1 -> increment ? tile.minY() : tile.maxY();
                default -> increment ? tile.minZ() : tile.maxZ();
            };
        }
    }

    static final class CompiledWorkspace {
        final boolean unrestricted;
        final List<SearchBounds> boxes;

        CompiledWorkspace(boolean unrestricted, List<SearchBounds> boxes) {
            this.unrestricted = unrestricted;
            this.boxes = boxes;
        }

        static CompiledWorkspace capture(SearchRequest request) {
            if (request.workspaceFilter() == WorkspaceFilter.NONE) {
                return new CompiledWorkspace(true, List.of());
            }
            List<SearchBounds> boxes;
            if (request.workspaceFilter() == WorkspaceFilter.SCHEMATIC) {
                SearchBounds limit = request.bounds();
                PrinterBox printerLimit = new PrinterBox(
                        limit.minX(), limit.minY(), limit.minZ(),
                        limit.maxX(), limit.maxY(), limit.maxZ());
                boxes = LitematicaUtils.getSchematicBoxesSnapshot(printerLimit)
                        .stream()
                        .map(box -> new SearchBounds(
                                box.minX, box.minY, box.minZ,
                                box.maxX, box.maxY, box.maxZ,
                                limit.iterationOrder(),
                                limit.xIncrement(), limit.yIncrement(),
                                limit.zIncrement()))
                        .toList();
            } else {
                boxes = request.selectionBoxes();
            }
            return new CompiledWorkspace(false, List.copyOf(boxes));
        }

        CoverageMode classify(TileSpec tile) {
            if (unrestricted) return CoverageMode.FULL;
            boolean intersects = false;
            for (SearchBounds box : boxes) {
                if (!box.intersects(tile)) continue;
                intersects = true;
                if (box.contains(tile)) return CoverageMode.FULL;
            }
            return intersects ? CoverageMode.PARTIAL : CoverageMode.EMPTY;
        }

        boolean contains(BlockPos pos) {
            return contains(pos.getX(), pos.getY(), pos.getZ());
        }

        boolean contains(int x, int y, int z) {
            if (unrestricted) return true;
            for (SearchBounds box : boxes) {
                if (box.contains(x, y, z)) return true;
            }
            return false;
        }
    }

    record TileSpec(
            int ordinal,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        long volume() {
            return ((long) maxX - minX + 1L)
                    * ((long) maxY - minY + 1L)
                    * ((long) maxZ - minZ + 1L);
        }
    }

    static CompiledShapeMask shapeMask(SearchRequest request) {
        ShapeMaskKey key = new ShapeMaskKey(
                request.shape(), Double.doubleToLongBits(request.range()));
        return SHAPE_MASKS.computeIfAbsent(
                key, ignored -> CompiledShapeMask.compile(
                        request.shape(), request.range()));
    }

    static boolean continuing(int value, int end, int step) {
        return step > 0 ? value <= end : value >= end;
    }
}
