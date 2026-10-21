package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TrialStatusResponseTest {
    @Test
    void missingMalformedAndNestedStatusCannotPretendToBeAStandardWorkspace() {
        for (String body : new String[]{"{}", "not JSON", "{\"metadata\":{\"isTrial\":false}}",
                "{\"isTrial\":\"false\",\"status\":\"standard_workspace\"}"}) {
            assertThrows(TrialClient.TrialException.class, () -> TrialClient.parseStatusSuccess(body));
        }
    }

    @Test
    void recognizesTheExplicitTrialAndStandardWorkspaceContracts() throws Exception {
        assertFalse(TrialClient.parseStatusSuccess(
                "{\"isTrial\":false,\"status\":\"standard_workspace\"}").isTrial());
        var active = TrialClient.parseStatusSuccess(
                "{\"isTrial\":true,\"status\":\"active\",\"daysRemaining\":12,"
                        + "\"expiresAt\":\"2026-10-22T00:00:00Z\",\"upgradeUrl\":\"https://neomechanical.com/billing\"}");
        assertEquals(12, active.daysRemaining());
        assertFalse(active.isExpired());
        assertEquals("https://neomechanical.com/billing", active.upgradeUrl());
    }

    @Test
    void aRevokedTrialHasEndedLikeAnExpiredOne() throws Exception {
        var revoked = TrialClient.parseStatusSuccess(
                "{\"isTrial\":true,\"status\":\"revoked\",\"daysRemaining\":0,\"expiresAt\":\"2026-10-22T00:00:00Z\","
                        + "\"activatedAt\":\"2026-10-08T00:00:00Z\"}");
        assertTrue(revoked.isRevoked());
        assertTrue(revoked.hasEnded());
        assertFalse(revoked.isExpired());
        assertEquals("2026-10-08T00:00:00Z", revoked.activatedAt());
        assertTrue(TrialClient.parseStatusSuccess("{\"isTrial\":true,\"status\":\"expired\"}").hasEnded());
        assertThrows(TrialClient.TrialException.class, () -> TrialClient.parseStatusSuccess(
                "{\"isTrial\":true,\"status\":\"paused\"}"));
    }

    @Test
    void anActivationTimeItCannotReadCostsNothingElse() throws Exception {
        var status = TrialClient.parseStatusSuccess(
                "{\"isTrial\":true,\"status\":\"active\",\"expiresAt\":\"2026-10-22T00:00:00Z\",\"activatedAt\":7}");
        assertEquals("", status.activatedAt());
        assertTrue(status.isTrial());
    }

    @Test
    void aRunningTrialsDaysAndEndAreCountedFromItsEndDateAsTheServiceCountsThem() {
        var trial = new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 9, "https://claim");
        long end = Instant.parse("2026-10-22T00:00:00Z").toEpochMilli();
        assertEquals(2, trial.daysLeft(end - 2 * 86_400_000L));
        assertEquals(2, trial.daysLeft(end - 86_400_000L - 1), "any part of a day is a day");
        assertEquals(1, trial.daysLeft(end - 1));
        assertEquals(0, trial.daysLeft(end));
        assertFalse(trial.endedBy(end - 1));
        assertTrue(trial.endedBy(end), "a trial key stops working at its end date");

        var unreadable = new TrialClient.TrialStatusResult(true, "active", "next week", 9, "https://claim");
        assertEquals(9, unreadable.daysLeft(end), "the days it sent when the date cannot be read");
        assertFalse(unreadable.endedBy(end));
        assertFalse(new TrialClient.TrialStatusResult(false, "standard_workspace", "2026-10-01T00:00:00Z", 0,
                "https://billing").endedBy(end), "a key from an account never ends by date");
    }

    @Test
    void anActivationNeverLosesTheIssuedKeyOverAFieldItCannotRead() throws Exception {
        var trial = TrialClient.parseSuccess("{\"apiKey\":\"nm_x\",\"expiresAt\":\"2026-10-22T00:00:00Z\","
                + "\"daysRemaining\":14,\"claimUrl\":\"https://c\"}");
        assertEquals(14, trial.daysRemaining());
        assertEquals("2026-10-22T00:00:00Z", trial.expiresAt());
        assertEquals("https://c", trial.claimUrl());

        for (String rest : new String[]{",\"plan\":\"free\",\"freePlan\":{\"allowanceCredits\":\"lots\"}",
                ",\"expiresAt\":null", ",\"claimUrl\":42", ",\"daysRemaining\":-3", ",\"expiresAt\":7"}) {
            var result = TrialClient.parseSuccess("{\"apiKey\":\"nm_kept\"" + rest + "}");
            assertEquals("nm_kept", result.apiKey(), rest);
            assertFalse(result.expiresAt().isBlank(), rest);
        }
        assertThrows(TrialClient.TrialException.class, () -> TrialClient.parseSuccess("{\"claimUrl\":\"x\"}"),
                "with no key there is nothing to keep");
    }

    @Test
    void onlyThe401OfTheTrialServiceRejectsTheKey() throws Exception {
        // The trial service never answers 403; a proxy in front of it may, and that says nothing about the key.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] status = {401};
        server.createContext("/v1/plugins/neomoderation/trial/status", exchange -> {
            exchange.sendResponseHeaders(status[0], -1);
            exchange.close();
        });
        server.start();
        try {
            ModerationApiSettings api = new ModerationApiSettings(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/events", "nm_key", 2000, 2000);
            TrialClient client = new TrialClient();
            assertEquals(TrialClient.TrialError.FORBIDDEN, assertThrows(TrialClient.TrialException.class,
                    () -> client.fetchTrialStatus(api)).error());
            status[0] = 403;
            assertEquals(TrialClient.TrialError.SERVER_ERROR, assertThrows(TrialClient.TrialException.class,
                    () -> client.fetchTrialStatus(api)).error());
        } finally {
            server.stop(0);
        }
    }
}
