package com.neomechanical.neomoderation.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCommandLogFilterTest {
    @Test
    void commandsThatCarryASecretAreKeptOutOfTheLog() {
        for (String line : List.of(
                "Steve issued server command: /nmod setup nm_live_abc123",
                "Steve issued server command: /NMOD key set nm_live_abc123",
                "Steve issued server command: /neomod discord https://discord.com/api/webhooks/1/abc",
                "Steve issued server command: /neomoderation:nmod setup nm_live_abc123")) {
            assertTrue(SecretCommandLogFilter.hides(line), line);
        }
    }

    @Test
    void everythingElseIsLoggedAsUsual() {
        for (String line : List.of(
                "Steve issued server command: /nmod setup",
                "Steve issued server command: /nmod key clear",
                "Steve issued server command: /nmod discord test",
                "Steve issued server command: /nmod discord off",
                "Steve issued server command: /nmod status",
                "Steve issued server command: /msg Alex setup nm_live_abc123",
                "[NeoModeration] Blocked Steve in chat: /nmod setup nm_live_abc123 (banned word \"x\")")) {
            assertFalse(SecretCommandLogFilter.hides(line), line);
        }
    }
}
