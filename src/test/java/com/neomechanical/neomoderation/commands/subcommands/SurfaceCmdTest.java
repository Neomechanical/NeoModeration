package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SurfaceCmdTest {

    @Test
    void theConsoleReadsEachSurfaceSettingInWords() {
        NeoModerationPlugin plugin = TestPlugin.with(configuredSurfaces());
        CommandSender console = mock(CommandSender.class);

        new SurfaceCmd(plugin).execute(console, "nmod", new String[]{"surface"});

        List<String> lines = TestPlugin.lines(console);
        assertEquals(List.of(
                "[NeoMod] Where local rules apply.",
                "  Chat: always checked",
                "  Signs: block",
                "  Books: off",
                "  Item names: star out",
                "  Commands: alert only",
                "  Commands checked: msg, tell",
                "[NeoMod] Use /nmod surface <sign|book|anvil|command|all> <off|monitor|censor|block>."), lines);
    }

    @Test
    void aPlayerClicksButtonsWithTheCurrentSettingHighlighted() {
        NeoModerationPlugin plugin = TestPlugin.with(configuredSurfaces());
        org.bukkit.entity.Player player = TestPlugin.player("Ana", "en_us");
        org.bukkit.entity.Player.Spigot spigot = mock(org.bukkit.entity.Player.Spigot.class);
        when(player.spigot()).thenReturn(spigot);

        new SurfaceCmd(plugin).execute(player, "nmod", new String[]{"surface"});

        org.mockito.ArgumentCaptor<net.md_5.bungee.api.chat.BaseComponent[]> sent =
                org.mockito.ArgumentCaptor.forClass(net.md_5.bungee.api.chat.BaseComponent[].class);
        verify(spigot, org.mockito.Mockito.atLeastOnce()).sendMessage(sent.capture());
        MessageService messages = plugin.messages();
        String signs = sent.getAllValues().stream()
                .map(net.md_5.bungee.api.chat.TextComponent::toLegacyText)
                .filter(line -> line.contains("Signs")).findFirst().orElseThrow();
        assertTrue(signs.contains(messages.format(player, "surface.button-current", Map.of("mode", "block"))), signs);
        assertTrue(signs.contains(messages.format(player, "surface.button", Map.of("mode", "off"))), signs);
        assertTrue(sent.getAllValues().stream().map(net.md_5.bungee.api.chat.TextComponent::toLegacyText)
                .noneMatch(line -> line.contains("Use /nmod surface")), "the usage line is for the console only");
    }

    private static YamlConfiguration configuredSurfaces() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.surfaces.sign", "block");
        config.set("moderation.surfaces.book", "off");
        config.set("moderation.surfaces.anvil", "censor");
        config.set("moderation.surfaces.command", "monitor");
        config.set("moderation.surfaces.scannedCommands", List.of("msg", "tell"));
        return config;
    }

    @Test
    void updatesSingleSurfaceOrAllSurfacesAndReloads() {
        YamlConfiguration config = new YamlConfiguration();
        NeoModerationPlugin plugin = TestPlugin.with(config);
        when(plugin.getConfig()).thenReturn(config);
        CommandSender sender = mock(CommandSender.class);
        SurfaceCmd cmd = new SurfaceCmd(plugin);

        cmd.execute(sender, "nmod", new String[]{"surface", "sign", "censor"});
        assertEquals("censor", config.getString("moderation.surfaces.sign"));
        verify(plugin).editConfig(any());

        cmd.execute(sender, "nmod", new String[]{"surface", "all", "block"});
        for (String name : SurfaceSettings.SURFACE_NAMES) {
            assertEquals("block", config.getString("moderation.surfaces." + name));
        }
        assertEquals(List.of("[NeoMod] Signs: star out.", "[NeoMod] All four: block."), TestPlugin.lines(sender));
    }

    @Test
    void rejectsUnknownSurfaceOrModeWithoutSaving() {
        NeoModerationPlugin plugin = TestPlugin.with(new YamlConfiguration());
        CommandSender sender = mock(CommandSender.class);

        new SurfaceCmd(plugin).execute(sender, "nmod", new String[]{"surface", "chat", "block"});

        assertEquals(List.of("[NeoMod] Use /nmod surface <sign|book|anvil|command|all> <off|monitor|censor|block>."),
                TestPlugin.lines(sender));
        verify(plugin, never()).editConfig(any());
    }

    @Test
    void completesSurfaceNamesAndModes() {
        SurfaceCmd cmd = new SurfaceCmd(mock(NeoModerationPlugin.class));
        assertEquals(List.of("sign"), cmd.onTabComplete(mock(CommandSender.class), new String[]{"surface", "si"}));
        assertEquals(List.of("monitor"), cmd.onTabComplete(mock(CommandSender.class), new String[]{"surface", "sign", "mo"}));
    }
}
