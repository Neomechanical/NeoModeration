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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SurfaceCmdTest {

    @Test
    void listsEverySurfaceWithItsSettingHighlighted() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.surfaces.sign", "block");
        config.set("moderation.surfaces.book", "off");
        config.set("moderation.surfaces.anvil", "censor");
        config.set("moderation.surfaces.command", "monitor");
        config.set("moderation.surfaces.scannedCommands", List.of("msg", "tell"));
        NeoModerationPlugin plugin = TestPlugin.with(config);
        CommandSender sender = mock(CommandSender.class);

        new SurfaceCmd(plugin).execute(sender, "nmod", new String[]{"surface"});

        List<String> lines = TestPlugin.lines(sender);
        assertTrue(lines.contains("  Signs: [off] [alert only] [star out] [block] "), String.join("\n", lines));
        assertTrue(lines.contains("  Commands checked: msg, tell"), String.join("\n", lines));
        MessageService messages = plugin.messages();
        String signs = TestPlugin.rawLines(sender).stream().filter(line -> line.contains("Signs")).findFirst().orElseThrow();
        assertTrue(signs.contains(messages.format(sender, "surface.button-current", Map.of("mode", "block"))), signs);
        assertTrue(signs.contains(messages.format(sender, "surface.button", Map.of("mode", "off"))), signs);
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
        verify(plugin).saveAndReload();

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
        verify(plugin, never()).saveAndReload();
    }

    @Test
    void completesSurfaceNamesAndModes() {
        SurfaceCmd cmd = new SurfaceCmd(mock(NeoModerationPlugin.class));
        assertEquals(List.of("sign"), cmd.onTabComplete(mock(CommandSender.class), new String[]{"surface", "si"}));
        assertEquals(List.of("monitor"), cmd.onTabComplete(mock(CommandSender.class), new String[]{"surface", "sign", "mo"}));
    }
}
