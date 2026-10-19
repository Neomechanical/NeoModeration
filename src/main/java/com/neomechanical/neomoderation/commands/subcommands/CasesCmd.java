package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.messages.MenuRenderer;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.CaseLog;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The local case history: {@code /nmod cases [player] [page]}, {@code /nmod case <id>},
 * {@code /nmod cases resolve|dismiss <id>}. Reads run off the main thread.
 */
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
    public String getPermission() {
        return Permissions.MODERATE;
    }

    @Override
    public List<String> getAliases() {
        return List.of("case");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("resolve", "dismiss"));
        plugin.getServer().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        return SubCommand.filterPrefix(args[1], options.toArray(String[]::new));
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        MessageService messages = plugin.messages();
        if (!plugin.settings().cases().enabled()) {
            messages.send(sender, "cases.disabled");
            return;
        }
        if (!plugin.caseLog().isAvailable()) {
            messages.send(sender, "cases.unavailable");
            return;
        }
        String first = args.length >= 2 ? args[1].trim() : "";
        String action = first.toLowerCase(Locale.ROOT);
        // "resolve page 1" lists the cases of a player named Resolve, as the [More from] button writes it.
        boolean listingAPlayer = args.length >= 3 && "page".equalsIgnoreCase(args[2]);
        if ((action.equals("resolve") || action.equals("dismiss")) && !listingAPlayer) {
            Long id = args.length >= 3 ? parseLong(args[2]) : null;
            if (id == null) {
                messages.send(sender, "cases.not-found", Map.of("id", args.length >= 3 ? args[2] : "?"));
                return;
            }
            mark(sender, label, id, action.equals("resolve") ? "resolved" : "dismissed");
            return;
        }
        // A lone number is a case; a player who happens to be named with digits is "<name> page 1".
        Long id = args.length == 2 ? parseLong(first) : null;
        if (id != null) {
            show(sender, label, id);
            return;
        }
        // [player] [page <n>] in any order of the two, as the "Older cases" line writes it.
        String player = null;
        int page = 1;
        for (int i = 1; i < args.length; i++) {
            if ("page".equalsIgnoreCase(args[i]) && i + 1 < args.length && parseInt(args[i + 1]) != null) {
                page = parseInt(args[++i]);
            } else if (player == null) {
                player = args[i].trim();
            }
        }
        list(sender, label, player, page);
    }

    private void list(CommandSender sender, String label, String player, int page) {
        plugin.runAsync(() -> {
            // One row more than a page shows whether an older page exists.
            List<CaseLog.CaseRecord> fetched = plugin.caseLog().recent(player, PAGE_SIZE + 1, (page - 1) * PAGE_SIZE);
            boolean older = fetched.size() > PAGE_SIZE;
            List<CaseLog.CaseRecord> rows = older ? fetched.subList(0, PAGE_SIZE) : fetched;
            plugin.runSync(() -> {
                MessageService messages = plugin.messages();
                if (rows.isEmpty()) {
                    messages.send(sender, page > 1 ? "cases.no-older" : "cases.empty");
                    return;
                }
                String who = player == null ? ""
                        : messages.format(sender, "cases.title-player", Map.of("player", player));
                List<MenuRenderer.Line> lines = new ArrayList<>();
                lines.add(MenuRenderer.text(messages.format(sender, "cases.title", Map.of("who", who, "label", label))));
                rows.forEach(row -> lines.add(CaseView.row(messages, sender, row, label)));
                if (older) {
                    String next = "/" + label + " cases " + (player == null ? "" : player + " ") + "page " + (page + 1);
                    lines.add(MenuRenderer.clickableLine(messages.format(sender, "cases.more", Map.of("command", next)),
                            next, "", MenuRenderer.ClickAction.RUN));
                }
                messages.sendMenu(sender, lines);
            });
        });
    }

    private void show(CommandSender sender, String label, long id) {
        plugin.runAsync(() -> {
            Optional<CaseLog.CaseRecord> found = plugin.caseLog().byId(id);
            plugin.runSync(() -> found.ifPresentOrElse(
                    row -> plugin.messages().sendMenu(sender, CaseView.detail(plugin.messages(), sender, row, label)),
                    () -> plugin.messages().send(sender, "cases.not-found", Map.of("id", String.valueOf(id)))));
        });
    }

    private void mark(CommandSender sender, String label, long id, String status) {
        plugin.runAsync(() -> {
            boolean updated = plugin.caseLog().updateStatus(id, status);
            plugin.runSync(() -> {
                MessageService messages = plugin.messages();
                if (!updated) {
                    messages.send(sender, "cases.not-found", Map.of("id", String.valueOf(id)));
                    return;
                }
                messages.send(sender, "cases.updated", Map.of("id", String.valueOf(id),
                        "status", messages.text(sender, "cases.status-name." + status)));
            });
        });
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value.trim().replace("#", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed < 10_000 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
