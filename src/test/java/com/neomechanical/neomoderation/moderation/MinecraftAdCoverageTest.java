package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-declared test suite verifying Minecraft threat intelligence, server advertising,
 * scam links, and IP grabber coverage with 0% false positives on legitimate Minecraft chat.
 */
class MinecraftAdCoverageTest {

    /**
     * Realistic Minecraft clean chat corpus: coordinates, versions, decimals,
     * ratios, trade counts, abbreviations, ellipses, and standard player conversation.
     * Must produce 0 false positives under both blockAnyUrl=false and blockAnyUrl=true.
     */
    private static final List<String> CLEAN_CHAT_CORPUS = List.of(
            // Minecraft version strings
            "we are running 1.20.4 on the server now",
            "is anyone on 1.21.1 yet",
            "the server supports 1.8.9 through 1.20",
            "tested on 1.16.5 forge and fabric",
            "paper 1.12.2 compatibility build",
            // World coordinates and directions
            "meet me at 120 64 -340 near the portal",
            "x: 120, y: 64, z: -340",
            "x=1500, y=72, z=-800 is the outpost",
            "mob grinder at 1250, 68, -420",
            "-145.5, 70, 892.3 is the stronghold",
            "dig straight down to y -59 for diamonds",
            "nether portal coords: 250 118 -80",
            // Economy, math, decimals, ratios
            "selling 64 diamonds for 3.5k in auction",
            "iron to emerald ratio is 2.5:1 today",
            "pi is roughly 3.14159",
            "my kdr is 1.75 in pvp arena",
            "clutched the fight with 0.5 hearts left",
            "100.0% completion on advancements",
            "buying stacks of wood 5.0 coins each",
            // Ellipses and conversational punctuation
            "wait... what did you just do?",
            "hello... is anyone at spawn?",
            "no... way that actually worked!",
            "look... it's totally fine.",
            "cool... let's do the raid now",
            // Timestamps and abbreviations
            "event starts at 12:30 tomorrow",
            "restart scheduled for 5:00pm",
            "e.g. you can build a cobble gen",
            "i.e. that means no griefing allowed",
            "i am back. what did i miss.",
            // Scunthorpe and standard words
            "the assignment is due on friday",
            "walking on the grass in the park",
            "classic analysis of the redstone",
            "I live in Scunthorpe"
    );

    /**
     * Realistic Minecraft abuse corpus: direct server IPs, custom ports,
     * server domains, obfuscated IPs, obfuscated domains, Discord invites,
     * IP grabbers, and phishing/scam domains.
     */
    private static final List<String> ABUSE_CORPUS = List.of(
            // Direct server IPs with port
            "join my server at 192.168.1.1:25565 now",
            "new anarchy server: 45.33.32.156:25565",
            "connect to 10.0.0.1:25565 for free ranks",
            // Direct server IPs without port
            "connect to 123.45.67.89",
            "join 10.0.0.1 right now",
            // Domain server advertising
            "join play.hypixel.net for bedwars",
            "check out smp.coolcraft.xyz:25565",
            "join my smp at play.minekingdom.org",
            "connect to mc.survivalcraft.gg",
            // Discord invites (competitor recruitment)
            "join our community discord.gg/coolsmp",
            "recruiting staff at discord.com/invite/smpcraft",
            "discord.gg/xyz123 free op",
            // Known IP grabbers / loggers
            "check this picture grabify.link/a",
            "free cape link iplogger.org/2f5x",
            "view stats at 2no.co/abc",
            "click yip.su/test",
            "free items at blasze.com/log",
            // Phishing & scam domains
            "claim free nitro: discord-nitro.gift",
            "free nitro giveaway discordnitro.info",
            "vote for rewards hypixel-giveaway.xyz",
            "claim minecon cape at minecon-cape.org",
            "free steam gift card steamcommunity.link",
            // Obfuscated IP advertising
            "join 192 dot 168 dot 1 dot 1 now",
            "join 192(dot)168(dot)1(dot)1:25565",
            "join 192[dot]168[dot]1[dot]1",
            // Obfuscated domain advertising
            "play dot coolsmp dot net",
            "play (dot) coolsmp (dot) net",
            "play [dot] coolsmp [dot] net"
    );

    private static List<String> shippedBannedUrls() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/config.yml"));
        List<String> urls = new ArrayList<>();
        boolean inList = false;
        for (String line : lines) {
            if (line.strip().equals("bannedUrls:")) {
                inList = true;
                continue;
            }
            if (!inList) {
                continue;
            }
            String stripped = line.strip();
            if (!stripped.startsWith("- ")) {
                break;
            }
            urls.add(stripped.substring(2).trim().replaceAll("^\"|\"$", ""));
        }
        return urls;
    }

    private static OfflineModerationSettings settings(boolean blockAnyUrl, List<String> bannedUrls, List<String> allowedUrls) {
        return new OfflineModerationSettings(
                true,
                blockAnyUrl,
                true,
                List.of("badword"),
                bannedUrls,
                List.of(),
                allowedUrls
        );
    }

    @Test
    void cleanMinecraftChatCorpusHasZeroFalsePositivesUnderBothModes() {
        OfflineModerationSettings defaultMode = settings(false, List.of("grabify.link", "discord.gg/free"), List.of());
        OfflineModerationSettings anyUrlMode = settings(true, List.of(), List.of());
        OfflineModerationSettings serverAdsMode = new OfflineModerationSettings(true, false, true,
                List.of("badword"), List.of(), List.of(), List.of(), List.of(), List.of(), true);

        for (String clean : CLEAN_CHAT_CORPUS) {
            assertFalse(OfflineModerationEngine.evaluate(clean, defaultMode).flagged(),
                    () -> "Clean chat flagged under default mode: " + clean);
            assertFalse(OfflineModerationEngine.evaluate(clean, serverAdsMode).flagged(),
                    () -> "Clean chat flagged as a server ad: " + clean);
            assertFalse(OfflineModerationEngine.evaluate(clean, anyUrlMode).flagged(),
                    () -> "Clean chat flagged under blockAnyUrl mode: " + clean);
        }
    }

    @Test
    void abuseCorpusIs100PercentFlaggedUnderAnyUrlMode() {
        OfflineModerationSettings anyUrlMode = settings(true, List.of(), List.of());

        for (String abuse : ABUSE_CORPUS) {
            assertTrue(OfflineModerationEngine.evaluate(abuse, anyUrlMode).flagged(),
                    () -> "Abuse not caught under blockAnyUrl mode: " + abuse);
        }
    }

    @Test
    void shippedDefaultThreatSignaturesCatchScamsEvenWithoutBlockAnyUrl() throws IOException {
        List<String> shippedUrls = shippedBannedUrls();
        assertTrue(shippedUrls.size() >= 8, "Expected shipped bannedUrls to include known threat signatures");

        OfflineModerationSettings defaultShipped = settings(false, shippedUrls, List.of());

        List<String> scamSamples = List.of(
                "click grabify.link/secret",
                "click grabify(dot)link/secret",
                "click grabify [dot] link/secret",
                "click grabify[.]link/secret",
                "see iplogger.org/xyz",
                "see iplogger(.)org/xyz",
                "stats at 2no.co/profile",
                "join discord.gg/free-nitro",
                "claim at discord-nitro.gift",
                "login to discordnitro.info for rank",
                "claim gift at discord.gift",
                "free cape at optifine-cape(dot)com"
        );

        for (String scam : scamSamples) {
            assertTrue(OfflineModerationEngine.evaluate(scam, defaultShipped).flagged(),
                    () -> "Shipped default rules must flag scam: " + scam);
        }
    }

    @Test
    void allowedUrlsExemptWhitelistedCommunitiesEvenUnderBlockAnyUrlMode() {
        OfflineModerationSettings allowedSettings = settings(
                true,
                List.of(),
                List.of("discord.gg/myserver", "store.myserver.net", "vote.planetminecraft.com")
        );

        assertFalse(OfflineModerationEngine.evaluate("join our official discord: discord.gg/myserver", allowedSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("buy ranks at store.myserver.net", allowedSettings).flagged());
        assertFalse(OfflineModerationEngine.evaluate("vote for rewards at vote.planetminecraft.com", allowedSettings).flagged());

        // But external ads in the same session remain blocked
        assertTrue(OfflineModerationEngine.evaluate("discord.gg/myserver and join play.rivalsmp.net", allowedSettings).flagged());
        assertTrue(OfflineModerationEngine.evaluate("discord.gg/myserver and check grabify.link/a", allowedSettings).flagged());
    }

    @Test
    void censoringMasksThreatMatchesWithoutCorruptingSurroundingText() {
        OfflineModerationSettings anyUrl = settings(true, List.of(), List.of("discord.gg/myserver"));

        assertEquals("join ***************** now",
                OfflineModerationEngine.censor("join 192.168.1.1:25565 now", anyUrl));
        assertEquals("see discord.gg/myserver not ************",
                OfflineModerationEngine.censor("see discord.gg/myserver not grabify.link", anyUrl));
        assertEquals("wait... what did you just do?",
                OfflineModerationEngine.censor("wait... what did you just do?", anyUrl));
    }
}
