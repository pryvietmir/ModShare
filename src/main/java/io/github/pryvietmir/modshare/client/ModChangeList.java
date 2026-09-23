package io.github.pryvietmir.modshare.client;

import io.github.pryvietmir.modshare.common.LocalMod;
import io.github.pryvietmir.modshare.common.Manifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mods a server wants to download or remove, each with a choice the player cycles by clicking the row:
 * apply, skip this time, or always ignore.
 */
public class ModChangeList extends ObjectSelectionList<ModChangeList.Row> {
    public enum Choice {
        APPLY("modshare.choice.apply", 0x55FF55),
        SKIP("modshare.choice.skip", 0xAAAAAA),
        IGNORE("modshare.choice.ignore", 0xFFAA55);

        final String translationKey;
        final int color;

        Choice(String translationKey, int color) {
            this.translationKey = translationKey;
            this.color = color;
        }

        Choice next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final int REPLACED_COLOR = 0xFFFF55;

    private final SyncPlan plan;
    /** Choice per change, keyed by the Manifest.Entry or LocalMod; shared with the screen */
    private final Map<Object, Choice> choices;

    public ModChangeList(Minecraft minecraft, int width, int height, int y, SyncPlan plan, Map<Object, Choice> choices) {
        super(minecraft, width, height, y, 14);
        this.plan = plan;
        this.choices = choices;
        // Hidden mods are installed by the server and never listed
        for (Manifest.Entry entry : plan.toDownload()) {
            if (!entry.hidden()) addEntry(new Row(entry, "+ " + entry.file(), 0x55FF55, sizeOf(entry)));
        }
        for (LocalMod mod : plan.toRemove()) addEntry(new Row(mod, "- " + mod.fileName(), 0xFF5555, ""));
    }

    @Override
    public int getRowWidth() {
        return Math.min(width - 40, 380);
    }

    /** Downloads that will happen: the ones the player keeps on Apply, plus every hidden mod. */
    public static List<Manifest.Entry> selectedDownloads(SyncPlan plan, Map<Object, Choice> choices) {
        return plan.toDownload().stream().filter(entry -> entry.hidden() || choices.get(entry) == Choice.APPLY).toList();
    }

    /** A removal forced by a selected download of another version of the same mod. */
    boolean isReplaced(Object change) {
        return change instanceof LocalMod mod && SyncPlan.replacedBy(mod, selectedDownloads(plan, choices));
    }

    private static String sizeOf(Manifest.Entry entry) {
        return String.format(Locale.ROOT, "%.1f MB", entry.size() / (1024.0 * 1024.0));
    }

    public class Row extends ObjectSelectionList.Entry<Row> {
        private final Object change;
        private final String label;
        private final int labelColor;
        private final String details;

        Row(Object change, String label, int labelColor, String details) {
            this.change = change;
            this.label = label;
            this.labelColor = labelColor;
            this.details = details;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            Font font = minecraft.font;
            boolean replaced = isReplaced(change);
            Choice choice = choices.get(change);
            Component state = replaced
                    ? Component.translatable("modshare.choice.replaced").withColor(REPLACED_COLOR)
                    : Component.translatable(choice.translationKey).withColor(choice.color);

            int stateWidth = font.width(state);
            int detailsWidth = details.isEmpty() ? 0 : font.width(details) + 8;
            int textY = top + (height - font.lineHeight) / 2 + 1;
            int nameWidth = width - stateWidth - detailsWidth - 12;
            String name = font.plainSubstrByWidth(label, nameWidth);
            if (name.length() < label.length()) name = font.plainSubstrByWidth(label, nameWidth - font.width("...")) + "...";

            // Skipped and ignored changes are dimmed so the ones that will happen stand out
            boolean active = replaced || choice == Choice.APPLY;
            graphics.drawString(font, name, left + 2, textY, active ? labelColor : 0x777777);
            if (!details.isEmpty()) graphics.drawString(font, details, left + width - stateWidth - detailsWidth - 2, textY, 0x888888);
            graphics.drawString(font, state, left + width - stateWidth - 2, textY, 0xFFFFFF);
            if (hovering && !replaced) graphics.fill(left, top + height - 1, left + width - 4, top + height, 0x40FFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isReplaced(change)) choices.put(change, choices.get(change).next());
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(label);
        }
    }
}
