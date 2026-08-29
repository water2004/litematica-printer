package me.aleksilassila.litematica.printer.core.status;

/**
 * User-facing state of the printer scheduler. Minecraft-specific adapters decide
 * which state applies; the HUD only decides how to present it.
 */
public enum PrinterStatus {
    PRINTING("litematica-printer.hud.status.printing", Kind.ACTIVE),
    FILLING("litematica-printer.hud.status.filling", Kind.ACTIVE),
    REMOVING_FLUID("litematica-printer.hud.status.removingFluid", Kind.ACTIVE),
    BREAKING_BEDROCK("litematica-printer.hud.status.breakingBedrock", Kind.ACTIVE),
    SEARCHING("litematica-printer.hud.status.searching", Kind.SEARCH),
    WAITING_FOR_SEARCH("litematica-printer.hud.status.waitingForSearch", Kind.SEARCH),
    WAITING_FOR_HAND("litematica-printer.hud.status.waitingForHand", Kind.WAITING),
    WAITING_FOR_SHULKER("litematica-printer.hud.status.waitingForShulker", Kind.WAITING),
    WAITING_FOR_CONTAINER("litematica-printer.hud.status.waitingForContainer", Kind.WAITING),
    WAITING_FOR_LOOK("litematica-printer.hud.status.waitingForLook", Kind.WAITING),
    WAITING_FOR_WORLD("litematica-printer.hud.status.waitingForWorld", Kind.WAITING),
    PAUSED_BY_LAG("litematica-printer.hud.status.pausedByLag", Kind.WAITING);

    private final String translationKey;
    private final Kind kind;

    PrinterStatus(String translationKey, Kind kind) {
        this.translationKey = translationKey;
        this.kind = kind;
    }

    public String translationKey() {
        return translationKey;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        ACTIVE,
        SEARCH,
        WAITING
    }
}
