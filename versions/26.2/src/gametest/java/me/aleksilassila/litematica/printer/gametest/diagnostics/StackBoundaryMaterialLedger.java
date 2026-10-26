package me.aleksilassila.litematica.printer.gametest.diagnostics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Server-authoritative accounting, including blocks outside the fixture and dropped items. */
public final class StackBoundaryMaterialLedger {
    private StackBoundaryMaterialLedger() { }

    public static Snapshot capture(ServerLevel level, ServerPlayer player,
                            List<Block> materials, Set<BlockPos> targets) {
        var inventory = new TreeMap<String, Integer>();
        var boxed = new TreeMap<String, Integer>();
        var targetBlocks = new TreeMap<String, Integer>();
        var outsideBlocks = new TreeMap<String, Integer>();
        var dropped = new TreeMap<String, Integer>();
        var cursor = new TreeMap<String, Integer>();
        var outsidePositions = new ArrayList<String>();
        var droppedPositions = new ArrayList<String>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            addStack(player.getInventory().getItem(slot), materials, inventory, boxed);
        }
        addStack(player.containerMenu.getCarried(), materials, cursor, boxed);
        // All printing targets are above the support platform at Y=63.
        // Cover the entire possible interaction region, not just the one-block halo.
        for (BlockPos pos : BlockPos.betweenClosed(-32, 64, -32, 32, 96, 32)) {
            Block block = level.getBlockState(pos).getBlock();
            if (!materials.contains(block)) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            (targets.contains(pos) ? targetBlocks : outsideBlocks).merge(id, 1, Integer::sum);
            if (!targets.contains(pos) && outsidePositions.size() < 64) {
                outsidePositions.add(pos.toShortString() + "=" + id);
            }
        }
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(-33, 0, -33, 33, 100, 33))) {
            ItemStack stack = entity.getItem();
            addStack(stack, materials, dropped, dropped);
            droppedPositions.add(entity.position() + "=" + stack);
        }
        var total = new TreeMap<String, Integer>();
        for (var counts : List.of(inventory, boxed, targetBlocks, outsideBlocks, dropped, cursor)) {
            counts.forEach((item, count) -> total.merge(item, count, Integer::sum));
        }
        return new Snapshot(inventory, boxed, targetBlocks, outsideBlocks, dropped, cursor,
                total, List.copyOf(outsidePositions), List.copyOf(droppedPositions));
    }

    private static void addStack(ItemStack stack, List<Block> materials,
                                 Map<String, Integer> loose, Map<String, Integer> boxed) {
        if (stack.isEmpty()) return;
        if (materials.stream().anyMatch(block -> stack.is(block.asItem()))) {
            loose.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), Integer::sum);
        }
        if (stack.is(Items.SHULKER_BOX)) {
            var contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) contents.nonEmptyItemCopyStream()
                    .forEach(item -> addStack(item, materials, boxed, boxed));
        }
    }

    public record Snapshot(Map<String, Integer> inventory, Map<String, Integer> boxed,
                    Map<String, Integer> targetBlocks, Map<String, Integer> outsideBlocks,
                    Map<String, Integer> dropped, Map<String, Integer> cursor,
                    Map<String, Integer> total, List<String> outsidePositions,
                    List<String> droppedPositions) {
        public Map<String, Integer> deltaFrom(Snapshot before) {
            var delta = new TreeMap<String, Integer>(total);
            before.total.forEach((item, count) -> delta.merge(item, -count, Integer::sum));
            delta.values().removeIf(count -> count == 0);
            return delta;
        }
    }
}
