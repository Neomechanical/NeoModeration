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

    /** Both renderings of one line: components for players, plain text for the console. */
    public record Line(BaseComponent[] player, String console) {
    }

    /** One clickable part of a line, such as a [Resolve] button. */
    public record Button(String text, String target, String hover, ClickAction action) {
    }

    private MenuRenderer() {
    }

    /** Static line (header, divider, hint): same text both forms. */
    public static Line text(String formatted) {
        return new Line(TextComponent.fromLegacyText(formatted), formatted);
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
            for (BaseComponent component : TextComponent.fromLegacyText(part.text())) {
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
