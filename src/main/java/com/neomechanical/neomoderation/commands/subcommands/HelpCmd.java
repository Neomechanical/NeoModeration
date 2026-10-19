package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clickable paged help menu. Page 1: daily commands. Page 2: admin commands.
 * Layout lives here; all text comes from the locale.
 */
public class HelpCmd implements SubCommand {
    /** Page 1: section locale key -> subcommand names. */
    private static final Map<String, List<String>> PAGE_ONE = new LinkedHashMap<>();
    /** Page 2: admin + rarely used commands. */
    private static final Map<String, List<String>> PAGE_TWO = new LinkedHashMap<>();
    /**
     * Commands safe to execute with zero args: click runs them. State-changing
     * toggles (on/off) stay out: a stray click must never disable protection.
     */
    private static final List<String> RUN_COMMANDS =
            List.of("trial", "status", "mode", "cloudmode", "preset", "surface", "doctor", "cases", "usage", "privacy", "reload");

    static {
        PAGE_ONE.put("start", List.of("trial", "setup", "status", "mode", "preset"));
        PAGE_ONE.put("rules", List.of("word", "url", "allow", "action", "surface"));
        PAGE_ONE.put("tools", List.of("test", "doctor"));
        PAGE_TWO.put("tools", List.of("cases", "usage", "privacy"));
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
    public String getDescription() {
        return "Shows the help menu.";
    }

    @Override
    public String getUsage() {
        return "/nmod help";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        // The dispatcher passes the full args array including the subcommand
        // name ("/nmod help 2" arrives as {"help", "2"}), so scan every arg.
        int page = java.util.Arrays.stream(args).anyMatch("2"::equals) ? 2 : 1;
        Map<String, List<String>> sections = page == 2 ? PAGE_TWO : PAGE_ONE;
        List<MenuRenderer.Line> lines = new ArrayList<>();
        lines.add(MenuRenderer.text(dashboard()));
        lines.add(MenuRenderer.text(divider()));
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            List<String> visible = section.getValue().stream()
                    .filter(name -> {
                        SubCommand cmd = commands.get(name);
                        return cmd != null && sender.hasPermission(cmd.getPermission());
                    })
                    .toList();
            if (visible.isEmpty()) {
                continue;
            }
            lines.add(MenuRenderer.section(sectionTitle(section.getKey())));
            for (String name : visible) {
                SubCommand cmd = commands.get(name);
                String usage = usageFor(cmd, label);
                if (RUN_COMMANDS.contains(name)) {
                    lines.add(MenuRenderer.entry(usage, descriptionFor(cmd),
                            "/" + label + " " + name, ClickAction.RUN));
                } else {
                    lines.add(MenuRenderer.entry(usage, descriptionFor(cmd),
                            usage + " ", ClickAction.SUGGEST));
                }
            }
        }
        lines.add(MenuRenderer.text(divider()));
        lines.add(MenuRenderer.text(plugin.messages().format("help.hint", Map.of())));
        lines.add(MenuRenderer.text(plugin.messages().format("help.page",
                Map.of("page", String.valueOf(page), "next", page == 1 ? "2" : "1"))));
        plugin.messages().sendMenu(sender, lines);
    }

    private String dashboard() {
        var settings = plugin.settings();
        return plugin.messages().dashboardLine(
                plugin.getDescription().getVersion(),
                settings.mode().name(),
                com.neomechanical.neomoderation.moderation.ModerationCoverage.cloudDashboardLabel(settings),
                plugin.monitorStats().total());
    }

    private String divider() {
        return plugin.messages().footerLine();
    }

    private String sectionTitle(String key) {
        return plugin.messages().format("help.section." + key, Map.of());
    }

    private String usageFor(SubCommand cmd, String label) {
        String key = "help.usage." + cmd.getName();
        String usage = plugin.messages().format(key, Map.of());
        if (usage.equals(key) || usage.isBlank()) {
            usage = cmd.getUsage();
        }
        return usage.replace("/nmod", "/" + label);
    }

    private String descriptionFor(SubCommand cmd) {
        String key = "help.desc." + cmd.getName();
        String desc = plugin.messages().format(key, Map.of());
        return desc.equals(key) || desc.isBlank() ? cmd.getDescription() : desc;
    }
}
