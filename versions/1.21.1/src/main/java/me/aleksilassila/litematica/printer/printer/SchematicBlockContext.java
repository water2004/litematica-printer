package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.ToString;
import me.aleksilassila.litematica.printer.handler.AsyncSearchCoordinator;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

@ToString
public class SchematicBlockContext {
    public final Minecraft client;
    @Nullable
    public final ClientLevel level;
    @Nullable
    public final WorldSchematic schematic;
    public final BlockPos blockPos;
    public final BlockState currentState;
    public final BlockState requiredState;
    private final BlockGetter currentView;
    private final SignalGetter currentSignalView;
    private final BlockGetter requiredView;
    private final SignalGetter requiredSignalView;
    private final boolean snapshot;

    public SchematicBlockContext(Minecraft client, ClientLevel level, WorldSchematic schematic, BlockPos blockPos) {
        this(client, level, schematic, blockPos,
                level.getBlockState(blockPos), schematic.getBlockState(blockPos));
    }

    public SchematicBlockContext(Minecraft client, ClientLevel level, WorldSchematic schematic, BlockPos blockPos, BlockState currentState, BlockState requiredState) {
        this.client = client;
        this.level = level;
        this.schematic = schematic;
        this.blockPos = blockPos;
        this.currentState = currentState;
        this.requiredState = requiredState;
        this.currentView = level;
        this.currentSignalView = level;
        this.requiredView = schematic;
        this.requiredSignalView = schematic;
        this.snapshot = false;
    }

    public SchematicBlockContext(
            Minecraft client,
            AsyncSearchCoordinator.SnapshotBlockView currentView,
            AsyncSearchCoordinator.SnapshotBlockView requiredView,
            BlockPos blockPos) {
        this.client = client;
        this.level = null;
        this.schematic = null;
        this.blockPos = blockPos;
        this.currentView = currentView;
        this.currentSignalView = currentView;
        this.requiredView = requiredView;
        this.requiredSignalView = requiredView;
        this.currentState = currentView.getBlockState(blockPos);
        this.requiredState = requiredView.getBlockState(blockPos);
        this.snapshot = true;
    }

    private SchematicBlockContext(
            Minecraft client,
            @Nullable ClientLevel level,
            @Nullable WorldSchematic schematic,
            BlockGetter currentView,
            SignalGetter currentSignalView,
            BlockGetter requiredView,
            SignalGetter requiredSignalView,
            BlockPos blockPos,
            boolean snapshot) {
        this.client = client;
        this.level = level;
        this.schematic = schematic;
        this.currentView = currentView;
        this.currentSignalView = currentSignalView;
        this.requiredView = requiredView;
        this.requiredSignalView = requiredSignalView;
        this.blockPos = blockPos;
        this.currentState = currentView.getBlockState(blockPos);
        this.requiredState = requiredView.getBlockState(blockPos);
        this.snapshot = snapshot;
    }

    public static <T extends Comparable<T>> Optional<T> getProperty(BlockState blockState, Property<T> property) {
        return BlockUtils.getProperty(blockState, property);
    }

    public SchematicBlockContext offset(Direction direction) {
        if (direction == null) return this;
        return new SchematicBlockContext(
                client, level, schematic,
                currentView, currentSignalView,
                requiredView, requiredSignalView,
                blockPos.relative(direction), snapshot);
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    public BlockGetter getCurrentView() {
        return currentView;
    }

    public BlockGetter getRequiredView() {
        return requiredView;
    }

    public BlockState getCurrentState(BlockPos pos) {
        return currentView.getBlockState(pos);
    }

    public BlockState getRequiredState(BlockPos pos) {
        return requiredView.getBlockState(pos);
    }

    public int getCurrentSignal(BlockPos pos, Direction direction) {
        return currentSignalView.getSignal(pos, direction);
    }

    public int getRequiredSignal(BlockPos pos, Direction direction) {
        return requiredSignalView.getSignal(pos, direction);
    }

    /**
     * 快照搜索阶段允许产生保守候选；实时消费者会再次执行完整生存检查。
     */
    public boolean canRequiredSurvive() {
        return snapshot || (level != null && requiredState.canSurvive(level, blockPos));
    }

    public boolean isCurrentCollisionShapeFull(BlockState state, BlockPos pos) {
        return state.isCollisionShapeFullBlock(currentView, pos);
    }

    public <T extends Comparable<T>> Optional<T> getRequiredStateProperty(Property<T> property) {
        return getProperty(requiredState, property);
    }

    public <T extends Comparable<T>> Optional<T> getCurrentStateProperty(Property<T> property) {
        return getProperty(currentState, property);
    }

    public Block getRequiredBlock() {
        return requiredState.getBlock();
    }

    public Block getCurrentBlock() {
        return currentState.getBlock();
    }

    public MutableComponent getRequiredBlockName() {
        return requiredState.getBlock().getName();
    }

    public MutableComponent getCurrentBlockName() {
        return currentState.getBlock().getName();
    }
}
