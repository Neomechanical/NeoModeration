package com.neomechanical.neomoderation;

import com.neomechanical.neomoderation.config.ModerationAction;
import com.neomechanical.neomoderation.config.ModerationActionType;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.neomechanical.neomoderation.messages.ActionText;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.messages.Reasons;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.Outcome;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Every message the code asks for exists, so nobody reads a raw key such as
 * {@code doctor.detail.on}. Keys written out in the code are checked by reading
 * the sources; keys the code builds from a value are checked family by family.
 */
class LocaleKeysTest {
    /** A string literal shaped like a message key, or like the start of one ("trial.error."). */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-zA-Z0-9_-]+)+\\.?)\"");
    /** Literals shaped like keys that are file names or web addresses, such as cases.db and discord.gg. */
    private static final Pattern NOT_A_KEY = Pattern.compile("\\.(com|gg|net|org|io|db|yml|txt|json|png)$");
    private static final YamlConfiguration ENGLISH = TestPlugin.locale(MessageService.DEFAULT_LOCALE);
    /** Reasons of every kind, as the detectors write them. */
    private static final List<String> SAMPLE_REASONS = List.of(
            "blocked_word:badword", "split_blocked_word:badword", "blocked_url:grabify.link", "blocked_url:any",
            "server_ad:play.example.net", "spam:rate", "spam:duplicate", "spam:caps", "spam:flood",
            "spam:command_rate", "platform:harassment", "platform", "map_art:sexual", "map_art", "unknown:thing");

    @Test
    void noKeyIsReadAsAYesOrNo() {
        // YAML reads an unquoted on, off, yes or no key as true or false: "on:" becomes the key "true".
        for (String code : MessageService.BUNDLED_LOCALES) {
            for (String key : TestPlugin.locale(code).getKeys(true)) {
                for (String part : key.split("\\.")) {
                    assertFalse(part.equals("true") || part.equals("false"),
                            code + ".yml: quote the key in " + key + " (\"on\": not on:)");
                }
            }
        }
    }

    @Test
    void everyKeyTheCodeWritesOutExists() throws IOException {
        Set<String> sections = ENGLISH.getKeys(false);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY_LITERAL.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!sections.contains(key.substring(0, key.indexOf('.'))) || NOT_A_KEY.matcher(key).find()) {
                        continue; // a config path such as moderation.enabled, or a file or site name
                    }
                    checked++;
                    boolean exists = key.endsWith(".")
                            ? ENGLISH.isConfigurationSection(key.substring(0, key.length() - 1))
                            : key.endsWith("-")
                            ? ENGLISH.getKeys(true).stream().anyMatch(name -> name.startsWith(key))
                            : ENGLISH.isString(key);
                    if (!exists) {
                        missing.add(file.getFileName() + ": " + key);
                    }
                }
            }
        }
        assertTrue(checked > 200, "found only " + checked + " message keys in the code; is the pattern still right?");
        assertTrue(missing.isEmpty(), () -> "Keys the code uses but en_US.yml lacks:\n" + String.join("\n", missing));
    }

    @Test
    void everyVerdictHasItsWords() {
        for (Outcome.Verdict verdict : Outcome.Verdict.values()) {
            assertTrue(ENGLISH.isString("alert.verdict." + verdict.key()), verdict.name());
            assertTrue(ENGLISH.isString("discord.title-" + verdict.key()), verdict.name());
        }
    }

    @Test
    void everyReasonHasWordsForStaffPlayersAndStatistics() {
        MessageService messages = TestPlugin.english();
        CommandSender console = mock(CommandSender.class);
        Set<Reasons.Kind> covered = EnumSet.noneOf(Reasons.Kind.class);
        for (String reason : SAMPLE_REASONS) {
            covered.add(Reasons.parse(reason).kind());
            String described = Reasons.describe(messages, console, reason);
            assertFalse(described.startsWith("reason."), reason + " -> " + described);
            assertString(Reasons.whyKey(reason));
            assertString("stat." + Reasons.bucket(reason));
        }
        assertEquals(EnumSet.allOf(Reasons.Kind.class), covered, "add a sample reason for every kind");
    }

    @Test
    void everyFamilyOfBuiltKeysIsComplete() {
        for (String category : ModerationCategorySettings.categoryKeys()) {
            assertString("category." + category);
        }
        for (String surface : SurfaceSettings.SURFACE_NAMES) {
            assertString("surface.name." + surface);
            assertString("notice." + surface);
        }
        assertString("surface.name.all");
        for (String mode : SurfaceSettings.MODE_NAMES) {
            assertString("surface.mode." + mode);
        }
        for (String surface : List.of("chat", "sign", "book", "anvil", "command", "map_art", "map_frame")) {
            assertString("surface-name." + surface);
        }
        // A refused cloud check is named once (CloudPlanNotices.refusal) and said in each surface's words; the last-check
        // lines name only what the plan line beside them does not say (CloudPlanNotices.lastCheck).
        for (String reason : CloudPlanNotices.REASONS) {
            assertString("test.cloud-" + reason);
            assertString("cloud-plan.console-" + reason);
            assertString("usage.error-" + reason);
        }
        for (String lastCheck : CloudPlanNotices.LAST_CHECKS) {
            assertString("status.cloud-" + lastCheck);
            assertString("doctor.detail.events-" + lastCheck);
        }
        for (String ended : List.of("trial-ended", "trial-revoked")) {
            assertString("cloud-plan.join-" + ended);
            assertString("doctor.detail.plan-" + ended);
            assertString("status.cloud-plan-" + ended);
        }
        for (Outcome.Verdict verdict : Outcome.Verdict.values()) {
            String name = verdict.name().toLowerCase(Locale.ROOT);
            assertString("alert.verdict." + name);
            assertString("discord.title-" + name);
        }
        MessageService messages = TestPlugin.english();
        CommandSender console = mock(CommandSender.class);
        for (ModerationActionType type : ModerationActionType.values()) {
            // A command action always has its command (config parsing drops one without); the rest use defaults.
            String command = type == ModerationActionType.COMMAND ? "say hello %PLAYER%" : "";
            String name = ActionText.name(messages, console, new ModerationAction(type, command, "", 600, ""));
            assertFalse(name.startsWith("action.") || name.endsWith("/"), type + " -> " + name);
        }
        for (String status : List.of("open", "resolved", "dismissed")) {
            assertString("cases.status." + status);
            assertString("cases.status-name." + status);
        }
        for (String prefix : List.of("mode", "cloudmode")) {
            assertString(prefix + ".usage");
            assertString(prefix + ".set-monitor");
            assertString(prefix + ".set-enforce");
        }
    }

    private static void assertString(String key) {
        assertTrue(ENGLISH.isString(key), "en_US.yml has no text for " + key);
    }
}
