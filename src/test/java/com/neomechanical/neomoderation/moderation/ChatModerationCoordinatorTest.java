package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.CaseSettings;
import com.neomechanical.neomoderation.config.MapArtSettings;
import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.neomechanical.neomoderation.config.ModerationCategorySettings;
import com.neomechanical.neomoderation.config.ModerationMode;
import com.neomechanical.neomoderation.config.ModerationSettings;
import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import com.neomechanical.neomoderation.config.SpamSettings;
import com.neomechanical.neomoderation.config.StrikeSettings;
import com.neomechanical.neomoderation.config.SurfaceSettings;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModerationCoordinatorTest {
    @Test
    void transientFailureFollowsFailOpenSetting() throws Exception {
        assertFalse(decisionFor(503, "unavailable", true));
        assertTrue(decisionFor(503, "unavailable", false));
    }

    @Test
    void authenticationFailureFollowsFailOpenSetting() throws Exception {
        assertFalse(decisionFor(401, "invalid key", true));
        assertTrue(decisionFor(401, "invalid key", false));
    }

    @Test
    void paymentAndAuthenticationFailuresStayDistinct() throws Exception {
        assertEquals(ModerationApiResult.Kind.INSUFFICIENT_CREDITS, resultKindFor(402, "credits exhausted"));
        assertEquals(ModerationApiResult.Kind.CLIENT_AUTH, resultKindFor(403, "invalid key"));
        assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, resultKindFor(422, "bad request"));
    }

    @Test
    void httpRequestTimeoutIsATransientTransportFailure() throws Exception {
        assertEquals(ModerationApiResult.Kind.TRANSIENT_TRANSPORT,
                resultKindFor(408, "request timed out"));
    }

    @Test
    void malformedConfiguredEndpointIsAClientConfigurationFailure() {
        ModerationSettings malformed = settings("not a valid URI", true);

        ModerationApiResult result = new ModerationApiClient().moderateText(
                "Tester",
                "00000000-0000-0000-0000-000000000001",
                "hello",
                malformed.api(),
                malformed.categories()
        );

        assertEquals(ModerationApiResult.Kind.CLIENT_REQUEST, result.kind());
    }

    @Test
    void malformedOrMissingVerdictsAreNotCleanScans() throws Exception {
        assertEquals(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, resultKindFor(200, "not JSON"));
        assertEquals(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, resultKindFor(200, "{}"));
        assertEquals(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, resultKindFor(200, "{\"jobId\":\"pending\"}"));
    }

    @Test
    void openCircuitFollowsFailOpenSetting() {
        ModerationCircuitBreaker breaker = new ModerationCircuitBreaker(Logger.getLogger("test"));
        breaker.record(ModerationApiResult.transientTransport());
        breaker.record(ModerationApiResult.transientTransport());
        breaker.record(ModerationApiResult.transientTransport());

        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(
                breaker, new ModerationApiClient())) {
            assertTrue(coordinator.checkMessage(
                    player(), "hello", settings("http://127.0.0.1:1", false)).kind() == ModerationApiResult.Kind.TRANSIENT_TRANSPORT);
        }
    }

    @Test
    void shutdownRejectsCloudWorkWithoutThrowingIntoTheChatListener() {
        ChatModerationCoordinator coordinator = new ChatModerationCoordinator(
                new ModerationCircuitBreaker(Logger.getLogger("test")), new ModerationApiClient());
        coordinator.close();
        assertEquals(ModerationApiResult.Kind.OVERLOADED,
                coordinator.checkMessage(player(), "hello", settings("http://127.0.0.1:1", true)).kind());
    }

    @Test
    void busyWorkersRejectExcessCloudRequestsWithoutOpeningTheCircuit() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ModerationApiClient apiClient = mock(ModerationApiClient.class);
        when(apiClient.moderateText(anyString(), anyString(), anyString(),
                any(ModerationApiSettings.class), any(ModerationCategorySettings.class))).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "Cloud requests were not released");
            return ModerationApiResult.clear();
        });
        ModerationCircuitBreaker breaker = new ModerationCircuitBreaker(Logger.getLogger("test"));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        Player player = player();
        ModerationSettings settings = settings("http://127.0.0.1:1", true, 1000);

        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(breaker, apiClient)) {
            Future<ModerationApiResult> first = callers.submit(() -> coordinator.checkMessage(player, "first", settings));
            Future<ModerationApiResult> second = callers.submit(() -> coordinator.checkMessage(player, "second", settings));
            assertTrue(started.await(2, TimeUnit.SECONDS), "Both cloud workers must be occupied");

            for (int i = 0; i < 3; i++) {
                assertEquals(ModerationApiResult.Kind.OVERLOADED,
                        coordinator.checkMessage(player, "excess", settings).kind());
            }
            assertTrue(coordinator.isRemoteCallAllowed());
            assertEquals(ModerationApiResult.Kind.OVERLOADED, coordinator.lastCloudResultKind());
            verify(apiClient, times(2)).moderateText(anyString(), anyString(), anyString(),
                    any(ModerationApiSettings.class), any(ModerationCategorySettings.class));

            release.countDown();
            assertEquals(ModerationApiResult.Kind.CLEAR, first.get(2, TimeUnit.SECONDS).kind());
            assertEquals(ModerationApiResult.Kind.CLEAR, second.get(2, TimeUnit.SECONDS).kind());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void clearAndFlaggedResponsesKeepTheirMeaning() throws Exception {
        assertFalse(decisionFor(200, "{\"decision\":{\"status\":\"clean\"}}", false));
        assertTrue(decisionFor(200, "{\"status\":\"blocked\"}", true));
    }

    private boolean decisionFor(int status, String body, boolean failOpen) throws Exception {
        HttpServer server = localServer(status, body);
        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(
                new ModerationCircuitBreaker(Logger.getLogger("test")), new ModerationApiClient())) {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events";
            ModerationApiResult result = coordinator.checkMessage(player(), "hello", settings(endpoint, failOpen));
            return result.isFlagged() || (!failOpen && result.kind() != ModerationApiResult.Kind.CLEAR);
        } finally {
            server.stop(0);
        }
    }

    private ModerationApiResult.Kind resultKindFor(int status, String body) throws Exception {
        HttpServer server = localServer(status, body);
        try (ChatModerationCoordinator coordinator = new ChatModerationCoordinator(
                new ModerationCircuitBreaker(Logger.getLogger("test")), new ModerationApiClient())) {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events";
            coordinator.checkMessage(player(), "hello", settings(endpoint, true));
            return coordinator.lastCloudResultKind();
        } finally {
            server.stop(0);
        }
    }

    private HttpServer localServer(int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/events", exchange -> {
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Tester");
        when(player.getUniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return player;
    }

    private ModerationSettings settings(String endpoint, boolean failOpen) {
        return settings(endpoint, failOpen, 100);
    }

    private ModerationSettings settings(String endpoint, boolean failOpen, int timeoutMs) {
        return new ModerationSettings(
                true,
                ModerationMode.ENFORCE,
                ModerationMode.ENFORCE,
                new ModerationApiSettings(endpoint, "test-key", timeoutMs, timeoutMs),
                new OfflineModerationSettings(true, false, true, List.of(), List.of(), List.of(), List.of()),
                new ModerationCategorySettings(Map.of()),
                new MapArtSettings(true, true, true, true, 1000),
                List.of(),
                true,
                failOpen,
                new ModerationSettings.AlertSettings(true, true),
                new SpamSettings(false, 0, 0, 0.9D, 0, 0, 0, 0),
                new StrikeSettings(false, 30, List.of()),
                new SurfaceSettings(
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        SurfaceSettings.SurfaceMode.OFF,
                        List.of()),
                new CaseSettings(false, false),
                false,
                true
        );
    }
}
