package me.aleksilassila.litematica.printer.render;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.world.item.ItemStack;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.util.GuiUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;

public class MissingMaterialHudRenderer implements IInfoHudRenderer
{
    public static final MissingMaterialHudRenderer INSTANCE = new MissingMaterialHudRenderer();

    private static final int LINE_HEIGHT = 16;
    private static final int MAX_DISPLAY_ITEMS = 10;
    private static final int BG_COLOR = 0xA0000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_COLOR_GRAY = 0xFF808080;

    @Override
    public boolean getShouldRenderText(RenderPhase phase)
    {
        return false;
    }

    @Override
    public boolean getShouldRenderCustom()
    {
        if (!Configs.Core.MISSING_MATERIAL_HUD.getBooleanValue()) return false;
        return MissingMaterialTracker.getInstance().hasMissing();
    }

    @Override
    public boolean shouldRenderInGuis()
    {
        return true;
    }

    @Override
    public List<String> getText(RenderPhase phase)
    {
        return List.of();
    }

    public int render(GuiGraphicsExtractor drawContext, int xOffset, int yOffset, HudAlignment alignment)
    {
        MissingMaterialTracker tracker = MissingMaterialTracker.getInstance();
        List<MissingMaterialTracker.Entry> missing = tracker.getMissing();
        if (missing.isEmpty()) return 0;

        int totalTypes = tracker.size();
        int displayCount = Math.min(missing.size(), MAX_DISPLAY_ITEMS);
        boolean showOverflow = missing.size() > MAX_DISPLAY_ITEMS;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        int bgMargin = 2;
        int contentHeight = (displayCount * LINE_HEIGHT) + LINE_HEIGHT;
        if (showOverflow) contentHeight += LINE_HEIGHT;

        int maxTextLength = 0;
        for (int i = 0; i < displayCount; i++) {
            maxTextLength = Math.max(maxTextLength, font.width(getItemName(missing.get(i))));
        }
        String title = String.format(Language.getInstance().getOrDefault("litematica-printer.hud.missing.title"), totalTypes);
        int titleWidth = font.width(title);
        maxTextLength = Math.max(maxTextLength, titleWidth);
        final int maxLineLength = maxTextLength + 20;

        int posX = xOffset + bgMargin;
        switch (alignment) {
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
                posX = GuiUtils.getScaledWindowWidth() - maxLineLength - xOffset - bgMargin;
                break;
            case CENTER:
                posX = (int) (GuiUtils.getScaledWindowWidth() / 2.0 - maxLineLength / 2.0 - xOffset);
                break;
            default:
                break;
        }

        int posY = yOffset + bgMargin;
        {
            int scaledHeight = GuiUtils.getScaledWindowHeight();
            if (alignment == HudAlignment.BOTTOM_RIGHT || alignment == HudAlignment.BOTTOM_LEFT) {
                posY = scaledHeight - posY - contentHeight;
            } else if (alignment == HudAlignment.CENTER) {
                posY = (int) (scaledHeight / 2.0 - contentHeight / 2.0 + posY);
            }
        }

        int x1 = posX - bgMargin;
        int y1 = posY - bgMargin;
        int x2 = x1 + maxLineLength + bgMargin * 2;
        int y2 = y1 + contentHeight + bgMargin;

        drawContext.fill(x1, y1, x2, y2, BG_COLOR);

        drawContext.text(font, title,
                posX + 2, posY + 2, TEXT_COLOR, true);

        int itemIconX = posX;
        int itemTextX = posX + 18;
        int itemY = posY + 16;
        for (int i = 0; i < displayCount; i++) {
            MissingMaterialTracker.Entry entry = missing.get(i);
            ItemStack stack = entry.item.getDefaultInstance();

            drawContext.item(stack, itemIconX, itemY);
            drawContext.itemDecorations(font, stack, itemIconX, itemY);

            String name = getItemName(entry);
            int availableWidth = maxLineLength - 20;
            if (font.width(name) > availableWidth) {
                name = font.plainSubstrByWidth(name, availableWidth - font.width("...")) + "...";
            }
            drawContext.text(font, name, itemTextX, itemY + 4, TEXT_COLOR, true);

            itemY += LINE_HEIGHT;
        }

        if (showOverflow) {
            String overflow = String.format(Language.getInstance().getOrDefault("litematica-printer.hud.missing.overflow"), missing.size() - MAX_DISPLAY_ITEMS);
            drawContext.text(font, overflow,
                    posX + 2, itemY + 4, TEXT_COLOR_GRAY, true);
        }

        return contentHeight + 4;
    }

    private static String getItemName(MissingMaterialTracker.Entry entry)
    {
        return entry.displayName != null
                ? entry.displayName.getString()
                : entry.item.getDefaultInstance().getDisplayName().getString();
    }

}
