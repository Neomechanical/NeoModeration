package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DiscordCmdTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-JKL";

    @Test
    void connectingAlertsNamesModeratorBotOnceWithItsTaggedInvite() {
        YamlConfiguration config = new YamlConfiguration();
        NeoModerationPlugin plugin = TestPlugin.with(config);
        CommandSender admin = mock(CommandSender.class);

        new DiscordCmd(plugin).execute(admin, "nmod", new String[]{"discord", WEBHOOK});

        assertEquals(WEBHOOK, config.getString("moderation.alerts.discordWebhookUrl"));
        assertEquals(List.of(
                "[NeoMod] Discord alerts are on. Run /nmod discord test to send one.",
                "[NeoMod] Moderator Bot, also by Neomechanical, can moderate that Discord server.",
                "  [Add Moderator Bot] https://modbot.neomechanical.com/api/invite?src=neomoderation_discord"),
                TestPlugin.lines(admin));
    }

    @Test
    void aWrongAddressOrTurningAlertsOffNamesNoOtherProduct() {
        NeoModerationPlugin plugin = TestPlugin.with(new YamlConfiguration());
        CommandSender admin = mock(CommandSender.class);

        new DiscordCmd(plugin).execute(admin, "nmod", new String[]{"discord", "https://example.com/hook"});
        new DiscordCmd(plugin).execute(admin, "nmod", new String[]{"discord", "off"});

        TestPlugin.lines(admin).forEach(line -> assertEquals(-1, line.indexOf("Moderator Bot"), line));
    }
}
