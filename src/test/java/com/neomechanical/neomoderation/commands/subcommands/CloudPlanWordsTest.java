package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.SubCommand;
import com.neomechanical.neomoderation.moderation.CloudRecovery;
import com.neomechanical.neomoderation.moderation.ModerationApiClient;
import com.neomechanical.neomoderation.moderation.ModerationApiResult;
import com.neomechanical.neomoderation.moderation.TrialClient;
import com.neomechanical.neomoderation.testing.TestPlugin;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The words an admin reads about the cloud plan in {@code /nmod status}, {@code /nmod doctor}, {@code /nmod test} and
 * {@code /nmod usage}, in each state the trial service can name. These surfaces were once tested by message key only,
 * and two doctor lines printed their raw keys on every server with a key.
 */
class CloudPlanWordsTest {

    private static final String CLAIM = "https://neomechanical.com/signup?trial=t.sig&src=neomoderation_trial";
    private static final String SIGNUP = "https://neomechanical.com/signup?src=neomoderation";
    private static final TrialClient.TrialStatusResult RUNNING =
            new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 9, CLAIM);
    private static final TrialClient.TrialStatusResult ENDED =
            new TrialClient.TrialStatusResult(true, "expired", "2026-10-01T00:00:00Z", 0, SIGNUP);
    private static final TrialClient.TrialStatusResult STOPPED =
            new TrialClient.TrialStatusResult(true, "revoked", "2026-10-30T00:00:00Z", 0, SIGNUP);

    /** A plugin with a key on {@code endpoint} (a closed port unless a test serves one). */
    private static NeoModerationPlugin plugin(String endpoint) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("moderation.enabled", true);
        config.set("moderation.api.apiKey", "nm_trial_key");
        config.set("moderation.api.endpoint", endpoint);
        return TestPlugin.with(config);
    }

    private static NeoModerationPlugin plugin() {
        return plugin("http://127.0.0.1:1/v1/events");
    }

    private static List<String> run(SubCommand command, NeoModerationPlugin plugin) {
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        command.execute(admin, "nmod", new String[]{command.getName()});
        return TestPlugin.lines(admin);
    }

    /** No raw message key and no signed claim link, which whoever opens it first can use, in any line. */
    private static void assertClean(List<String> lines) {
        for (String line : lines) {
            assertFalse(line.matches(".*\\b(doctor|status|test|usage|cloud-plan|trial)\\.[a-z-]+.*"), line);
            assertFalse(line.contains("trial=t.sig"), "the claim link is kept out of text: " + line);
        }
    }

    @Test
    void statusCountsARunningTrialsDaysByTheClock() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, RUNNING);
        List<String> lines = run(new StatusCmd(plugin), plugin);
        assertTrue(lines.contains("Cloud plan: free trial until 22 October 2026. Days left: 2"), String.join("\n", lines));
        assertTrue(lines.contains("Last cloud check: none since startup. Run /nmod test hello."), String.join("\n", lines));
        assertClean(lines);
    }

    @Test
    void statusSaysAnEndedTrialOnceAfterARestartWithTheWayToAKey() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, ENDED);
        List<String> lines = run(new StatusCmd(plugin), plugin);
        assertTrue(lines.contains("Cloud plan: the free trial ended on 1 October 2026. Create a free account at "
                + SIGNUP + ", then a key, and run /nmod setup <key>."), String.join("\n", lines));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("Last cloud check")),
                "no check ran since startup, and the plan line already says why: " + lines);
        assertClean(lines);
    }

    @Test
    void statusSaysAStoppedTrialWithoutTheDateItWasMeantToEnd() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, STOPPED);
        when(plugin.coordinator().lastCloudResultKind()).thenReturn(ModerationApiResult.Kind.CLIENT_AUTH);
        List<String> lines = run(new StatusCmd(plugin), plugin);
        assertTrue(lines.contains("Cloud plan: the cloud trial was stopped. Create a free account at " + SIGNUP
                + ", then a key, and run /nmod setup <key>."), String.join("\n", lines));
        assertTrue(lines.contains("Last cloud check: refused."), "the plan line says why, once: " + lines);
        assertClean(lines);
    }

    @Test
    void statusSaysAKeyTheTrialServiceRejectedWithoutALastCheckThatNeverRan() {
        NeoModerationPlugin plugin = plugin();
        plugin.cloudPlan().rejected("nm_trial_key");
        List<String> lines = run(new StatusCmd(plugin), plugin);
        assertTrue(lines.contains("Cloud key: rejected. Create or replace it at " + CloudRecovery.API_KEYS_URL
                + ", then run /nmod setup <key>."), String.join("\n", lines));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("Last cloud check")), String.join("\n", lines));
        assertClean(lines);
    }

    @Test
    void aRunningTrialOutOfCreditsIsToldToKeepItWithoutItsLinkInText() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, RUNNING, ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        when(plugin.coordinator().lastCloudResultKind()).thenReturn(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);

        List<String> status = run(new StatusCmd(plugin), plugin);
        assertTrue(status.contains("Cloud plan: free trial until 22 October 2026, credits used up. Run /nmod trial "
                + "status to keep it in a free account, then add credits there."), String.join("\n", status));
        assertTrue(status.contains("Last cloud check: refused."), String.join("\n", status));
        assertClean(status);

        List<String> doctor = run(new DoctorCmd(plugin), plugin);
        assertTrue(doctor.contains("  Fix Cloud plan: the free trial's credits are used up. Run /nmod trial status to "
                + "keep it in a free account, then add credits there"), String.join("\n", doctor));
        assertTrue(doctor.contains("  Fix Last cloud check: refused"), String.join("\n", doctor));
        assertClean(doctor);
    }

    @Test
    void usageSaysARunningTrialWithNoCreditsLeftIsKeptFirst() throws Exception {
        HttpServer server = usageServer(0);
        try {
            NeoModerationPlugin plugin = plugin("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events");
            TestPlugin.plan(plugin, RUNNING);
            List<String> lines = run(new UsageCmd(plugin), plugin);
            assertTrue(lines.contains("  Credits left: 0. Run /nmod trial status to keep the trial in a free account, "
                    + "then add credits there."), String.join("\n", lines));
            assertClean(lines);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doctorSaysARunningTrialWithNoCreditsLeftIsKeptFirst() throws Exception {
        HttpServer server = usageServer(0);
        try {
            NeoModerationPlugin plugin = plugin("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events");
            TestPlugin.plan(plugin, RUNNING);
            List<String> lines = run(new DoctorCmd(plugin), plugin);
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("  Fix Credits: none left (")
                            && line.endsWith(" ms). Run /nmod trial status to keep the trial in a free account, then add "
                            + "credits there")), String.join("\n", lines));
            assertClean(lines);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doctorSaysNoCreditsAreLeftOnceWhenThePlanLineAlreadySaysWhatToDo() throws Exception {
        HttpServer server = usageServer(0);
        try {
            NeoModerationPlugin plugin = plugin("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events");
            TestPlugin.plan(plugin, RUNNING, ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
            List<String> lines = run(new DoctorCmd(plugin), plugin);
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("  Fix Credits: none left (") && line.endsWith(" ms)")),
                    String.join("\n", lines));
            assertTrue(lines.contains("  Fix Cloud plan: the free trial's credits are used up. Run /nmod trial status to "
                    + "keep it in a free account, then add credits there"), String.join("\n", lines));
            assertClean(lines);
        } finally {
            server.stop(0);
        }
    }

    /** A usage endpoint answering for a trial workspace with {@code credits} left. */
    private static HttpServer usageServer(long credits) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workspace/usage", exchange -> {
            byte[] body = ("{\"workspace\":\"Trial\",\"tier\":\"free\",\"requestsPerMinuteLimit\":60,"
                    + "\"creditsRemaining\":" + credits + ",\"requestsToday\":5,\"requestsLast7Days\":40}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void doctorLabelsEveryPlanLineAndSaysAKeyFromAnAccount() {
        for (TrialClient.TrialStatusResult status : List.of(RUNNING, ENDED, STOPPED,
                new TrialClient.TrialStatusResult(false, "standard_workspace", "", 0, "https://billing"))) {
            NeoModerationPlugin plugin = plugin();
            TestPlugin.plan(plugin, status);
            List<String> lines = run(new DoctorCmd(plugin), plugin);
            assertTrue(lines.stream().anyMatch(line -> line.contains("Cloud plan: ")), String.join("\n", lines));
            assertClean(lines);
        }
    }

    @Test
    void doctorTellsPlayersInAnotherLanguageWhatToTurnOn() {
        NeoModerationPlugin plugin = plugin();
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        List<org.bukkit.entity.Player> online = List.of(TestPlugin.player("Kari", "nb_no"),
                TestPlugin.player("Jens", "da_dk"), TestPlugin.player("Ann", "en_us"));
        when(server.getOnlinePlayers()).thenAnswer(call -> online);
        when(plugin.getServer()).thenReturn(server);
        List<String> lines = run(new DoctorCmd(plugin), plugin);
        assertTrue(lines.contains("  Check Players' languages: 2 of 3 players online play in another language. A word "
                + "that is everyday in their language but banned in another gets their line caught. Set "
                + "moderation.offline.trustPlayerLanguage: true to let such words through when they write in their game "
                + "language, which each player picks"), String.join("\n", lines));
        assertClean(lines);
    }

    @Test
    void testSaysAnEndedTrialIsNotABadKey() {
        NeoModerationPlugin plugin = plugin();
        TestPlugin.plan(plugin, ENDED);
        ModerationApiClient api = mock(ModerationApiClient.class);
        when(api.moderateText(anyString(), anyString(), anyString(), any(), any())).thenReturn(ModerationApiResult.clientAuth());
        when(plugin.apiClient()).thenReturn(api);
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        when(admin.getName()).thenReturn("CONSOLE");

        new TestCmd(plugin).execute(admin, "nmod", new String[]{"test", "hello"});

        List<String> lines = TestPlugin.lines(admin);
        assertTrue(lines.contains("  Cloud: the free trial ended on 1 October 2026. Create a free account at " + SIGNUP
                + ", then a key, and run /nmod setup <key>."), String.join("\n", lines));
        assertClean(lines);
    }

    @Test
    void usageSaysAnEndedTrialIsNotABadKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workspace/usage", exchange -> {
            byte[] body = "{\"error\":\"api_key_expired\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            NeoModerationPlugin plugin = plugin("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events");
            TestPlugin.plan(plugin, ENDED);
            List<String> lines = run(new UsageCmd(plugin), plugin);
            assertTrue(lines.contains("[NeoMod] The free trial ended. Create a free account at " + SIGNUP
                    + ", then a key, and run /nmod setup <key>."), String.join("\n", lines));
            assertClean(lines);
        } finally {
            server.stop(0);
        }
    }
}
