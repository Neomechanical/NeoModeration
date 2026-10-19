package com.neomechanical.neomoderation.messages;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.List;

/**
 * Builds clickable chat lines from text {@link MessageService} has already
 * formatted. Players get components (click to run, suggest or open a link,
 * hover for help); the console gets the same text. Text arrives formatted, so
 * this class adds no colours and never re-reads {@code &} codes: a player's
 * "&k" quoted in a staff alert stays "&k".
 */
public final class MenuRenderer {
    /** RUN executes, SUGGEST pre-fills chat, OPEN_URL opens a link, NONE is static text. */
    public enum ClickAction {
        RUN,
        SUGGEST,
        OPEN_URL,
        NONE
    }

    /** Both renderings of one line: components for players, plain text for the console; {@code null} leaves one out. */
    public record Line(BaseComponent[] player, String console) {
        /** The same line, reading {@code text} on the console, which cannot click; {@code null} for none. */
        public Line forConsole(String text) {
            return new Line(player, text);
        }
    }

    /** One clickable part of a line, such as a [Resolve] button. */
    public record Button(String text, String target, String hover, ClickAction action) {
    }

    private MenuRenderer() {
    }

    /** A line only the console reads, such as the command a player would have clicked. */
    public static Line consoleOnly(String formatted) {
        return new Line(null, formatted);
    }

    /** Each button's text with the command it runs, one per line, for the console. */
    public static String withCommands(List<Button> parts) {
        StringBuilder text = new StringBuilder();
        for (Button part : parts) {
            if (part.target() == null || part.target().isBlank() || part.action() == ClickAction.NONE) {
                continue;
            }
            text.append(text.isEmpty() ? "" : "\n").append("  ").append(part.text().stripTrailing())
                    .append(' ').append(part.target().strip());
        }
        return text.toString();
    }

    /** Static line (header, divider, hint): same text both forms. */
    public static Line text(String formatted) {
        return new Line(components(formatted), formatted);
    }

    /** Whole-line clickable entry (such as a case row or preset option). */
    public static Line clickableLine(String formatted, String target, String hover, ClickAction action) {
        return buttons(List.of(new Button(formatted, target, hover, action)));
    }

    /** One line made of several parts, each with its own click, such as "[off] [monitor] [block]". */
    public static Line buttons(List<Button> parts) {
        TextComponent root = new TextComponent();
        StringBuilder console = new StringBuilder();
        for (Button part : parts) {
            TextComponent piece = new TextComponent();
            for (BaseComponent component : components(part.text())) {
                piece.addExtra(component);
            }
            attach(piece, part);
            root.addExtra(piece);
            console.append(part.text());
        }
        return new Line(new BaseComponent[]{root}, console.toString());
    }

    /**
     * Menu entry: a clickable usage followed by its description; hovering the
     * usage shows {@code hover}. All three arrive formatted.
     */
    public static Line entry(String usage, String description, String hover, String target, ClickAction action) {
        return buttons(List.of(
                new Button(usage, target, hover, action),
                new Button(description, "", "", ClickAction.NONE)));
    }

    /** Section heading, e.g. "Get started". */
    public static Line section(String formatted) {
        return text(formatted);
    }

    /**
     * Components for formatted text, with no click of their own: the chat API turns
     * anything shaped like a link into one, and a link a player wrote must not
     * become something staff can click inside an alert.
     */
    private static BaseComponent[] components(String formatted) {
        BaseComponent[] components = TextComponent.fromLegacyText(formatted);
        for (BaseComponent component : components) {
            component.setClickEvent(null);
        }
        return components;
    }

    private static void attach(TextComponent piece, Button part) {
        if (part.action() == ClickAction.NONE || part.target() == null || part.target().isBlank()) {
            return;
        }
        ClickEvent.Action click = switch (part.action()) {
            case RUN -> ClickEvent.Action.RUN_COMMAND;
            case SUGGEST -> ClickEvent.Action.SUGGEST_COMMAND;
            case OPEN_URL -> ClickEvent.Action.OPEN_URL;
            case NONE -> throw new IllegalStateException("unreachable");
        };
        piece.setClickEvent(new ClickEvent(click, part.target()));
        if (part.hover() != null && !part.hover().isBlank()) {
            piece.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(part.hover())));
        }
    }
}
