package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.config.BukkitConfigView;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.messages.MessageService;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.NeoMechanicalUsageClient;
import com.neomechanical.neomoderation.moderation.UsageSummary;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorChecksTest {
    private static final YamlConfiguration ENGLISH = TestPlugin.locale(MessageService.DEFAULT_LOCALE);

    @Test
    void everyCheckEveryConfigurationCanProduceHasWords() {
        List<DoctorChecks.Check> checks = new ArrayList<>();
        for (Consumer<YamlConfiguration> setup : configurations()) {
            YamlConfiguration config = new YamlConfiguration();
            setup.accept(config);
            ModerationSettings settings = ModerationSettings.from(new BukkitConfigView(config));
            checks.addAll(DoctorChecks.local(settings, true, null));
            checks.addAll(DoctorChecks.local(settings, false, "HTTP 404"));
            checks.addAll(DoctorChecks.cloudSetup(settings, false, null));
            for (ModerationApiResult.Kind kind : ModerationApiResult.Kind.values()) {
                checks.addAll(DoctorChecks.cloudSetup(settings, true, kind));
            }
        }
        checks.addAll(DoctorChecks.account(usage(100), 12));
        checks.addAll(DoctorChecks.account(usage(0), 12));
        for (ModerationApiResult.Kind kind : ModerationApiResult.Kind.values()) {
            checks.add(DoctorChecks.accountFailure(new NeoMechanicalUsageClient.UsageException(kind, "test"), 12));
        }

        List<String> missing = new ArrayList<>();
        for (DoctorChecks.Check check : checks) {
            for (String key : List.of("doctor." + check.level().name().toLowerCase(Locale.ROOT),
                    "doctor.check." + check.check(), "doctor.detail." + check.detail())) {
                if (!ENGLISH.isString(key)) {
                    missing.add(key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "en_US.yml lacks " + missing.stream().distinct().toList());
    }

    private static UsageSummary usage(long credits) {
        return new UsageSummary("Test", "paid", 60, credits, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static List<Consumer<YamlConfiguration>> configurations() {
        return List.of(
                config -> config.set("moderation.enabled", true),
                config -> config.set("moderation.enabled", false),
                config -> {
                    config.set("moderation.enabled", true);
                    config.set("moderation.mode", "monitor");
                    config.set("moderation.api.apiKey", "key");
                    config.set("moderation.cloudMode", "monitor");
                },
                config -> {
                    config.set("moderation.offline.enabled", false);
                    config.set("moderation.spam.enabled", false);
                    config.set("moderation.api.apiKey", "key");
                    config.set("moderation.api.endpoint", "not a url");
                },
                config -> {
                    config.set("moderation.offline.enabled", false);
                    config.set("moderation.spam.enabled", true);
                    config.set("moderation.spam.messagesPer10s", 0);
                    config.set("moderation.spam.duplicateLimit", 0);
                    config.set("moderation.spam.capsPercent", 0);
                    config.set("moderation.spam.maxCharRun", 0);
                    config.set("moderation.spam.commandsPer10s", 5);
                },
                config -> {
                    config.set("moderation.mode", "enforce");
                    config.set("moderation.offline.enabled", false);
                    config.set("moderation.spam.enabled", false);
                    config.set("moderation.surfaces.sign", "block");
                    config.set("moderation.offline.blockServerAds", false);
                    config.set("moderation.strikes.enabled", true);
                    config.set("moderation.cases.storeContent", true);
                    config.set("moderation.alerts.discordWebhookUrl", "https://example.com/not-discord");
                    config.set("moderation.chat.failOpen", false);
                    config.set("moderation.api.connectTimeoutMs", 2000);
                    config.set("moderation.api.readTimeoutMs", 2000);
                    config.set("moderation.actions", List.of(Map.of("type", "kick")));
                },
                config -> {
                    config.set("moderation.api.apiKey", "key");
                    config.set("moderation.mapArt.enabled", false);
                    for (String category : ModerationCategorySettings.categoryKeys()) {
                        config.set("moderation.categories." + category, false);
                    }
                    config.set("moderation.cases.enabled", false);
                    config.set("moderation.alerts.discordWebhookUrl", "https://discord.com/api/webhooks/1/a");
                });
    }
}
