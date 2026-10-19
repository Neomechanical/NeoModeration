package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresetCmdTest {
    @Test
    void aCloudThresholdPresetDoesNotSilentlyReplaceTheServersPunishmentPolicy() {
        YamlConfiguration config = new YamlConfiguration();
        List<Map<?, ?>> originalActions = List.of(Map.of("type", "KICK"));
        config.set("moderation.actions", originalActions);

        NeoModerationPlugin plugin = mock(NeoModerationPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        TestPlugin.editsConfigInMemory(plugin);
        when(plugin.settings()).thenAnswer(ignored -> ModerationSettings.from(new BukkitConfigView(config)));
        when(plugin.messages()).thenReturn(mock(MessageService.class));

        new PresetCmd(plugin).execute(mock(CommandSender.class), "nmod", new String[]{"preset", "family"});

        assertEquals(originalActions, config.getMapList("moderation.actions"));
        assertEquals(0.55D, config.getDouble("moderation.categories.harassment"));
    }
}
