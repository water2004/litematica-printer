package me.aleksilassila.litematica.printer.core.status;

/** The reason a pooled job must be retried on a later client tick. */
public enum PrinterWaitReason {
    ITEM_SWITCH,
    WORLD_UPDATE
}
