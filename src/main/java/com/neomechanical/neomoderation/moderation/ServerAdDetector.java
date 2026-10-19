package com.neomechanical.neomoderation.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a message that advertises another Minecraft server.
 *
 * <p>Narrower than {@code blockAnyUrl} on purpose, so it runs on a new install
 * without blocking ordinary links (a YouTube video, a wiki page, a Discord
 * invite). It flags only text that reads as "come and play somewhere else": a
 * public IPv4 address, any address with a port, a host whose first label is a
 * server-style prefix ({@code play.}, {@code mc.}, {@code smp.} ...), a
 * subdomain of a free Minecraft host, or a host written straight after "join",
 * "ip:", "server is" or "play on". "play" alone ("play coolsmp.net", "come play
 * hypixel.net") counts only before a host whose name reads as a game server,
 * because players invite each other to browser games ("lets play skribbl.io").
 * A private address such as {@code 192.168.1.1} is not reachable from outside,
 * so it counts only with a port, and a well-known public DNS resolver
 * ({@code 1.1.1.1}, {@code 8.8.8.8} ...) only with a port. An ordinary domain after "join" that
 * goes on to a page ({@code example.net/watch}) is a link, not a server; a path
 * never clears an address with a server signal, so {@code play.evil.net/} is
 * still an ad. A two-label host whose ending is also a word
 * ({@code spawn.it is fun}, a missing space after a full stop) counts only after
 * a word that can mean nothing but an invitation, such as "join" or "ip".</p>
 *
 * <p>Works on lower-cased text. Every {@code allowedUrls} entry is masked first,
 * so a server's own address can be shared.</p>
 */
final class ServerAdDetector {

    /** Hostname with an optional port; group 1 = host, 2 = TLD, 3 = port. */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "(?<![a-z0-9.\\-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.){1,8}([a-z]{2,24}))(?::([0-9]{2,5}))?(?![a-z0-9\\-])");

    /** IPv4 with an optional port; group 1-4 = octets, 5 = port. */
    private static final Pattern IP_PATTERN = Pattern.compile(
            "(?<![0-9.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::([0-9]{2,5}))?(?![0-9.])");

    /** "play . server . net": a dot with spaces on both sides, between letters/digits. */
    private static final Pattern SPACED_DOT_PATTERN = Pattern.compile("(?<=[a-z0-9]) +\\. +(?=[a-z0-9])");

    /** Words that, right before an address, can only mean an invitation to another server. */
    private static final String INVITATIONS =
            "join(?:\\s+(?:us|me|my\\s+server|our\\s+server))?|ip(?:\\s+address)?|server\\s+ip|address|connect(?:\\s+to)?";
    private static final Pattern INVITATION_BEFORE = intentBefore(INVITATIONS);

    /** Words that, right before an address, make it an invitation, though they also begin sentences. */
    private static final Pattern AD_INTENT_BEFORE = intentBefore(
            INVITATIONS + "|server|play\\s+(?:on|at)|hop\\s+on|come\\s+(?:to|on|play\\s+on)");

    /** "play" telling someone to play ("play ...", "come play ...", "lets play ..."), not "i play ...". */
    private static final Pattern PLAY_BEFORE = Pattern.compile(
            "(?:^|(?:^|[^a-z])(?:come|lets|let's|go|pls|please)\\s+)play\\s*(?::|-|>)?\\s*$");

    /** Parts of a host's name that mark a game server or network. */
    private static final Pattern SERVER_NAME = Pattern.compile(
            "smp|mc|craft|pvp|network|server|survival|skyblock|anarchy|factions|prison|hypixel|cubecraft|mineplex");

    /** First labels that mark a game-server address (play.example.net). */
    private static final Set<String> SERVER_LABELS = Set.of(
            "play", "mc", "join", "hub", "server", "srv", "smp", "pvp", "survival",
            "lobby", "connect", "skyblock", "anarchy", "factions");

    /** Free and budget Minecraft hosts and tunnels; any subdomain of these is a server address. */
    private static final Set<String> SERVER_HOST_DOMAINS = Set.of(
            "aternos.me", "minehut.gg", "exaroton.me", "falixsrv.me", "joinmc.link", "ply.gg", "playit.gg",
            "minefort.com", "server.pro", "e4mc.link");

    /** Public DNS resolvers, which players name when they talk about their connection. */
    private static final Set<String> DNS_RESOLVERS = Set.of(
            "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220");

    /** Cyrillic letters that look like Latin ones, as in a "рlay." written with a Cyrillic "р". */
    private static final String CYRILLIC_LOOKALIKES = "аеорсхуіј";
    private static final String LATIN_LOOKALIKES = "aeopcxyij";

    /**
     * Registrable domains that are never treated as server ads, whatever comes
     * before them. Discord invites are here on purpose: blocking them is a
     * separate community decision (bannedUrls, or blockAnyUrl).
     */
    private static final Set<String> NEVER_SERVER_AD_DOMAINS = Set.of(
            "google.com", "youtube.com", "youtu.be", "spotify.com", "apple.com", "twitch.tv",
            "discord.com", "discord.gg", "discordapp.com", "reddit.com", "github.com", "imgur.com",
            "wikipedia.org", "minecraft.net", "mojang.com", "microsoft.com", "xbox.com",
            "modrinth.com", "spigotmc.org", "papermc.io", "curseforge.com", "planetminecraft.com",
            "namemc.com", "twitter.com", "x.com", "tiktok.com", "instagram.com", "facebook.com",
            "fandom.com", "neomechanical.com", "kick.com", "buycraft.net", "tebex.io",
            // Browser games players invite each other to between rounds.
            "skribbl.io", "krunker.io", "agar.io", "slither.io", "diep.io", "gartic.io", "chess.com",
            "lichess.org", "geoguessr.com", "roblox.com", "poki.com", "crazygames.com", "itch.io");

    /** TLDs that server addresses actually use; keeps config.yml and server.properties clean. */
    private static final Set<String> COMMON_TLDS = Set.of(
            "com", "net", "org", "gg", "me", "io", "co", "xyz", "us", "uk", "eu", "de", "fr", "es",
            "it", "nl", "pl", "ru", "tr", "br", "ca", "au", "nz", "in", "pro", "club", "fun", "online",
            "world", "land", "host", "games", "network", "site", "live", "tv", "cc", "ws", "su", "be",
            "dk", "se", "no", "fi", "cz", "sk", "hu", "ro", "bg", "gr", "pt", "at", "ch", "ie", "lt",
            "lv", "ee", "ua", "kr", "jp", "cn", "tw", "vn", "id", "ph", "my", "sg", "th", "mx", "ar",
            "cl", "pe", "mc", "info", "biz", "one", "top", "dev", "app", "gs", "ly", "to", "space",
            "today", "rocks", "city", "zone", "icu", "shop", "store", "vip", "win", "ovh", "cloud", "tech");

    /**
     * Endings that are also everyday words, so "spawn.it is fun" (a missing space
     * after a full stop) reads as a sentence unless the host has a server signal.
     */
    private static final Set<String> WORD_TLDS = Set.of("it", "in", "to", "at", "be", "no", "my", "us");

    private static final int MAX_REPORTED_ADDRESS = 64;

    private ServerAdDetector() {
    }

    private static Pattern intentBefore(String words) {
        return Pattern.compile("(?:^|[^a-z])(?:" + words + ")"
                + "\\s*(?:is|at|on|to|:|=|-|>)?\\s*(?:is|at|on|to|:)?\\s*$");
    }

    /**
     * The advertised address if the lower-cased text advertises a game server,
     * checking the literal text and its de-obfuscated form.
     */
    static Optional<String> find(String lowerText, List<String> allowedUrls) {
        if (lowerText == null || lowerText.isEmpty()) {
            return Optional.empty();
        }
        String literal = mask(lowerText, allowedUrls);
        Optional<String> found = scan(literal);
        if (found.isPresent()) {
            return found;
        }
        String normalized = mask(deobfuscate(lowerText), allowedUrls);
        return normalized.equals(literal) ? Optional.empty() : scan(normalized);
    }

    /**
     * Spans {@code [start, end)} of literal server ads in {@code lowerText}, for
     * censoring. Disguised forms are not located here, so a caller must re-check
     * the censored text before letting it through.
     */
    static List<int[]> literalSpans(String lowerText) {
        List<int[]> spans = new ArrayList<>();
        if (lowerText == null || lowerText.indexOf('.') < 0) {
            return spans;
        }
        Matcher ip = IP_PATTERN.matcher(lowerText);
        while (ip.find()) {
            if (isAdvertisedIp(ip)) {
                spans.add(new int[]{ip.start(), ip.end()});
            }
        }
        Matcher host = HOST_PATTERN.matcher(lowerText);
        while (host.find()) {
            if (isServerAd(lowerText, host)) {
                spans.add(new int[]{host.start(), host.end()});
            }
        }
        return spans;
    }

    /**
     * Folds the usual disguises back to plain text: fullwidth dots, "dot", "(dot)",
     * "[.]", " . " and Cyrillic letters that look like Latin ones.
     */
    static String deobfuscate(String lowerText) {
        StringBuilder folded = new StringBuilder(lowerText.length());
        for (int i = 0; i < lowerText.length(); i++) {
            char c = lowerText.charAt(i);
            int lookalike = CYRILLIC_LOOKALIKES.indexOf(c);
            folded.append(lookalike >= 0 ? LATIN_LOOKALIKES.charAt(lookalike) : c);
        }
        String text = folded.toString()
                .replace('。', '.')
                .replace('．', '.')
                .replace('｡', '.')
                .replace('․', '.');
        text = MinecraftThreatDetector.normalizeDotObfuscation(text);
        if (text.contains(" . ")) {
            text = SPACED_DOT_PATTERN.matcher(text).replaceAll(".");
        }
        return text;
    }

    private static Optional<String> scan(String text) {
        if (text.indexOf('.') < 0) {
            return Optional.empty();
        }
        Matcher ip = IP_PATTERN.matcher(text);
        while (ip.find()) {
            if (isAdvertisedIp(ip)) {
                return Optional.of(report(ip.group()));
            }
        }
        Matcher host = HOST_PATTERN.matcher(text);
        while (host.find()) {
            if (isServerAd(text, host)) {
                return Optional.of(report(host.group()));
            }
        }
        return Optional.empty();
    }

    /** Whether an address goes on to a page, as in "example.net/watch"; a bare trailing "/" is no page. */
    private static boolean isWebPage(String text, int end) {
        return end + 1 < text.length() && text.charAt(end) == '/' && !Character.isWhitespace(text.charAt(end + 1));
    }

    /** Whether the text goes on as a sentence after the host: a space, then a word. */
    private static boolean sentenceContinues(String text, int end) {
        return end + 1 < text.length() && text.charAt(end) == ' ' && Character.isLetter(text.charAt(end + 1));
    }

    private static boolean isAdvertisedIp(Matcher ip) {
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(ip.group(i + 1));
            if (octets[i] > 255) {
                return false;
            }
        }
        if (ip.group(5) != null) {
            return true;
        }
        String address = octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
        return !isPrivate(octets) && !DNS_RESOLVERS.contains(address);
    }

    private static boolean isPrivate(int[] o) {
        return o[0] == 10 || o[0] == 127 || o[0] == 0
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 169 && o[1] == 254);
    }

    private static boolean isServerAd(String text, Matcher host) {
        String hostname = host.group(1);
        String tld = host.group(2);
        String port = host.group(3);
        String[] labels = hostname.split("\\.");
        String registrable = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (NEVER_SERVER_AD_DOMAINS.contains(registrable)) {
            return false;
        }
        for (String serverHost : SERVER_HOST_DOMAINS) {
            if (hostname.endsWith("." + serverHost)) {
                return true;
            }
        }
        if (!COMMON_TLDS.contains(tld)) {
            return false;
        }
        if (port != null || (labels.length >= 3 && SERVER_LABELS.contains(labels[0]))) {
            return true;
        }
        // Without a server signal, only an invitation makes an ordinary domain an ad.
        if (isWebPage(text, host.end())) {
            return false;
        }
        String before = text.substring(Math.max(0, host.start() - 32), host.start());
        if (labels.length == 2 && WORD_TLDS.contains(tld) && sentenceContinues(text, host.end())) {
            return INVITATION_BEFORE.matcher(before).find();
        }
        return AD_INTENT_BEFORE.matcher(before).find()
                || (SERVER_NAME.matcher(labels[labels.length - 2]).find()
                        && PLAY_BEFORE.matcher(text.substring(0, host.start())).find());
    }

    private static String mask(String text, List<String> allowedUrls) {
        String masked = text;
        for (String allowed : allowedUrls == null ? List.<String>of() : allowedUrls) {
            if (!allowed.isEmpty()) {
                masked = masked.replace(allowed, " ");
            }
        }
        return masked;
    }

    private static String report(String address) {
        return address.length() <= MAX_REPORTED_ADDRESS ? address : address.substring(0, MAX_REPORTED_ADDRESS);
    }
}
