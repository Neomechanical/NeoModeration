package com.neomechanical.neomoderation.moderation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The trial service's free plan calls from 1.8.0: what they send, and how each refusal is read. */
class FreePlanClientTest {

    private static final String INSTALL = "00000000-0000-4000-8000-000000000042";
    private static final String FREE_STATUS = "{\"authorized\":true,\"exists\":true,\"isTrial\":false,\"active\":true,"
            + "\"expired\":false,\"status\":\"free_plan\",\"plan\":\"free\",\"freePlan\":{\"dailyChecks\":2000,"
            + "\"checksToday\":12,\"dailyImages\":300,\"imagesToday\":1,\"resetsAt\":\"2026-11-03T00:00:00.000Z\","
            + "\"claimed\":false,\"claimPending\":false},\"claimUrl\":\"https://neomechanical.com/signup?trial=v2.a\"}";

    private HttpServer server;
    private final List<Map<String, String>> asked = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private record Reply(int status, String body, String retryAfter) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/plugins/neomoderation/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        asked.add(Map.of("path", path, "method", exchange.getRequestMethod(),
                "install", String.valueOf(exchange.getRequestHeaders().getFirst("X-NeoModeration-Install-Id")),
                "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply reply = replies.getOrDefault(path, new Reply(500, "{}", null));
        if (reply.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ModerationApiSettings api() {
        return new ModerationApiSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events", "nm_key",
                2000, 2000);
    }

    @Test
    void activationSendsTheTermsAndAComeBackAsksForTheFreePlan() throws Exception {
        replies.put("/v1/plugins/neomoderation/trial", new Reply(201, FREE_STATUS.replace("\"authorized\":true",
                "\"apiKey\":\"nm_free\",\"authorized\":true"), null));
        TrialClient client = new TrialClient(() -> INSTALL);
        TrialClient.TrialResult started = client.activate(api(), INSTALL, "Paper", false);
        assertTrue(started.onFreePlan());
        assertEquals("nm_free", started.apiKey());
        JsonObject sent = JsonParser.parseString(asked.get(0).get("body")).getAsJsonObject();
        assertEquals(TrialClient.TERMS_VERSION, sent.get("termsVersion").getAsString());
        assertFalse(sent.has("plan"), "a new install asks for nothing in particular");

        client.activate(api(), INSTALL, "Paper", true);
        assertEquals("free", JsonParser.parseString(asked.get(1).get("body")).getAsJsonObject().get("plan").getAsString());
    }

    @Test
    void statusAndTheMoveNameThisInstallButAClientWithoutOneSendsNoHeader() throws Exception {
        replies.put("/v1/plugins/neomoderation/trial/status", new Reply(200, FREE_STATUS, null));
        replies.put("/v1/plugins/neomoderation/plan", new Reply(200, FREE_STATUS, null));
        TrialClient client = new TrialClient(() -> INSTALL);
        TrialClient.TrialStatusResult status = client.fetchTrialStatus(api());
        assertEquals(INSTALL, asked.get(0).get("install"));
        assertEquals("https://neomechanical.com/signup?trial=v2.a", status.upgradeUrl(), "an unclaimed plan's link");
        assertEquals(12, status.freePlan().checksToday());

        TrialClient.TrialStatusResult moved = client.convertToFreePlan(api());
        assertEquals("POST", asked.get(1).get("method"));
        assertEquals(INSTALL, asked.get(1).get("install"));
        assertTrue(JsonParser.parseString(asked.get(1).get("body")).getAsJsonObject().get("consent").getAsBoolean());
        assertTrue(moved.onFreePlan());

        new TrialClient().fetchTrialStatus(api());
        assertEquals("null", asked.get(2).get("install"), "no install id, no header");
    }

    @Test
    void refusalsAreWordedByTheirCodeWithTheWaitTheyGive() throws Exception {
        TrialClient client = new TrialClient(() -> INSTALL);
        String trial = "/v1/plugins/neomoderation/trial";
        replies.put(trial, new Reply(429, "{\"error\":\"ip_trial_lifetime_limit\",\"message\":\"x\"}", null));
        assertEquals(TrialClient.TrialError.LIFETIME_LIMIT, refusal(client).error());
        replies.put(trial, new Reply(429, "{\"error\":\"ip_trial_limit_reached\",\"retryAt\":\"2026-11-09\"}", "86400"));
        TrialClient.TrialException cooldown = refusal(client);
        assertEquals(TrialClient.TrialError.COOLDOWN, cooldown.error());
        assertEquals("2026-11-09", cooldown.retryAt());
        assertEquals(86400, cooldown.retryAfterSeconds(), "the header first");
        replies.put(trial, new Reply(429, "{\"error\":\"trial_activation_busy\",\"retryAfterSeconds\":4}", null));
        TrialClient.TrialException busy = refusal(client);
        assertEquals(TrialClient.TrialError.BUSY, busy.error());
        assertEquals(4, busy.retryAfterSeconds(), "else the body's");
        replies.put(trial, new Reply(429, "{\"error\":\"free_plan_closed\"}", "3600"));
        assertEquals(TrialClient.TrialError.FREE_PLAN_CLOSED, refusal(client).error());
        replies.put(trial, new Reply(409, "{\"error\":\"already_back\"}", null));
        TrialClient.TrialException back = refusal(client);
        assertEquals(TrialClient.TrialError.REFUSED, back.error());
        assertEquals("already_back", back.code());
        // A service or proxy that names no code is read from the status, as before 1.8.0.
        replies.put(trial, new Reply(409, "", null));
        assertEquals(TrialClient.TrialError.ALREADY_CLAIMED, refusal(client).error());
        replies.put(trial, new Reply(429, "<html>slow down</html>", null));
        assertEquals(TrialClient.TrialError.RATE_LIMITED, refusal(client).error());

        replies.put("/v1/plugins/neomoderation/plan", new Reply(409, "{\"error\":\"free_plan_not_open\"}", null));
        TrialClient.TrialException notOpen = assertThrows(TrialClient.TrialException.class, () -> client.convertToFreePlan(api()));
        assertEquals("free_plan_not_open", notOpen.code());
    }

    private TrialClient.TrialException refusal(TrialClient client) {
        return assertThrows(TrialClient.TrialException.class, () -> client.activate(api(), INSTALL, "Paper", false));
    }
}
