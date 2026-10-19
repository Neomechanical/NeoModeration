package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeoModerationCommandTest {
    private final NeoModerationPlugin plugin = TestPlugin.with(new YamlConfiguration());
    private final NeoModerationCommand command = new NeoModerationCommand(plugin);
    private final Command bukkitCommand = mock(Command.class);

    @Test
    void aModeratorCanReadCasesButIsToldSettingsBelongToAdmins() {
        CommandSender moderator = sender(Permissions.MODERATE);

        command.onCommand(moderator, bukkitCommand, "nmod", new String[]{"cases"});
        command.onCommand(moderator, bukkitCommand, "nmod", new String[]{"on"});

        assertEquals(List.of("[NeoMod] Nothing caught yet.", "[NeoMod] Only admins can use /nmod on."),
                TestPlugin.lines(moderator));
        verify(plugin, never()).saveAndReload();
    }

    @Test
    void aModeratorIsOfferedOnlyTheCommandsTheyMayRun() {
        CommandSender moderator = sender(Permissions.MODERATE);

        assertEquals(List.of("case", "cases", "help", "info", "mute", "privacy", "status", "test", "unmute"),
                command.onTabComplete(moderator, bukkitCommand, "nmod", new String[]{""}));
    }

    @Test
    void someoneWithNeitherPermissionGetsNothing() {
        CommandSender player = sender();

        command.onCommand(player, bukkitCommand, "nmod", new String[]{"cases"});

        assertEquals(List.of("[NeoMod] You don't have permission to use NeoModeration."), TestPlugin.lines(player));
        assertEquals(List.of(), command.onTabComplete(player, bukkitCommand, "nmod", new String[]{""}));
    }

    @Test
    void aTypoIsNamedBeforeTheHelp() {
        CommandSender admin = sender(Permissions.ADMIN, Permissions.MODERATE);

        command.onCommand(admin, bukkitCommand, "nmod", new String[]{"stauts"});

        List<String> lines = TestPlugin.lines(admin);
        assertEquals("[NeoMod] There is no /nmod stauts. Here is what there is:", lines.get(0));
        assertTrue(lines.size() > 3, String.join("\n", lines));
    }

    private static CommandSender sender(String... permissions) {
        CommandSender sender = mock(CommandSender.class);
        for (String permission : permissions) {
            when(sender.hasPermission(permission)).thenReturn(true);
        }
        return sender;
    }
}
