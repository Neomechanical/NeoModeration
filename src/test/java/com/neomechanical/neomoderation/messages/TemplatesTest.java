package com.neomechanical.neomoderation.messages;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplatesTest {
    private static final Map<String, String> COLORS = Map.of("brand", "&x&9&c&a&8&f&f", "muted", "&7");

    @Test
    void colourTokensAndCodesBelongToTheTemplate() {
        assertEquals("§x§9§c§a§8§f§fNeoMod §7ok",
                Templates.render("{#brand}NeoMod {#muted}ok", Map.of(), COLORS::get));
    }

    @Test
    void aPlayersTextIsInsertedLiterally() {
        String rendered = Templates.render("&7{player} said &f{message}",
                Map.of("player", "Steve", "message", "&kboo {player} {prefix}"), COLORS::get);
        assertEquals("§7Steve said §f&kboo {player} {prefix}", rendered);
    }

    @Test
    void anUnknownPlaceholderStaysVisible() {
        assertEquals("hi {who}", Templates.render("hi {who}", Map.of("other", "x"), COLORS::get));
    }

    @Test
    void anUnknownColourTokenIsDropped() {
        assertEquals("plain", Templates.render("{#nope}plain", Map.of(), COLORS::get));
    }
}
