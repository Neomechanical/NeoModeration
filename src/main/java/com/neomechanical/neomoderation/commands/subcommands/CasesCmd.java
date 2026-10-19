package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.DurationParser;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.moderation.CaseLog;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Browse the local SQLite case history: /nmod cases [player], /nmod case <id>. */
public class CasesCmd implements SubCommand {
    private static final int PAGE_SIZE = 10;

    private final NeoModerationPlugin plugin;

    public CasesCmd(NeoModerationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "cases";
    }

    @Override
    public String getDescription() {
        return "Browse the local detection history.";
    }

    @Override
    public String getUsage() {
        return "/nmod cases [player|id|resolve <id>|dismiss <id>]";
    }

    @Override
    public List<String> getAliases() {
        return List.of("case");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return SubCommand.filterPrefix(args[1], "resolve", "dismiss");
        }
        return List.of();
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!plugin.settings().cases().enabled()) {
            plugin.messages().send(sender, "cases.disabled");
            return;
        }
        if (!plugin.caseLog().isAvailable()) {
            plugin.messages().send(sender, "cases.unavailable");
            return;
        }
        String argument = args.length >= 2 ? args[1].trim() : null;
        if (argument != null && ("resolve".equalsIgnoreCase(argument) || "dismiss".equalsIgnoreCase(argument))) {
            if (args.length < 3 || parseId(args[2].trim()) == null) {
                plugin.messages().send(sender, "cases.not-found", Map.of("id", args.length >= 3 ? args[2].trim() : "?"));
                return;
            }
            long targetId = parseId(args[2].trim());
            String newStatus = "resolve".equalsIgnoreCase(argument) ? "resolved" : "dismissed";
            plugin.runAsync(() -> {
                boolean updated = plugin.caseLog().updateStatus(targetId, newStatus);
                Optional<CaseLog.CaseRecord> record = updated ? plugin.caseLog().byId(targetId) : Optional.empty();
                plugin.runSync(() -> record.ifPresentOrElse(
                        found -> renderDetail(sender, label, found),
                        () -> plugin.messages().send(sender, "cases.not-found", Map.of("id", String.valueOf(targetId)))
                ));
            });
            return;
        }
        Long id = argument != null ? parseId(argument) : null;
        if (id != null) {
            plugin.runAsync(() -> {
                Optional<CaseLog.CaseRecord> record = plugin.caseLog().byId(id);
                plugin.runSync(() -> record.ifPresentOrElse(
                        found -> renderDetail(sender, label, found),
                        () -> plugin.messages().send(sender, "cases.not-found", Map.of("id", String.valueOf(id)))
                ));
            });
            return;
        }
        String playerFilter = argument;
        plugin.runAsync(() -> {
            List<CaseLog.CaseRecord> rows = plugin.caseLog().recent(playerFilter, PAGE_SIZE);
            plugin.runSync(() -> renderList(sender, label, rows));
        });
    }

    private void renderList(CommandSender sender, String label, List<CaseLog.CaseRecord> rows) {
        plugin.messages().send(sender, "cases.title");
        if (rows.isEmpty()) {
            plugin.messages().send(sender, "cases.empty");
            return;
        }
        if (sender instanceof org.bukkit.entity.Player) {
            java.util.List<com.neomechanical.neomoderation.messages.MenuRenderer.Line> lines = new java.util.ArrayList<>();
            for (CaseLog.CaseRecord row : rows) {
                Map<String, String> fields = Map.of(
                        "id", String.valueOf(row.id()),
                        "player", row.player(),
                        "surface", row.surface(),
                        "reason", row.reason(),
                        "actions", row.action(),
                        "mode", row.mode(),
                        "ago", ago(row.timestamp())
                );
                String formatted = plugin.messages().format("cases.line", fields);
                String cmd = "/" + label + " case " + row.id();
                lines.add(com.neomechanical.neomoderation.messages.MenuRenderer.clickableLine(
                        formatted, cmd, "&b" + cmd,
                        com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction.RUN));
            }
            plugin.messages().sendMenu(sender, lines);
            return;
        }
        for (CaseLog.CaseRecord row : rows) {
            plugin.messages().send(sender, "cases.line", Map.of(
                    "id", String.valueOf(row.id()),
                    "player", row.player(),
                    "surface", row.surface(),
                    "reason", row.reason(),
                    "actions", row.action(),
                    "mode", row.mode(),
                    "ago", ago(row.timestamp())
            ));
        }
    }

    private void renderDetail(CommandSender sender, String label, CaseLog.CaseRecord row) {
        plugin.messages().send(sender, "cases.title");
        plugin.messages().send(sender, "cases.line", Map.of(
                "id", String.valueOf(row.id()),
                "player", row.player(),
                "surface", row.surface(),
                "reason", row.reason(),
                "actions", row.action() + " [" + row.status() + "]",
                "mode", row.mode(),
                "ago", ago(row.timestamp())
        ));
        if (!row.preview().isBlank()) {
            plugin.messages().send(sender, "cases.preview", Map.of("value", row.preview()));
        }
        if (sender instanceof org.bukkit.entity.Player) {
            java.util.List<com.neomechanical.neomoderation.messages.MenuRenderer.Line> actions = new java.util.ArrayList<>();
            String resolveCmd = "/" + label + " cases resolve " + row.id();
            String dismissCmd = "/" + label + " cases dismiss " + row.id();
            actions.add(com.neomechanical.neomoderation.messages.MenuRenderer.clickableLine(
                    "  &a[Resolve #" + row.id() + "] &8(" + resolveCmd + ")",
                    resolveCmd, "&a" + resolveCmd,
                    com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction.RUN));
            actions.add(com.neomechanical.neomoderation.messages.MenuRenderer.clickableLine(
                    "  &e[Dismiss #" + row.id() + "] &8(" + dismissCmd + ")",
                    dismissCmd, "&e" + dismissCmd,
                    com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction.RUN));
            String matchedWord = extractBlockedWord(row.reason());
            if (matchedWord != null) {
                String allowCmd = "/" + label + " allow word " + matchedWord;
                actions.add(com.neomechanical.neomoderation.messages.MenuRenderer.clickableLine(
                        "  &b[Allow word \"" + matchedWord + "\"] &8(" + allowCmd + ")",
                        allowCmd, "&b" + allowCmd,
                        com.neomechanical.neomoderation.messages.MenuRenderer.ClickAction.RUN));
            }
            plugin.messages().sendMenu(sender, actions);
        }
    }

    static String extractBlockedWord(String reason) {
        if (reason == null) {
            return null;
        }
        String prefix = reason.startsWith("split_blocked_word:") ? "split_blocked_word:"
                : reason.startsWith("blocked_word:") ? "blocked_word:" : null;
        if (prefix == null) {
            return null;
        }
        String word = reason.substring(prefix.length()).trim();
        return word.isEmpty() ? null : word;
    }

    private static String ago(long timestamp) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestamp) / 1000L);
        return DurationParser.format((int) Math.min(Integer.MAX_VALUE, seconds));
    }

    private static Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
