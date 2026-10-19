package com.neomechanical.neomoderation.messages;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuRendererTest {
    @Test
    void runEntryClicksToExecuteAndHoversHelp() {
        MenuRenderer.Line line = MenuRenderer.entry("/nmod status", "protection at a glance",
                "/nmod status", MenuRenderer.ClickAction.RUN);
        TextComponent root = (TextComponent) line.player()[0];
        assertNotNull(root.getClickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, root.getClickEvent().getAction());
        assertEquals("/nmod status", root.getClickEvent().getValue());
        assertNotNull(root.getHoverEvent());
        assertEquals(HoverEvent.Action.SHOW_TEXT, root.getHoverEvent().getAction());
        assertTrue(BaseComponent.toPlainText(root).contains("/nmod status"));
        assertTrue(BaseComponent.toPlainText(root).contains("protection at a glance"));
    }

    @Test
    void suggestEntryPrefillsChat() {
        MenuRenderer.Line line = MenuRenderer.entry("/nmod word", "banned words",
                "/nmod word ", MenuRenderer.ClickAction.SUGGEST);
        TextComponent root = (TextComponent) line.player()[0];
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, root.getClickEvent().getAction());
        assertEquals("/nmod word ", root.getClickEvent().getValue());
    }

    @Test
    void staticEntryHasNoClickEvent() {
        MenuRenderer.Line line = MenuRenderer.entry("/nmod x", "y", "", MenuRenderer.ClickAction.NONE);
        assertNull(((TextComponent) line.player()[0]).getClickEvent());
    }

    @Test
    void consoleFormMatchesLegacyColors() {
        MenuRenderer.Line line = MenuRenderer.entry("&b/nmod status", "&7desc",
                "/nmod status", MenuRenderer.ClickAction.RUN);
        assertEquals("  §b/nmod status - §7desc", line.console());
    }

    @Test
    void hexColorsSurviveTranslation() {
        MenuRenderer.Line line = MenuRenderer.text("&x&9&c&a&8&f&fNeoModeration");
        // Component form: the 1.8 compile API predates hex parsing, so only the
        // plain text survives here; modern runtimes parse the hex (verified live).
        assertEquals("NeoModeration", BaseComponent.toPlainText(line.player()));
        assertEquals("§x§9§c§a§8§f§fNeoModeration", line.console());
    }

    @Test
    void clickableLineBuildsComponentAndPlainConsole() {
        MenuRenderer.Line line = MenuRenderer.clickableLine(
                "&bfamily &8- &7strict",
                "/nmod preset family",
                "&b/nmod preset family",
                MenuRenderer.ClickAction.RUN);
        TextComponent root = (TextComponent) line.player()[0];
        assertEquals(ClickEvent.Action.RUN_COMMAND, root.getClickEvent().getAction());
        assertEquals("/nmod preset family", root.getClickEvent().getValue());
        assertEquals("§bfamily §8- §7strict", line.console());
    }
}
