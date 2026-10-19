package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpCmdTest {
    private static SubCommand fake(String name) {
        SubCommand cmd = mock(SubCommand.class);
        when(cmd.getName()).thenReturn(name);
        when(cmd.getPermission()).thenReturn("neomoderation.admin");
        return cmd;
    }

    private static List<String> help(Map<String, SubCommand> commands, boolean permitted, String... args) {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(permitted);
        new HelpCmd(TestPlugin.with(new YamlConfiguration()), commands).execute(sender, "nmod", args);
        return TestPlugin.lines(sender);
    }

    @Test
    void pageOneShowsDailyCommandsNotAdmin() {
        List<String> lines = help(Map.of("trial", fake("trial"), "status", fake("status"), "key", fake("key")), true);

        assertTrue(lines.contains("  /nmod trial  start a free 14-day cloud trial"), String.join("\n", lines));
        assertTrue(lines.contains("  /nmod status  what is protected right now"), String.join("\n", lines));
        assertFalse(lines.stream().anyMatch(line -> line.contains("/nmod key")));
        assertTrue(lines.contains("Page 1 of 2. More: /nmod help 2"), String.join("\n", lines));
    }

    @Test
    void pageTwoShowsAdminCommands() {
        List<String> lines = help(Map.of("status", fake("status"), "key", fake("key")), true, "2");

        assertTrue(lines.stream().anyMatch(line -> line.contains("/nmod key")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("/nmod status")));
    }

    @Test
    void pageTwoReachedThroughDispatcherArgConvention() {
        // NeoModerationCommand passes the full args array including the
        // subcommand name, so "/nmod help 2" arrives as {"help", "2"}.
        List<String> lines = help(Map.of("status", fake("status"), "key", fake("key")), true, "help", "2");

        assertTrue(lines.stream().anyMatch(line -> line.contains("/nmod key")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("/nmod status")));
    }

    @Test
    void commandsWithoutPermissionAreHidden() {
        List<String> lines = help(Map.of("status", fake("status")), false);

        assertFalse(lines.stream().anyMatch(line -> line.contains("/nmod status")));
    }

    @Test
    void everyCommandInTheMenuHasItsOwnWords() {
        Map<String, SubCommand> all = new HashMap<>();
        for (String name : List.of("trial", "setup", "status", "mode", "preset", "word", "url", "allow", "action",
                "surface", "test", "cases", "mute", "unmute", "doctor", "discord", "usage", "privacy", "key",
                "cloudmode", "reload", "on", "off")) {
            all.put(name, fake(name));
        }
        List<String> lines = new ArrayList<>(help(all, true));
        lines.addAll(help(all, true, "2"));

        assertTrue(lines.stream().noneMatch(line -> line.contains("help.")), String.join("\n", lines));
        assertTrue(lines.contains("  /nmod on  turn moderation on"), String.join("\n", lines));
    }
}
