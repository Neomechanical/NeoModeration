package com.neomechanical.neomoderation.commands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.config.InputLimits;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared add/remove/list handler for the string-list rules in config.yml
 * (banned and allowed words and links). {@code actionIndex} is the position of
 * the add|remove|list token in {@code args}; everything after it is the value.
 */
public final class RuleListEditor {

    /** One editable list: where it lives, how the reader calls it, and the command that edits it. */
    public record RuleList(String path, String kindKey, String command) {
        public static final RuleList BANNED_WORDS = new RuleList("moderation.offline.bannedWords", "word", "word");
        public static final RuleList BANNED_URLS = new RuleList("moderation.offline.bannedUrls", "url", "url");
        public static final RuleList ALLOWED_WORDS =
                new RuleList("moderation.offline.allowedWords", "allowed-word", "allow word");
        public static final RuleList ALLOWED_URLS =
                new RuleList("moderation.offline.allowedUrls", "allowed-url", "allow url");
    }

    private RuleListEditor() {
    }

    public static void handle(NeoModerationPlugin plugin, CommandSender sender, String label, String[] args,
                              int actionIndex, RuleList list) {
        MessageService messages = plugin.messages();
        String kind = messages.text(sender, "rules.kind." + list.kindKey());
        String action = args.length > actionIndex ? args[actionIndex].toLowerCase(Locale.ROOT) : "";
        if ("list".equals(action)) {
            showList(plugin, sender, list, kind);
            return;
        }
        if (!"add".equals(action) && !"remove".equals(action)) {
            messages.send(sender, "rules.usage", Map.of("label", label, "command", list.command()));
            return;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, Math.min(args.length, actionIndex + 1), args.length)).trim();
        if (value.isEmpty()) {
            messages.send(sender, "rules.value-required");
            return;
        }
        if (!InputLimits.isRuleValueLengthValid(value)) {
            messages.send(sender, "error.rule-too-long", Map.of("max", String.valueOf(InputLimits.MAX_RULE_VALUE_LENGTH)));
            return;
        }
        boolean adding = "add".equals(action);
        boolean changed = plugin.editConfig(config -> {
            List<String> values = new ArrayList<>(config.getStringList(list.path()));
            boolean edited = adding ? add(values, value) : values.removeIf(existing -> existing.equalsIgnoreCase(value));
            if (edited) {
                config.set(list.path(), values);
            }
            return edited;
        });
        String key = changed ? (adding ? "rules.added" : "rules.removed") : (adding ? "rules.already-there" : "rules.not-there");
        messages.send(sender, key, Map.of("kind", kind, "value", value));
        if (!changed && !adding && list == RuleList.BANNED_WORDS && fromLanguagePack(plugin, value)) {
            // The word still blocks; say why and how to let it through.
            messages.send(sender, "rules.in-pack", Map.of("value", value, "label", label));
        }
    }

    private static boolean fromLanguagePack(NeoModerationPlugin plugin, String value) {
        return plugin.settings().offline().packWords().stream().anyMatch(word -> word.equalsIgnoreCase(value));
    }

    private static void showList(NeoModerationPlugin plugin, CommandSender sender, RuleList list, String kind) {
        MessageService messages = plugin.messages();
        List<String> values = plugin.getConfig().getStringList(list.path());
        messages.send(sender, "rules.list", Map.of("kind", kind,
                "values", values.isEmpty() ? messages.text(sender, "label.none") : String.join(", ", values)));
        OfflineModerationSettings offline = plugin.settings().offline();
        if (list == RuleList.BANNED_WORDS && !offline.packWords().isEmpty()) {
            messages.send(sender, "rules.list-packs", Map.of(
                    "count", String.valueOf(offline.packWords().size()),
                    "packs", String.join(", ", offline.languagePacks())));
        }
    }

    private static boolean add(List<String> values, String value) {
        if (values.stream().anyMatch(existing -> existing.equalsIgnoreCase(value))) {
            return false;
        }
        values.add(value);
        return true;
    }
}
