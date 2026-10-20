package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineModerationEngineTest {
    @Test
    void largeBookWhitespaceAndRepeatedTokensHaveABoundedCost() {
        OfflineModerationSettings s = settings(false, List.of("fuck"), List.of("grabify.link"), List.of(), List.of());
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(1), () ->
                assertFalse(OfflineModerationEngine.evaluate(" ".repeat(32768) + "hello", s).flagged()));
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(1), () ->
                assertFalse(OfflineModerationEngine.evaluate("f ".repeat(32768) + "u cky", s).flagged()));
    }
    private static OfflineModerationSettings settings(
            boolean blockAnyUrl,
            List<String> bannedWords,
            List<String> bannedUrls,
            List<String> allowedWords,
            List<String> allowedUrls
    ) {
        return new OfflineModerationSettings(
                true,
                blockAnyUrl,
                true,
                bannedWords,
                bannedUrls,
                allowedWords,
                allowedUrls
        );
    }

    @Test
    void flagsBannedWordsCaseInsensitivelyWithoutMatchingInsideSafeWords() {
        OfflineModerationSettings s = settings(false, List.of("scam"), List.of(), List.of(), List.of());

        assertTrue(OfflineModerationEngine.evaluate("this is a SCAM", s).flagged());
        assertFalse(OfflineModerationEngine.evaluate("watch the scampi cook", s).flagged());
    }

    @Test
    void flagsLeetspeakWhenNormalizationIsEnabled() {
        OfflineModerationSettings s = settings(false, List.of("scam"), List.of(), List.of(), List.of());

        assertTrue(OfflineModerationEngine.evaluate("free s c a m", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("free $c4m", s).flagged());
    }

    @Test
    void flagsBannedWordsEndingInTerminalPunctuation() {
        OfflineModerationSettings s = settings(false, List.of("scam"), List.of(), List.of(), List.of());

        // Leetspeak maps '!' to 'i', which is right inside a word ("sc4m!ng") but
        // wrong at a boundary: "scam!" became "scami" and stopped matching.
        assertTrue(OfflineModerationEngine.evaluate("this is a scam!", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("scam!!!", s).flagged());
    }

    @Test
    void stillTreatsInnerExclamationMarksAsLeetspeakLetters() {
        OfflineModerationSettings s = settings(false, List.of("bitch"), List.of(), List.of(), List.of());

        assertTrue(OfflineModerationEngine.evaluate("you b!tch", s).flagged());
    }

    @Test
    void flagsConfiguredUrlFragmentsAndOptionalAnyUrlMode() {
        OfflineModerationSettings fragmentOnly =
                settings(false, List.of(), List.of("grabify.link", "discord.gg/free"), List.of(), List.of());
        OfflineModerationSettings anyUrl = settings(true, List.of(), List.of(), List.of(), List.of());

        assertTrue(OfflineModerationEngine.evaluate("go to https://grabify.link/a", fragmentOnly).flagged());
        assertTrue(OfflineModerationEngine.evaluate("discord.gg/free-nitro", fragmentOnly).flagged());
        assertFalse(OfflineModerationEngine.evaluate("example.com is fine here", fragmentOnly).flagged());
        assertTrue(OfflineModerationEngine.evaluate("example.com is blocked in any-url mode", anyUrl).flagged());
        assertTrue(OfflineModerationEngine.evaluate("join 1.2.3.4 now", anyUrl).flagged());
    }

    @Test
    void ignoresBlankConfiguredUrlFragments() {
        OfflineModerationSettings s =
                settings(false, List.of(), List.of("", "   ", "grabify.link"), List.of(), List.of());

        assertFalse(OfflineModerationEngine.evaluate("ordinary clean chat", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("visit grabify.link/demo", s).flagged());
    }

    @Test
    void allowedPhrasesSuppressBannedWordsInsideThem() {
        OfflineModerationSettings s =
                settings(false, List.of("scam"), List.of(), List.of("scam awareness"), List.of());

        assertFalse(OfflineModerationEngine.evaluate("join our scam awareness event", s).flagged());
        assertFalse(OfflineModerationEngine.evaluate("join our SCAM AWARENESS event", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("this is a scam", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("scam awareness is a scam", s).flagged());
    }

    @Test
    void allowedUrlsSuppressBannedUrlFragments() {
        OfflineModerationSettings s =
                settings(false, List.of(), List.of("discord.gg"), List.of(), List.of("discord.gg/myserver"));

        assertFalse(OfflineModerationEngine.evaluate("join discord.gg/myserver", s).flagged());
        assertFalse(OfflineModerationEngine.evaluate("join DISCORD.GG/myserver", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("join discord.gg/free", s).flagged());
    }

    @Test
    void censorMasksBannedWordsWhilePreservingTheRest() {
        OfflineModerationSettings s = settings(false, List.of("scam"), List.of(), List.of(), List.of());

        assertEquals("this is a ****", OfflineModerationEngine.censor("this is a SCAM", s));
        assertEquals("free **** now", OfflineModerationEngine.censor("free $c4m now", s));
        assertEquals("free * * * *", OfflineModerationEngine.censor("free s c a m", s));
        assertEquals("clean message", OfflineModerationEngine.censor("clean message", s));
    }

    @Test
    void censorAndEvaluateAgreeOnTerminalPunctuation() {
        OfflineModerationSettings s = settings(false, List.of("scam"), List.of(), List.of(), List.of());

        // evaluate() and censor() normalize through two separate code paths that
        // must stay in step; fixing one and not the other silently lets censor
        // pass through a word the detector already flagged.
        assertTrue(OfflineModerationEngine.evaluate("this is a scam!", s).flagged());
        assertEquals("this is a ****!", OfflineModerationEngine.censor("this is a scam!", s));
    }

    @Test
    void censorRespectsAllowedPhrases() {
        OfflineModerationSettings s =
                settings(false, List.of("scam"), List.of(), List.of("scam awareness"), List.of());

        assertEquals("scam awareness is a ****",
                OfflineModerationEngine.censor("scam awareness is a scam", s));
    }

    @Test
    void censorMasksUrls() {
        OfflineModerationSettings fragment =
                settings(false, List.of(), List.of("grabify.link"), List.of(), List.of());
        assertEquals("go to https://************/a",
                OfflineModerationEngine.censor("go to https://grabify.link/a", fragment));

        OfflineModerationSettings anyUrl =
                settings(true, List.of(), List.of(), List.of(), List.of("example.com"));
        assertEquals("see example.com not ********",
                OfflineModerationEngine.censor("see example.com not evil.net", anyUrl));
    }

    @Test
    void censorStarsTheLinkItselfAfterALetterThatLowerCasesToTwo() {
        OfflineModerationSettings s =
                settings(true, List.of(), List.of(), List.of(), List.of());

        // "İ".toLowerCase() is two characters; the stars must still land on the link.
        assertEquals("İyi sunucu ******** gel", OfflineModerationEngine.censor("İyi sunucu evil.net gel", s));
    }

    @Test
    void allowedUrlsSuppressBlockAnyUrlMode() {
        OfflineModerationSettings s =
                settings(true, List.of(), List.of(), List.of(), List.of("example.com"));

        assertFalse(OfflineModerationEngine.evaluate("example.com is fine here", s).flagged());
        assertFalse(OfflineModerationEngine.evaluate("see https://example.com/wiki", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("example.com and evil.net too", s).flagged());
        assertTrue(OfflineModerationEngine.evaluate("join 1.2.3.4 now", s).flagged());
    }
}
