package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.DiscordAlerts;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** /nmod discord: set, test or turn off the Discord webhook staff alerts go to. */
public class DiscordCmd implements SubCommand {
    private static final String PATH = "moderation.alerts.discordWebhookUrl";

    private final NeoModerationPlugin plugin;

    public DiscordCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "discord";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (args.length != 2) {
            messages.send(sender, "discord.usage", Map.of("label", label));
            return;
        }
        String value = args[1].trim();
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> {
                plugin.getConfig().set(PATH, "");
                plugin.saveAndReload();
                messages.send(sender, "discord.off");
            }
            case "test" -> sendTest(sender, label);
            default -> {
                if (!DiscordAlerts.isWebhookUrl(value)) {
                    messages.send(sender, "discord.invalid");
                    return;
                }
                plugin.getConfig().set(PATH, value);
                plugin.saveAndReload();
                messages.send(sender, "discord.saved", Map.of("label", label));
            }
        }
    }

    private void sendTest(CommandSender sender, String label) {
        MessageService messages = plugin.messages();
        String webhook = plugin.settings().alerts().discordWebhookUrl();
        if (!DiscordAlerts.isWebhookUrl(webhook)) {
            messages.send(sender, "discord.not-set", Map.of("label", label));
            return;
        }
        CommandSender console = plugin.getServer().getConsoleSender();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(plain(messages, console, "discord.field-player"), plain(messages, console, "discord.test-player"));
        fields.put(plain(messages, console, "discord.field-reason"), plain(messages, console, "discord.test-reason"));
        fields.put(plain(messages, console, "discord.field-message"), plain(messages, console, "discord.test-message"));
        DiscordAlerts.Alert alert = new DiscordAlerts.Alert(plain(messages, console, "discord.title-review"), 0x9CA8FF, fields);
        plugin.discordAlerts().sendNow(webhook, alert, delivered ->
                plugin.runSync(() -> messages.send(sender, delivered ? "discord.sent" : "discord.failed")));
    }

    private static String plain(MessageService messages, CommandSender reader, String key) {
        return MessageService.plain(messages.text(reader, key));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return args.length == 2 ? SubCommand.filterPrefix(args[1], "test", "off") : List.of();
    }
}
