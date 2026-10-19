package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SurfaceCmdTest {

    @Test
    void listsConfiguredSurfacesWhenNoArgumentsAreProvided() {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        ModerationSettings settings = mock(ModerationSettings.class);
        when(plugin.messages()).thenReturn(messages);
        when(plugin.settings()).thenReturn(settings);
        when(settings.surfaces()).thenReturn(new SurfaceSettings(
                SurfaceSettings.SurfaceMode.BLOCK,
                SurfaceSettings.SurfaceMode.OFF,
                SurfaceSettings.SurfaceMode.CENSOR,
                SurfaceSettings.SurfaceMode.MONITOR,
                List.of("msg", "tell")
        ));

        CommandSender sender = mock(CommandSender.class);
        SurfaceCmd cmd = new SurfaceCmd(plugin);
        cmd.execute(sender, "nmod", new String[]{"surface"});

        verify(messages).send(sender, "surface.title");
        verify(messages).send(sender, "surface.row", Map.of("surface", "sign", "mode", "block"));
        verify(messages).send(sender, "surface.row", Map.of("surface", "book", "mode", "off"));
        verify(messages).send(sender, "surface.row", Map.of("surface", "anvil", "mode", "censor"));
        verify(messages).send(sender, "surface.row", Map.of("surface", "command", "mode", "monitor"));
        verify(messages).send(sender, "surface.commands", Map.of("commands", "msg, tell"));
    }

    @Test
    void updatesSingleSurfaceOrAllSurfacesAndReloads() {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);

        CommandSender sender = mock(CommandSender.class);
        SurfaceCmd cmd = new SurfaceCmd(plugin);

        cmd.execute(sender, "nmod", new String[]{"surface", "sign", "censor"});
        assertEquals("censor", config.getString("moderation.surfaces.sign"));
        verify(plugin).saveAndReload();
        verify(messages).send(sender, "surface.updated", Map.of("surface", "sign", "mode", "censor"));

        cmd.execute(sender, "nmod", new String[]{"surface", "all", "block"});
        for (String name : SurfaceSettings.SURFACE_NAMES) {
            assertEquals("block", config.getString("moderation.surfaces." + name));
        }
        verify(messages).send(sender, "surface.updated", Map.of("surface", "all", "mode", "block"));
    }

    @Test
    void rejectsUnknownSurfaceOrModeWithoutSaving() {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        when(plugin.messages()).thenReturn(messages);

        CommandSender sender = mock(CommandSender.class);
        SurfaceCmd cmd = new SurfaceCmd(plugin);

        cmd.execute(sender, "nmod", new String[]{"surface", "chat", "block"});
        verify(messages).send(sender, "surface.usage", Map.of("label", "nmod"));
        verify(plugin, never()).saveAndReload();
    }

    @Test
    void completesSurfaceNamesAndModes() {
        SurfaceCmd cmd = new SurfaceCmd(mock(NeoModerationPlugin.class));
        assertEquals(List.of("sign"), cmd.onTabComplete(mock(CommandSender.class), new String[]{"surface", "si"}));
        assertEquals(List.of("monitor"), cmd.onTabComplete(mock(CommandSender.class), new String[]{"surface", "sign", "mo"}));
    }
}
