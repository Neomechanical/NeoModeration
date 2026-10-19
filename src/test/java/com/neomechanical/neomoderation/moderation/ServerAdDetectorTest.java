package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server advertising is on for every install, so it must stay quiet on ordinary
 * chat and links while catching "come and play somewhere else".
 */
class ServerAdDetectorTest {

    private static OfflineModerationSettings adsOn(List<String> allowedUrls) {
        return new OfflineModerationSettings(true, false, true, List.of("badword"), List.of(), List.of(),
                allowedUrls, List.of(), List.of(), true);
    }

    private static Optional<String> advertised(String message) {
        OfflineModerationResult result = OfflineModerationEngine.evaluate(message, adsOn(List.of()));
        return result.flagged() ? Optional.of(result.reason()) : Optional.empty();
    }

    @Test
    void catchesAddressesThatInviteToAnotherServer() {
        for (String ad : List.of(
                "join play.coolsmp.net for free ranks",
                "new server 45.33.32.156",
                "45.33.32.156:25565 come",
                "check out smp.coolcraft.xyz:25565",
                "connect to mc.survivalcraft.gg",
                "free op on mysmp.aternos.me",
                "join coolsmp.net now",
                "the ip: coolsmp.net",
                "our server is coolsmp.net",
                "come play on coolsmp.net",
                "join 192.168.1.1:25565",
                "play . coolsmp . net",
                "play dot coolsmp dot net",
                "play(dot)coolsmp(dot)net",
                "play。coolsmp。net",
                "\u0440lay.coolsmp.net is up",
                "cool.minefort.com",
                "x.e4mc.link",
                "abc.playit.gg",
                "my server is cool.server.pro",
                "join play.evil.net/",
                "play.evil.net/ is the best",
                "mc.hypixel.net/",
                "join mc.evil.net/discord",
                "45.67.89.10:25565/",
                "45.67.89.10/",
                "play on hypixel.net now",
                "play hypixel.net now",
                "join kick.gg:25565",
                "connect to play.roblox.net",
                "play.youtube.fun",
                "come play coolsmp.net",
                "lets play coolsmp.net tonight",
                "play coolsmp.co.uk",
                "come and play coolsmp.net",
                "let’s play coolsmp.net",
                "new server! play coolsmp.net",
                "play 2b2t.org",
                "entra a coolsmp.net",
                "joga no coolsmp.com.br",
                "rejoignez coolsmp.fr",
                "kommt auf coolsmp.de",
                "join coolsmp.us now",
                "join epicmc.it guys",
                "ip is coolsmp.at for real",
                "join coolsmp.to now",
                "server ip coolsmp.be today",
                "entre en coolsmp.net",
                "entre a coolsmp.net",
                "join craftingtable.gg",
                "ip: topg.gg",
                "join kick.gg:25565",
                "join minecraftservers.de",
                "join youtube.de:25565")) {
            assertTrue(advertised(ad).orElse("").startsWith("server_ad:"), "missed a server ad: " + ad);
        }
    }

    @Test
    void leavesOrdinaryChatAndLinksAlone() {
        for (String clean : List.of(
                "watch this youtube.com/watch?v=abc",
                "the recipe is on minecraft.wiki",
                "go to minecraft.net for the launcher",
                "hypixel.net is laggy today",
                "our discord is discord.gg/abc",
                "my router is 192.168.1.1",
                "we are running 1.20.4 now",
                "paper 1.21.11 build 40",
                "meet me at 120 64 -340",
                "edit config.yml and plugin.yml",
                "check server.properties",
                "e.g. a cobble gen, i.e. a farm",
                "wait... what? ok.so then",
                "gg.gg lol",
                "my kdr is 1.75",
                "my ping to 1.1.1.1 is bad",
                "use 8.8.8.8 as dns",
                "join kick.com/streamer",
                "join twitch.tv/streamer to watch",
                "server: buycraft.net",
                "join example.net/watch?v=abc for the video",
                "come to spawn.it is fun",
                "lets play skribbl.io",
                "wanna play krunker.io later",
                "play agar.io with me",
                "we should play chess.com tonight",
                "play geoguessr.com its fun",
                "play roblox.com instead",
                "come play on skribbl.io",
                "i play hypixel.net mostly",
                "go play mcdonalds.com",
                "entra no google.com.br e pesquisa",
                "entra no globo.com",
                "entra a marca.com",
                "das kommt auf amazon.de an",
                "estoy entre hypixel.net y mineplex.com, cuál es mejor?",
                "kommt auf minecraft-serverlist.net zum voten",
                "viens sur serveur-minecraft.com pour voter",
                "entra en minecraft-server.eu para votar",
                "the recipe is on craftingtable.gg",
                "mira google.com.mx",
                "we play minecraft.net on weekends",
                "привет. как дела")) {
            assertEquals(Optional.empty(), advertised(clean), "flagged ordinary chat: " + clean);
        }
    }

    @Test
    void anAllowedAddressCanBeSharedButAnotherServerCannot() {
        OfflineModerationSettings own = adsOn(List.of("play.myserver.net"));

        assertFalse(OfflineModerationEngine.evaluate("join play.myserver.net with friends", own).flagged());
        assertTrue(OfflineModerationEngine.evaluate("join play.rivalsmp.net instead", own).flagged());
    }

    @Test
    void censoringStarsALiteralAddressButBlocksADisguisedOne() {
        OfflineModerationSettings settings = adsOn(List.of());

        assertEquals(Optional.of("join " + "*".repeat("play.coolsmp.net".length()) + " now"),
                OfflineModerationEngine.censorCompletely("join play.coolsmp.net now", settings));
        assertEquals(Optional.empty(),
                OfflineModerationEngine.censorCompletely("join play dot coolsmp dot net", settings),
                "a disguised address cannot be starred out, so the message must be blocked");
    }

    @Test
    void theCheckIsOffWhenTheServerTurnsItOff() {
        OfflineModerationSettings off = new OfflineModerationSettings(true, false, true, List.of("badword"),
                List.of(), List.of(), List.of(), List.of(), List.of(), false);

        assertFalse(OfflineModerationEngine.evaluate("join play.coolsmp.net", off).flagged());
    }
}
