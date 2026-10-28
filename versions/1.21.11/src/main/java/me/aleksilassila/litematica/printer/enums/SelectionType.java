package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;
import me.aleksilassila.litematica.printer.core.spatial.PlayerHeightFilter;

public enum SelectionType implements ConfigOptionListEntry<SelectionType> {
    /**
     * 使用投影的选择框进行打印
     */
    LITEMATICA_SELECTION("selectionType.litematica.selection"),
    /**
     * 使用投影的渲染层进行打印
     */
    LITEMATICA_RENDER_LAYER("selectionType.litematica.renderLayer"),
    /**
     * 打印投影选择框中玩家下方的部分
     */
    LITEMATICA_SELECTION_BELOW_PLAYER("selectionType.litematica.selection.belowPlayer"),
    /**
     * 打印投影选择框中玩家上方的部分
     */
    LITEMATICA_SELECTION_ABOVE_PLAYER("selectionType.litematica.selection.abovePlayer");

    private final I18n i18n;

    SelectionType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    public PlayerHeightFilter heightFilter() {
        return switch (this) {
            case LITEMATICA_SELECTION_BELOW_PLAYER -> PlayerHeightFilter.BELOW_PLAYER;
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> PlayerHeightFilter.ABOVE_PLAYER;
            default -> PlayerHeightFilter.ALL;
        };
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
