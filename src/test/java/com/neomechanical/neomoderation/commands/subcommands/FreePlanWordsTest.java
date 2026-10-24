package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.moderation.CloudPlanNotices;
import com.neomechanical.neomoderation.moderation.CloudPlanWatch;
import com.neomechanical.neomoderation.moderation.FreePlan;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What an admin reads about the free plan in {@code /nmod status} and {@code /nmod doctor}, in each of its states. */
class FreePlanWordsTest {

    private static final String CLAIM = "https://neomechanical.com/signup?trial=v2.sig&src=neomoderation_trial";

    private static TrialClient.TrialStatusResult free(int checksToday, boolean claimed, boolean pending) {
        FreePlan plan = new FreePlan(2000, checksToday, 300, 1, "2026-11-03T00:00:00Z", claimed, pending);
        return new TrialClient.TrialStatusResult(false, "free_plan", "", 0, claimed ? "" : CLAIM, "", plan, false);
    }

    private static NeoModerationPlugin plugin() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.api.apiKey", "nm_free_key");
        config.set("moderation.api.endpoint", "http://127.0.0.1:1/v1/events");
        return TestPlugin.with(config);
    }

    private static List<String> run(SubCommand command, NeoModerationPlugin plugin) {
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        command.execute(admin, "nmod", new String[]{command.getName()});
        return TestPlugin.lines(admin);
    }

    @Test
    void anUnclaimedPlanShowsTodaysChecksAndNeverItsLinkInText() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, free(12, false, false));
        List<String> status = run(new StatusCmd(plugin), plugin);
        assertTrue(status.contains("Cloud plan: free. Today: 12 of 2000 checks, 1 of 300 map pictures."), String.join("\n", status));
        assertTrue(status.stream().noneMatch(line -> line.contains("trial=v2.sig")), "the claim link stays a button");
        List<String> doctor = run(new DoctorCmd(plugin), plugin);
        assertTrue(doctor.stream().anyMatch(line -> line.contains("free. Today: 12 of 2000 checks, 1 of 300 map pictures")),
                String.join("\n", doctor));
    }

    @Test
    void aCappedPlanSaysWhenItsChecksComeBackOnce() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, free(2000, false, false));
        when(plugin.coordinator().lastCloudResultKind()).thenReturn(ModerationApiResult.Kind.FREE_PLAN_CAPPED);
        when(plugin.coordinator().freeChecksBackAt()).thenReturn(Optional.of(Instant.parse("2026-11-03T00:07:00Z")));
        List<String> status = run(new StatusCmd(plugin), plugin);
        assertTrue(status.contains("Cloud plan: free. Today's 2000 cloud checks are used up until 3 November 2026, "
                + "00:00 UTC. Local rules still run. Run /nmod trial status to keep checking with an account."),
                String.join("\n", status));
        assertTrue(status.stream().noneMatch(line -> line.startsWith("Last cloud check")),
                "the plan line already says it: " + status);
    }

    @Test
    void aClaimedPlanPastItsCapSaysChecksGoOnAtTheNormalPrice() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, free(2000, true, false));
        List<String> status = run(new StatusCmd(plugin), plugin);
        assertTrue(status.stream().anyMatch(line -> line.startsWith("Cloud plan: free, in your account. Today's 2000 "
                + "free checks are used up, so checks use your credits at the normal price")), String.join("\n", status));
    }

    @Test
    void aPendingClaimAsksForTheEmail() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, free(3, false, true));
        assertEquals(CloudPlanWatch.State.FREE_PLAN, plugin.cloudPlan().state());
        List<String> status = run(new StatusCmd(plugin), plugin);
        assertTrue(status.stream().anyMatch(line -> line.contains("Verify the email of the account you made for it")),
                String.join("\n", status));
    }

    @Test
    void everyFreePlanLineAndRefusalHasItsWords() {
        YamlConfiguration english = YamlConfiguration.loadConfiguration(new File("src/main/resources/locale/en_US.yml"));
        for (String line : CloudPlanNotices.FREE_PLAN_LINES) {
            assertTrue(english.isString("status.cloud-plan-" + line), "status.cloud-plan-" + line);
            assertTrue(english.isString("doctor.detail.plan-" + line), "doctor.detail.plan-" + line);
        }
        for (String code : TrialCmd.REFUSAL_CODES) {
            assertTrue(english.isString("trial.refused-" + code), "trial.refused-" + code);
        }
    }
}
