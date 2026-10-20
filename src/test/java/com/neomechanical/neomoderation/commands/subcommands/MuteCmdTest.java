package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.moderation.PlayerMuteService;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MuteCmdTest {
    private final NeoModerationPlugin plugin = TestPlugin.with(new YamlConfiguration());
    private final PlayerMuteService mutes = mock(PlayerMuteService.class);
    private final Server server = mock(Server.class);
    private final CommandSender staff = mock(CommandSender.class);
    private final Player steve = TestPlugin.player("Steve", "en_us");

    MuteCmdTest() {
        when(plugin.muteService()).thenReturn(mutes);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayerExact("Steve")).thenReturn(steve);
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[0]);
        when(steve.isOnline()).thenReturn(true);
    }

    @Test
    void anOnlinePlayerIsMutedAndTold() {
        new MuteCmd(plugin, false).execute(staff, "nmod", new String[]{"mute", "Steve", "5m"});

        verify(mutes).mute(steve.getUniqueId(), 300);
        assertEquals(List.of("[NeoMod] Muted Steve for 5m."), TestPlugin.lines(staff));
        assertEquals(List.of("[NeoMod] You are muted for 5m."), TestPlugin.lines(steve));
    }

    @Test
    void aPlayerWhoLeftIsFoundAmongThoseTheServerHasStored() {
        OfflinePlayer alex = mock(OfflinePlayer.class);
        UUID alexId = UUID.randomUUID();
        when(alex.getName()).thenReturn("Alex");
        when(alex.getUniqueId()).thenReturn(alexId);
        when(server.getOfflinePlayers()).thenReturn(new OfflinePlayer[]{alex});

        new MuteCmd(plugin, false).execute(staff, "nmod", new String[]{"mute", "alex"});

        verify(mutes).mute(alexId, 600);
        assertEquals(List.of("[NeoMod] Muted Alex for 10m."), TestPlugin.lines(staff));
    }

    @Test
    void someoneWhoNeverPlayedHereIsNamedBack() {
        new MuteCmd(plugin, false).execute(staff, "nmod", new String[]{"mute", "Nobody"});

        verify(mutes, never()).mute(any(), anyInt());
        assertEquals(List.of("[NeoMod] No player called Nobody has played here."), TestPlugin.lines(staff));
    }

    @Test
    void aDurationThatIsNotOneIsRefusedBeforeAnyLookup() {
        new MuteCmd(plugin, false).execute(staff, "nmod", new String[]{"mute", "Steve", "soon"});

        verify(mutes, never()).mute(any(), anyInt());
        verify(server, never()).getPlayerExact(any());
        assertEquals(List.of("[NeoMod] soon is not a duration. Try 30s, 5m or 1h (30d at most)."),
                TestPlugin.lines(staff));
    }

    @Test
    void unmutingSaysWhetherThereWasAMute() {
        when(mutes.isMuted(steve.getUniqueId())).thenReturn(false, true);

        new MuteCmd(plugin, true).execute(staff, "nmod", new String[]{"unmute", "Steve"});
        new MuteCmd(plugin, true).execute(staff, "nmod", new String[]{"unmute", "Steve"});

        verify(mutes).unmute(steve.getUniqueId());
        assertEquals(List.of("[NeoMod] Steve is not muted.", "[NeoMod] Steve can chat again."),
                TestPlugin.lines(staff));
        assertEquals(List.of("[NeoMod] You can chat again."), TestPlugin.lines(steve));
    }
}
