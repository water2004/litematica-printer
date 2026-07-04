package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 扫描计划 — 两阶段扫描引擎的材料分类。
 * <p>
 * COLLECT 阶段：收集所有需处理坐标，按所需物品分组以最小化物品切换。
 * PROCESS 阶段：按分组依次执行。
 * </p>
 */
public class ScanPlan {
    private final Map<Item, List<BlockPos>> materialGroups = new LinkedHashMap<>();
    private final List<BlockPos> noItemPositions = new ArrayList<>();

    private List<Map.Entry<Item, List<BlockPos>>> groupList;
    private int groupIndex = 0;
    private boolean collectionComplete = false;

    public void collect(BlockPos pos, @Nullable Item[] requiredItems) {
        if (requiredItems == null || requiredItems.length == 0 || requiredItems[0] == null) {
            noItemPositions.add(pos);
        } else {
            materialGroups.computeIfAbsent(requiredItems[0], k -> new ArrayList<>()).add(pos);
        }
    }

    public void completeCollection() {
        collectionComplete = true;
        groupList = new ArrayList<>(materialGroups.entrySet());
        groupIndex = 0;
    }

    public boolean isCollectionComplete() {
        return collectionComplete;
    }

    public boolean hasNextGroup() {
        if (!collectionComplete) return false;
        return groupIndex < groupList.size();
    }

    @Nullable
    public GroupEntry nextGroup() {
        if (!hasNextGroup()) return null;
        Map.Entry<Item, List<BlockPos>> entry = groupList.get(groupIndex++);
        return new GroupEntry(entry.getKey(), entry.getValue());
    }

    public List<BlockPos> getNoItemPositions() {
        return noItemPositions;
    }

    public int getTotalCollected() {
        int total = noItemPositions.size();
        for (List<BlockPos> group : materialGroups.values()) {
            total += group.size();
        }
        return total;
    }

    public int getGroupCount() {
        return materialGroups.size();
    }

    public Iterator<BlockPos> createFlatIterator() {
        List<BlockPos> all = new ArrayList<>(noItemPositions);
        for (List<BlockPos> group : materialGroups.values()) {
            all.addAll(group);
        }
        return all.iterator();
    }

    public void reset() {
        materialGroups.clear();
        noItemPositions.clear();
        groupList = null;
        groupIndex = 0;
        collectionComplete = false;
    }

    public record GroupEntry(Item item, List<BlockPos> positions) {}
}