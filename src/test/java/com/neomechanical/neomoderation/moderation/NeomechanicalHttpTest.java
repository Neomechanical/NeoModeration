package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NeomechanicalHttpTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Builds as a request, then the client refuses it when sending: "port out of range". */
    private static final String PORT_OUT_OF_RANGE = "https://127.0.0.1:99999/v1/events";

    @Test
    void anEndpointThatIsNoUrlNeverMakesARequest() {
        for (String endpoint : new String[]{"not a url", "/v1/events", "ftp://api.neomechanical.com/v1/events"}) {
            NeomechanicalHttp.NoAnswer noAnswer = assertThrows(NeomechanicalHttp.NoAnswer.class,
                    () -> NeomechanicalHttp.send(() -> NeomechanicalHttp.withKey(endpoint, TIMEOUT, "nm_key").GET()));
            assertEquals(NeomechanicalHttp.NoAnswer.Reason.INVALID_REQUEST, noAnswer.reason(), endpoint);
        }
    }

    @Test
    void aRefusedConnectionIsNoAnswerAndNotABadEndpoint() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0)) {
            port = closed.getLocalPort();
        }
        String endpoint = "http://127.0.0.1:" + port + "/v1/events";
        NeomechanicalHttp.NoAnswer noAnswer = assertThrows(NeomechanicalHttp.NoAnswer.class,
                () -> NeomechanicalHttp.send(() -> NeomechanicalHttp.request(endpoint, TIMEOUT).GET()));
        assertEquals(NeomechanicalHttp.NoAnswer.Reason.FAILED, noAnswer.reason());
        assertNotNull(noAnswer.getCause(), "the network's own error is kept for whoever reads it");
    }

    @Test
    void aRequestTheClientRefusesToSendIsNoAnswer() {
        NeomechanicalHttp.NoAnswer noAnswer = assertThrows(NeomechanicalHttp.NoAnswer.class,
                () -> NeomechanicalHttp.send(() -> NeomechanicalHttp.request(PORT_OUT_OF_RANGE, TIMEOUT).GET()));
        assertEquals(NeomechanicalHttp.NoAnswer.Reason.FAILED, noAnswer.reason());
    }

    @Test
    void theTrialAndUsageCallsReadARequestTheClientRefusesAsAFailedCall() {
        ModerationApiSettings api = new ModerationApiSettings(PORT_OUT_OF_RANGE, "nm_key", 2000, 2000);
        assertEquals(TrialClient.TrialError.TRANSPORT_ERROR, assertThrows(TrialClient.TrialException.class,
                () -> new TrialClient().fetchTrialStatus(api)).error());
        assertEquals(TrialClient.TrialError.TRANSPORT_ERROR, assertThrows(TrialClient.TrialException.class,
                () -> new TrialClient().activateTrial(api, "00000000-0000-0000-0000-000000000001", "Paper")).error());
        assertEquals(ModerationApiResult.Kind.TRANSIENT_TRANSPORT, assertThrows(
                NeoMechanicalUsageClient.UsageException.class,
                () -> new NeoMechanicalUsageClient().fetchUsage(api)).kind());
    }

    @Test
    void aRedirectIsTheAnswerAndTheKeyNeverFollowsIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean followed = new AtomicBoolean();
        AtomicReference<String> named = new AtomicReference<>();
        server.createContext("/v1/events", exchange -> {
            named.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            followed.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpResponse<String> response = NeomechanicalHttp.send(() -> NeomechanicalHttp.withKey(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events", TIMEOUT, "nm_key").GET());
            assertEquals(302, response.statusCode());
            assertFalse(followed.get(), "the key must never go where a redirect points");
            assertEquals(ClientIdentity.userAgent(), named.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anInterruptedCallIsNoAnswerAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            NeomechanicalHttp.NoAnswer noAnswer = assertThrows(NeomechanicalHttp.NoAnswer.class,
                    () -> NeomechanicalHttp.send(() -> NeomechanicalHttp.request("http://127.0.0.1:9/v1/events", TIMEOUT).GET()));
            assertEquals(NeomechanicalHttp.NoAnswer.Reason.INTERRUPTED, noAnswer.reason());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
