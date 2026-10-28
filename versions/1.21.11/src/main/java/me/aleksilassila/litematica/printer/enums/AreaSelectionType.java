package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;
import me.aleksilassila.litematica.printer.core.spatial.PlayerHeightFilter;

/** Area-selection options for filling and fluid removal, not schematic printing. */
public enum AreaSelectionType implements ConfigOptionListEntry<AreaSelectionType> {
    ALL("areaSelectionType.all", "selection", PlayerHeightFilter.ALL),
    BELOW_PLAYER("areaSelectionType.belowPlayer", "belowPlayer",
            PlayerHeightFilter.BELOW_PLAYER),
    ABOVE_PLAYER("areaSelectionType.abovePlayer", "abovePlayer",
            PlayerHeightFilter.ABOVE_PLAYER);

    private final I18n i18n;
    private final String savedValue;
    private final PlayerHeightFilter heightFilter;

    AreaSelectionType(String translationKey, String savedValue, PlayerHeightFilter heightFilter) {
        this.i18n = I18n.of(translationKey);
        this.savedValue = savedValue;
        this.heightFilter = heightFilter;
    }

    public PlayerHeightFilter heightFilter() {
        return heightFilter;
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }

    @Override
    public String getStringValue() {
        // Preserve existing configs independently of the new dropdown labels.
        return savedValue;
    }

    @Override
    public AreaSelectionType fromString(String name) {
        // Both old choices already obeyed the global render layer.
        if ("renderLayer".equalsIgnoreCase(name)) return ALL;
        return ConfigOptionListEntry.super.fromString(name);
    }
}
