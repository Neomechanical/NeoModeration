package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SetupCmdTest {

    @Test
    void setupWithoutKeyOutputsUsageWithDirectSignupUrl() {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        when(plugin.messages()).thenReturn(messages);
        CommandSender sender = mock(CommandSender.class);

        SetupCmd cmd = new SetupCmd(plugin);
        assertTrue(cmd.getAliases().contains("cloud"), "SetupCmd should support /nmod cloud alias");

        cmd.execute(sender, "nmod", new String[]{"setup"});

        verify(messages).send(eq(sender), eq("setup.usage"), argThat(map ->
                map.containsKey("url") && map.get("url").equals(CloudRecovery.SIGNUP_URL)
        ));
    }

    @Test
    void setupWithValidKeySavesKeyAndEnablesModeration() {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);
        TestPlugin.editsConfigInMemory(plugin);
        CommandSender sender = mock(CommandSender.class);

        SetupCmd cmd = new SetupCmd(plugin);
        cmd.execute(sender, "nmod", new String[]{"setup", "nm_live_test_key_12345"});

        assertEquals(true, config.get("moderation.enabled"));
        assertEquals("nm_live_test_key_12345", config.get("moderation.api.apiKey"));
        verify(plugin).editConfig(any());
        verify(messages).send(eq(sender), eq("setup.done"));
    }

    @Test
    void setupWithExcessivelyLongKeyRejectsWithoutSaving() {
        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        MessageService messages = mock(MessageService.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getConfig()).thenReturn(config);
        CommandSender sender = mock(CommandSender.class);

        String tooLong = "k".repeat(257);
        SetupCmd cmd = new SetupCmd(plugin);
        cmd.execute(sender, "nmod", new String[]{"setup", tooLong});

        verify(messages).send(eq(sender), eq("error.api-key-too-long"), any());
        verify(plugin, never()).editConfig(any());
    }
}
