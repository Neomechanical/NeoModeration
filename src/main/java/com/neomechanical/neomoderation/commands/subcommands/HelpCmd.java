package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clickable two-page help menu. Page 1: daily commands. Page 2: admin commands.
 * Layout lives here; every word comes from the locale.
 */
public class HelpCmd implements SubCommand {
    /** Page 1: section locale key -> subcommand names. */
    private static final Map<String, List<String>> PAGE_ONE = new LinkedHashMap<>();
    /** Page 2: admin and rarely used commands. */
    private static final Map<String, List<String>> PAGE_TWO = new LinkedHashMap<>();
    /**
     * Commands safe to run with no arguments: a click runs them. State-changing
     * toggles (on/off) stay out: a stray click must never disable protection.
     */
    private static final List<String> RUN_COMMANDS = List.of(
            "trial", "status", "mode", "cloudmode", "preset", "surface", "doctor", "cases", "usage", "privacy",
            "reload", "discord");

    static {
        PAGE_ONE.put("start", List.of("trial", "setup", "status", "mode", "preset"));
        PAGE_ONE.put("rules", List.of("word", "url", "allow", "action", "surface"));
        PAGE_ONE.put("tools", List.of("test", "cases", "mute", "unmute", "doctor"));
        PAGE_TWO.put("tools", List.of("discord", "usage", "privacy"));
        PAGE_TWO.put("admin", List.of("key", "cloudmode", "reload", "on", "off"));
    }

    private final NeoModerationPlugin plugin;
    private final Map<String, SubCommand> commands;

    public HelpCmd(NeoModerationPlugin plugin, Map<String, SubCommand> commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        // The dispatcher passes the full args array including the subcommand
        // name ("/nmod help 2" arrives as {"help", "2"}), so scan every arg.
        int page = Arrays.asList(args).contains("2") ? 2 : 1;
        Map<String, List<String>> sections = page == 2 ? PAGE_TWO : PAGE_ONE;
        MessageService messages = plugin.messages();
        List<MenuRenderer.Line> lines = new ArrayList<>();
        lines.add(MenuRenderer.text(StatusCmd.dashboard(plugin, sender)));
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            List<String> visible = section.getValue().stream()
                    .filter(name -> commands.get(name) != null && sender.hasPermission(commands.get(name).getPermission()))
                    .toList();
            if (visible.isEmpty()) {
                continue;
            }
            lines.add(MenuRenderer.section(messages.text(sender, "help.section." + section.getKey())));
            for (String name : visible) {
                String usage = messages.text(sender, "help.usage." + name).replace("/nmod", "/" + label);
                String description = messages.text(sender, "help.desc." + name);
                boolean runs = RUN_COMMANDS.contains(name);
                lines.add(MenuRenderer.entry(
                        messages.format(sender, "help.entry-usage", Map.of("usage", usage)),
                        messages.format(sender, "help.entry-desc", Map.of("desc", description)),
                        messages.format(sender, "help.entry-hover", Map.of("usage", usage, "desc", description)),
                        runs ? "/" + label + " " + name : usage + " ", runs ? ClickAction.RUN : ClickAction.SUGGEST));
            }
        }
        lines.add(MenuRenderer.text(messages.text(sender, "help.hint")));
        lines.add(MenuRenderer.text(messages.format(sender, "help.page",
                Map.of("page", String.valueOf(page), "next", page == 1 ? "2" : "1"))));
        messages.sendMenu(sender, lines);
    }
}
