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
    private static TextComponent usagePart(MenuRenderer.Line line) {
        return (TextComponent) ((TextComponent) line.player()[0]).getExtra().get(0);
    }

    @Test
    void runEntryClicksToExecuteAndHoversHelp() {
        MenuRenderer.Line line = MenuRenderer.entry("/nmod status", "protection at a glance",
                "/nmod status\nprotection at a glance", "/nmod status", MenuRenderer.ClickAction.RUN);
        TextComponent usage = usagePart(line);
        assertNotNull(usage.getClickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, usage.getClickEvent().getAction());
        assertEquals("/nmod status", usage.getClickEvent().getValue());
        assertNotNull(usage.getHoverEvent());
        assertEquals(HoverEvent.Action.SHOW_TEXT, usage.getHoverEvent().getAction());
        assertTrue(BaseComponent.toPlainText(line.player()).contains("/nmod status"));
        assertTrue(BaseComponent.toPlainText(line.player()).contains("protection at a glance"));
    }

    @Test
    void suggestEntryPrefillsChat() {
        MenuRenderer.Line line = MenuRenderer.entry("/nmod word", "banned words", "",
                "/nmod word ", MenuRenderer.ClickAction.SUGGEST);
        TextComponent usage = usagePart(line);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, usage.getClickEvent().getAction());
        assertEquals("/nmod word ", usage.getClickEvent().getValue());
    }

    @Test
    void staticEntryHasNoClickEvent() {
        MenuRenderer.Line line = MenuRenderer.entry("/nmod x", "y", "", "", MenuRenderer.ClickAction.NONE);
        assertNull(usagePart(line).getClickEvent());
    }

    @Test
    void consoleFormJoinsThePartsAsFormatted() {
        MenuRenderer.Line line = MenuRenderer.entry("  §b/nmod status", "  §7desc", "",
                "/nmod status", MenuRenderer.ClickAction.RUN);
        assertEquals("  §b/nmod status  §7desc", line.console());
    }

    @Test
    void hexColorsSurviveTranslation() {
        assertEquals("§x§9§c§a§8§f§fNeoModeration", Templates.translateCodes("&x&9&c&a&8&f&fNeoModeration"));
        MenuRenderer.Line line = MenuRenderer.text("§x§9§c§a§8§f§fNeoModeration");
        // Component form: the 1.8 compile API predates hex parsing, so only the
        // plain text survives here; modern runtimes parse the hex (verified live).
        assertEquals("NeoModeration", BaseComponent.toPlainText(line.player()));
        assertEquals("§x§9§c§a§8§f§fNeoModeration", line.console());
    }

    @Test
    void clickableLineBuildsComponentAndPlainConsole() {
        MenuRenderer.Line line = MenuRenderer.clickableLine(
                "§bfamily §8- §7strict",
                "/nmod preset family",
                "§b/nmod preset family",
                MenuRenderer.ClickAction.RUN);
        TextComponent part = (TextComponent) ((TextComponent) line.player()[0]).getExtra().get(0);
        assertEquals(ClickEvent.Action.RUN_COMMAND, part.getClickEvent().getAction());
        assertEquals("/nmod preset family", part.getClickEvent().getValue());
        assertEquals("§bfamily §8- §7strict", line.console());
    }

    @Test
    void textAPlayerWroteKeepsItsAmpersandCodes() {
        MenuRenderer.Line line = MenuRenderer.clickableLine("§7said §f&kboo", "/nmod cases Steve", "",
                MenuRenderer.ClickAction.RUN);
        assertEquals("§7said §f&kboo", line.console());
        assertTrue(BaseComponent.toPlainText(line.player()).contains("&kboo"));
    }

    @Test
    void aRowOfButtonsGivesEachPartItsOwnClick() {
        MenuRenderer.Line line = MenuRenderer.buttons(java.util.List.of(
                new MenuRenderer.Button("[off] ", "/nmod surface sign off", "", MenuRenderer.ClickAction.RUN),
                new MenuRenderer.Button("[block]", "/nmod surface sign block", "", MenuRenderer.ClickAction.RUN),
                new MenuRenderer.Button(" docs", "https://example.org", "", MenuRenderer.ClickAction.OPEN_URL)));
        java.util.List<BaseComponent> parts = ((TextComponent) line.player()[0]).getExtra();
        assertEquals("/nmod surface sign off", parts.get(0).getClickEvent().getValue());
        assertEquals("/nmod surface sign block", parts.get(1).getClickEvent().getValue());
        assertEquals(ClickEvent.Action.OPEN_URL, parts.get(2).getClickEvent().getAction());
        assertEquals("[off] [block] docs", line.console());
    }

    @Test
    void aLinkAPlayerWroteNeverBecomesAClickOfItsOwnInsideAnAlert() {
        MenuRenderer.Line line = MenuRenderer.clickableLine("Blocked Steve in chat: grabify.link/abc",
                "/nmod cases Steve page 1", "Click to see Steve's cases", MenuRenderer.ClickAction.RUN);

        java.util.List<BaseComponent> all = new java.util.ArrayList<>();
        java.util.Deque<BaseComponent> todo = new java.util.ArrayDeque<>(java.util.List.of(line.player()));
        while (!todo.isEmpty()) {
            BaseComponent component = todo.pop();
            all.add(component);
            if (component.getExtra() != null) {
                todo.addAll(component.getExtra());
            }
        }
        for (BaseComponent component : all) {
            ClickEvent click = component.getClickEvent();
            assertTrue(click == null || click.getAction() == ClickEvent.Action.RUN_COMMAND,
                    "a part of the line opens " + (click == null ? "" : click.getValue()));
        }
    }
}
