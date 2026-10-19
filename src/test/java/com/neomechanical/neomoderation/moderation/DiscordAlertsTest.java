package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordAlertsTest {
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1/abc";
    /** Longer than any test runs, so only the test flushes. */
    private static final long NEVER = 3_600;

    @Test
    void onlyDiscordWebhookAddressesAreEverContacted() {
        assertTrue(DiscordAlerts.isWebhookUrl(WEBHOOK));
        assertTrue(DiscordAlerts.isWebhookUrl("  https://canary.discord.com/api/webhooks/1/abc "));
        assertTrue(DiscordAlerts.isWebhookUrl("https://discordapp.com/api/webhooks/1/abc"));
        assertFalse(DiscordAlerts.isWebhookUrl("http://discord.com/api/webhooks/1/abc"));
        assertFalse(DiscordAlerts.isWebhookUrl("https://discord.com.example.net/api/webhooks/1/abc"));
        assertFalse(DiscordAlerts.isWebhookUrl("https://example.net/api/webhooks/1/abc"));
        assertFalse(DiscordAlerts.isWebhookUrl("https://discord.com/channels/1/2"));
        assertFalse(DiscordAlerts.isWebhookUrl(""));
        assertFalse(DiscordAlerts.isWebhookUrl(null));
    }

    @Test
    void aPostPingsNobodyAndCarriesWhatAPlayerWroteAsText() {
        String json = DiscordAlerts.payload(List.of(alert("Blocked", "Message", "`@everyone \"hi\"\n`")));

        assertTrue(json.contains("\"allowed_mentions\":{\"parse\":[]}"), json);
        assertTrue(json.contains("\"value\":\"`@everyone \\\"hi\\\"\\n`\""), json);
    }

    @Test
    void emptyFieldsAreLeftOutAndLongOnesAreShortened() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Player", "Steve");
        fields.put("Then", "");
        fields.put("Why", "x".repeat(2_000));
        String json = DiscordAlerts.payload(List.of(new DiscordAlerts.Alert("Blocked", 1, fields)));

        assertFalse(json.contains("\"Then\""), json);
        assertTrue(json.contains("\"value\":\"" + "x".repeat(1_021) + "...\""), json);
        assertTrue(json.contains("{\"name\":\"Player\",\"value\":\"Steve\",\"inline\":true}"), json);
    }

    @Test
    void aBurstGoesOutAsOnePostThatSumsUpTheRest() throws Exception {
        HttpClient http = answering(204);
        try (DiscordAlerts alerts = new DiscordAlerts(http, NEVER)) {
            for (int i = 1; i <= 12; i++) {
                alerts.submit(WEBHOOK, alert("Blocked " + i, "Player", "Steve"), "and {count} more");
            }
            alerts.flush();
            alerts.flush();
        }

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(request.capture(), any());
        String body = body(request.getValue());
        assertEquals(10, count(body, "\"title\":"), body);
        assertTrue(body.contains("\"title\":\"Blocked 9\""), body);
        assertFalse(body.contains("\"title\":\"Blocked 10\""), body);
        assertTrue(body.contains("\"title\":\"and 3 more\""), body);
        assertEquals(WEBHOOK, request.getValue().uri().toString());
    }

    @Test
    void aQuietMinuteSendsEachAlertWithoutASummary() throws Exception {
        HttpClient http = answering(204);
        try (DiscordAlerts alerts = new DiscordAlerts(http, NEVER)) {
            for (int i = 1; i <= 3; i++) {
                alerts.submit(WEBHOOK, alert("Blocked " + i, "Player", "Steve"), "and {count} more");
            }
            alerts.flush();
        }

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        String body = body(request.getValue());
        assertEquals(3, count(body, "\"title\":"), body);
        assertFalse(body.contains("more"), body);
    }

    @Test
    void anAddressThatIsNotAWebhookIsNeverQueued() throws Exception {
        HttpClient http = answering(204);
        try (DiscordAlerts alerts = new DiscordAlerts(http, NEVER)) {
            alerts.submit("https://example.net/hook", alert("Blocked", "Player", "Steve"), "and {count} more");
            alerts.flush();
        }

        verify(http, never()).send(any(), any());
    }

    @Test
    void theLastProblemIsKeptUntilAPostWorksAgain() throws Exception {
        HttpClient http = answering(404);
        try (DiscordAlerts alerts = new DiscordAlerts(http, NEVER)) {
            alerts.submit(WEBHOOK, alert("Blocked", "Player", "Steve"), "and {count} more");
            alerts.flush();
            assertEquals(DiscordAlerts.Problem.GONE, alerts.lastProblem());

            doThrow(new IOException("offline")).when(http).send(any(), any());
            alerts.submit(WEBHOOK, alert("Blocked", "Player", "Steve"), "and {count} more");
            alerts.flush();
            assertEquals(DiscordAlerts.Problem.UNREACHABLE, alerts.lastProblem());

            HttpResponse<?> ok = mock(HttpResponse.class);
            when(ok.statusCode()).thenReturn(204);
            doReturn(ok).when(http).send(any(), any());
            alerts.submit(WEBHOOK, alert("Blocked", "Player", "Steve"), "and {count} more");
            alerts.flush();
            assertNull(alerts.lastProblem());
        }
    }

    private static DiscordAlerts.Alert alert(String title, String field, String value) {
        return new DiscordAlerts.Alert(title, 0xFF7A70, Map.of(field, value));
    }

    private static HttpClient answering(int status) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<?> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        doReturn(response).when(http).send(any(), any());
        return http;
    }

    /** The JSON a request carries. The string publisher hands its bytes over as soon as they are asked for. */
    private static String body(HttpRequest request) {
        StringBuilder out = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                out.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }

            @Override
            public void onComplete() {
            }
        });
        return out.toString();
    }

    private static int count(String text, String part) {
        Matcher matcher = Pattern.compile(Pattern.quote(part)).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
