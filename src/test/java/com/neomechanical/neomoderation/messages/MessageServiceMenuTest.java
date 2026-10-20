package com.neomechanical.neomoderation.messages;

import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageServiceMenuTest {
    private static MessageService messages() {
        YamlConfiguration empty = new YamlConfiguration();
        return MessageService.of(empty, empty, MessageService.DEFAULT_LOCALE);
    }

    private static MessageService messagesWithChrome() {
        YamlConfiguration fallback = new YamlConfiguration();
        fallback.set("prefix", "[NMod]");
        fallback.set("help.footer", "{prefix} --");
        return MessageService.of(fallback, new YamlConfiguration(), MessageService.DEFAULT_LOCALE);
    }

    @Test
    void consoleSenderGetsLegacyLines() {
        CommandSender console = mock(CommandSender.class);
        List<MenuRenderer.Line> lines = List.of(MenuRenderer.text("§aHello"));
        messages().sendMenu(console, lines);
        verify(console).sendMessage("§aHello");
    }

    @Test
    void playerGetsComponents() {
        Player player = mock(Player.class);
        Player.Spigot spigot = mock(Player.Spigot.class);
        when(player.spigot()).thenReturn(spigot);
        List<MenuRenderer.Line> lines = List.of(MenuRenderer.text("§aHello"));
        messages().sendMenu(player, lines);
        ArgumentCaptor<BaseComponent[]> captor = ArgumentCaptor.forClass(BaseComponent[].class);
        verify(spigot).sendMessage(captor.capture());
        assertEquals("Hello", BaseComponent.toPlainText(captor.getValue()));
    }

    @Test
    void sendFooterDeliversDivider() {
        CommandSender console = mock(CommandSender.class);
        messagesWithChrome().sendFooter(console);
        verify(console).sendMessage("[NMod] --");
    }
}
