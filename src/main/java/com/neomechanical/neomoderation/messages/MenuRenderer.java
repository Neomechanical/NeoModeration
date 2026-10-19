package com.neomechanical.neomoderation.messages;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Builds clickable menu lines. Player form uses Bungee components (click to
 * run/suggest, hover for help); console form is the same content as legacy
 * text. All wording and colors arrive as arguments so translators own every
 * word via the locale — this class never contains user-facing text.
 */
public final class MenuRenderer {
    /** RUN executes immediately; SUGGEST pre-fills chat; NONE is static text. */
    public enum ClickAction {
        RUN,
        SUGGEST,
        NONE
    }

    /** Both renderings of one line: components for players, legacy text for console. */
    public record Line(BaseComponent[] player, String console) {
    }

    private MenuRenderer() {
    }

    private static final Pattern HEX_SEQUENCE = Pattern.compile("&x((?:&[0-9a-fA-F]){6})");

    /**
     * Translates {@code &} color codes including {@code &x&R&R&G&G&B&B} hex
     * sequences. Hex is handled here rather than by Bukkit because the compile
     * API predates hex support — the runtime behavior is identical on modern
     * servers, and this keeps unit tests honest.
     */
    static String translate(String legacy) {
        if (legacy == null) {
            return "";
        }
        String hexed = HEX_SEQUENCE.matcher(legacy)
                .replaceAll((MatchResult match) -> "§x" + match.group(1).replace("&", "§"));
        return ChatColor.translateAlternateColorCodes('&', hexed);
    }

    /** Static line (header, divider, hint): same text both forms. */
    public static Line text(String legacy) {
        String translated = translate(legacy);
        return new Line(TextComponent.fromLegacyText(translated), translated);
    }

    /** Whole-line clickable entry (such as a case row or preset option). */
    public static Line clickableLine(String legacyText, String command, String hoverText, ClickAction action) {
        String translated = translate(legacyText);
        TextComponent root = new TextComponent();
        for (BaseComponent part : TextComponent.fromLegacyText(translated)) {
            root.addExtra(part);
        }
        if (action != ClickAction.NONE) {
            ClickEvent.Action click = action == ClickAction.RUN
                    ? ClickEvent.Action.RUN_COMMAND
                    : ClickEvent.Action.SUGGEST_COMMAND;
            root.setClickEvent(new ClickEvent(click, command));
            root.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    TextComponent.fromLegacyText(translate(hoverText))));
        }
        return new Line(new BaseComponent[]{root}, translated);
    }

    /**
     * Menu entry rendered as "  {usage} - {desc}": only the usage part is
     * clickable, with hover showing usage + description.
     */
    public static Line entry(String usage, String description, String command, ClickAction action) {
        TextComponent root = new TextComponent();
        for (BaseComponent part : TextComponent.fromLegacyText(translate("  " + usage))) {
            root.addExtra(part);
        }
        if (action != ClickAction.NONE) {
            ClickEvent.Action click = action == ClickAction.RUN
                    ? ClickEvent.Action.RUN_COMMAND
                    : ClickEvent.Action.SUGGEST_COMMAND;
            root.setClickEvent(new ClickEvent(click, command));
            root.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    TextComponent.fromLegacyText(translate(usage + "\n" + description))));
        }
        for (BaseComponent part : TextComponent.fromLegacyText(translate(" - " + description))) {
            root.addExtra(part);
        }
        String console = translate("  " + usage + " - " + description);
        return new Line(new BaseComponent[]{root}, console);
    }

    /** Section heading, e.g. "Getting started". */
    public static Line section(String legacy) {
        return text(legacy);
    }

    public static List<Line> lines(Line... lines) {
        return List.of(lines);
    }
}
