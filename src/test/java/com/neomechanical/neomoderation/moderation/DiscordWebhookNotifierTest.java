package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookNotifierTest {

    @Test
    void isValidWebhookUrlAcceptsOfficialDiscordHttpsEndpoints() {
        assertTrue(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://discord.com/api/webhooks/123456789/abcdef-token_123"));
        assertTrue(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://discordapp.com/api/webhooks/987654321/token-XYZ"));
        assertTrue(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://ptb.discord.com/api/webhooks/11223344/token-ptb"));
        assertTrue(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://canary.discord.com/api/webhooks/55667788/token-canary"));

        // Case insensitivity
        assertTrue(DiscordWebhookNotifier.isValidWebhookUrl(
                "HTTPS://DISCORD.COM/API/WEBHOOKS/123/token"));
    }

    @Test
    void isValidWebhookUrlRejectsInsecureHttp() {
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "http://discord.com/api/webhooks/123456789/token"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "http://discordapp.com/api/webhooks/123456789/token"));
    }

    @Test
    void isValidWebhookUrlRejectsArbitraryHostsAndSsrf() {
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://attacker.example.com/api/webhooks/123456/token"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "http://localhost:8080/api/webhooks/123"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "http://127.0.0.1:8080/api/webhooks/123"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://127.0.0.1/api/webhooks/123"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "http://169.254.169.254/latest/meta-data/"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(
                "https://discord.com.attacker.com/api/webhooks/123"));
    }

    @Test
    void isValidWebhookUrlRejectsBlanksAndNullsAndMalformed() {
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(null));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl(""));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl("   "));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl("not a url"));
        assertFalse(DiscordWebhookNotifier.isValidWebhookUrl("https://discord.com/api/webhooks/ with spaces"));
    }

    @Test
    void buildPayloadRendersMonitorStyleCorrectly() {
        String payload = DiscordWebhookNotifier.buildPayload(
                "Steve", "chat", "blocked_word:badword", "hello badword", "none", true);

        assertTrue(payload.contains("\"title\":\"NeoModeration Monitor Alert\""));
        assertTrue(payload.contains("\"color\":16096779")); // 0xF59E0B
        assertTrue(payload.contains("**Player:** `Steve`"));
        assertTrue(payload.contains("**Surface:** `chat`"));
        assertTrue(payload.contains("**Rule:** `blocked_word:badword`"));
        assertTrue(payload.contains("**Action:** `none`"));
        assertTrue(payload.contains("**Preview:** `hello badword`"));
    }

    @Test
    void buildPayloadRendersEnforceStyleCorrectly() {
        String payload = DiscordWebhookNotifier.buildPayload(
                "Alex", "sign", "blocked_url:grabify.link", "click grabify.link", "block", false);

        assertTrue(payload.contains("\"title\":\"NeoModeration Enforcement Alert\""));
        assertTrue(payload.contains("\"color\":8490232")); // 0x818CF8
        assertTrue(payload.contains("**Player:** `Alex`"));
        assertTrue(payload.contains("**Surface:** `sign`"));
        assertTrue(payload.contains("**Action:** `block`"));
    }

    @Test
    void buildPayloadEscapesJsonSpecialCharacters() {
        String trickyPlayer = "Steve \"The Hacker\" \\";
        String trickyPreview = "Line 1\nLine 2\rLine 3\tTabbed `backticked` \"quoted\"";

        String payload = DiscordWebhookNotifier.buildPayload(
                trickyPlayer, "chat", "rule\\test", trickyPreview, "action", false);

        // Quotes must be escaped as \"
        assertTrue(payload.contains("Steve \\\"The Hacker\\\" \\\\"));

        // Newline, tab, carriage return escaped
        assertTrue(payload.contains("Line 1\\nLine 2\\rLine 3\\tTabbed"));

        // Backticks inside values must be turned into single quotes so Discord markdown code blocks do not break
        assertTrue(payload.contains("'backticked'"));

        // Backslashes must be escaped as \\
        assertTrue(payload.contains("rule\\\\test"));
    }

    @Test
    void escapeJsonHandlesNullSafely() {
        assertEquals("", DiscordWebhookNotifier.escapeJson(null));
    }
}
