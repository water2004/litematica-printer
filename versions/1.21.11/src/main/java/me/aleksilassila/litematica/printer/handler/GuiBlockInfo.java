package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 渲染快照 — 由 Module 在迭代时更新，render 线程读取。
 * 不可变，避免跨线程竞态。
 */
public class GuiBlockInfo {
    public final BlockPos pos;
    public final BlockState currentState;
    public final @Nullable BlockState requiredState;
    public final boolean interacted;
    public final boolean execute;
    public final boolean posInSelectionRange;

    public GuiBlockInfo(BlockPos pos, BlockState currentState, @Nullable BlockState requiredState,
                        boolean interacted, boolean execute, boolean posInSelectionRange) {
        this.pos = pos;
        this.currentState = currentState;
        this.requiredState = requiredState;
        this.interacted = interacted;
        this.execute = execute;
        this.posInSelectionRange = posInSelectionRange;
    }
}
