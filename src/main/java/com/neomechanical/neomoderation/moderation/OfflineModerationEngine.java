package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import com.neomechanical.neomoderation.moderation.OfflineRuleCompiler.CompiledRules;
import com.neomechanical.neomoderation.moderation.OfflineRuleCompiler.CompiledUrl;
import com.neomechanical.neomoderation.moderation.OfflineRuleCompiler.CompiledWord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local word/URL filtering and censoring for the hot chat path, backed by
 * {@link OfflineRuleCompiler} and {@link TextNormalizer}.
 *
 * <p>{@code speaker} is the game language of whoever wrote the text, such as
 * {@code fr_fr}, or {@code null}. Everyday words of the speaker's language are
 * never read as one banned word ({@link EverydayWords}), and words that language
 * uses every day although another language's list bans them pass for that
 * speaker when the message reads as that language. A word matched across a
 * space is reported as {@code spaced_blocked_word:<word>}.</p>
 */
public final class OfflineModerationEngine {

    private OfflineModerationEngine() {
    }

    public static OfflineModerationResult evaluate(String message, OfflineModerationSettings settings) {
        return evaluate(message, settings, null);
    }

    public static OfflineModerationResult evaluate(String message, OfflineModerationSettings settings,
                                                   String speaker) {
        if (!settings.enabled()) {
            return OfflineModerationResult.clear();
        }

        CompiledRules rules = OfflineRuleCompiler.rulesFor(settings);

        String lowerMessage = OfflineRuleCompiler.lower(message);
        for (String allowedUrl : rules.allowedUrls()) {
            lowerMessage = lowerMessage.replace(allowedUrl, " ");
        }
        String deobfuscatedUrlMessage = MinecraftThreatDetector.normalizeDotObfuscation(lowerMessage);
        if (!deobfuscatedUrlMessage.equals(lowerMessage)) {
            for (String allowedUrl : rules.allowedUrls()) {
                deobfuscatedUrlMessage = deobfuscatedUrlMessage.replace(allowedUrl, " ");
            }
        }
        for (CompiledUrl url : rules.urls()) {
            if (lowerMessage.contains(url.normalized())
                    || (!deobfuscatedUrlMessage.equals(lowerMessage)
                            && deobfuscatedUrlMessage.contains(url.normalized()))) {
                return OfflineModerationResult.flagged("blocked_url:" + url.original());
            }
        }

        if (settings.blockAnyUrl() && MinecraftThreatDetector.matchesUrlOrIp(lowerMessage)) {
            return OfflineModerationResult.flagged("blocked_url:any");
        }
        if (settings.blockServerAds()) {
            Optional<String> advertised = ServerAdDetector.find(OfflineRuleCompiler.lower(message), rules.allowedUrls());
            if (advertised.isPresent()) {
                return OfflineModerationResult.flagged("server_ad:" + advertised.get());
            }
        }

        WordScan scan = WordScan.of(rules, settings, message, speaker);
        if (scan == null) {
            return OfflineModerationResult.clear();
        }
        for (CompiledWord word : rules.words()) {
            if (!scan.mayHold(word)) {
                continue;
            }
            int[] match = scan.find(word, 0);
            if (match != null) {
                String spaced = OfflineRuleCompiler.crossesSpace(scan.text(), match[0], match[1]) ? "spaced_" : "";
                return OfflineModerationResult.flagged(spaced + "blocked_word:" + word.original());
            }
        }

        return OfflineModerationResult.clear();
    }

    /**
     * A message made ready for the banned words: normalised, with allowed phrases masked and the everyday words of
     * its languages known. Checking a message and censoring it share it, so both read every word the same way.
     */
    private record WordScan(TextNormalizer.Normalized text, boolean[] masked, EverydayWords everyday) {

        /** Null when there is nothing to scan: no banned words, or no letters. */
        static WordScan of(CompiledRules rules, OfflineModerationSettings settings, String message, String speaker) {
            if (rules.words().isEmpty()) {
                return null;
            }
            TextNormalizer.Normalized text = TextNormalizer.normalize(message, settings.normalizeLeetspeak());
            if (text.length() == 0) {
                return null;
            }
            String language = settings.wordLanguage(speaker);
            return new WordScan(text, OfflineRuleCompiler.maskAllowedPhrases(text, allowedFor(rules, text, language)),
                    new EverydayWords(text, rules.everydayScope(language), speakerWords(language)));
        }

        /** False when the word cannot be in the text at all: the cheap test before the search. */
        boolean mayHold(CompiledWord word) {
            return text.collapsed().contains(word.collapsed());
        }

        /** Where {@code word} matches from {@code from} on, or null. */
        int[] find(CompiledWord word, int from) {
            return OfflineRuleCompiler.findWord(text, word, masked, everyday, from);
        }
    }

    /** The everyday words of a trusted speaker's {@code language}, or {@code null} for none. */
    private static Set<String> speakerWords(String language) {
        return language == null ? null : BundledWordPacks.everydayWords(language);
    }

    /**
     * Everyone's allowed phrases, plus the words the speaker's language may use
     * when the message reads as that language: "quel retard ce train" for a French
     * speaker, but not "shut up retard", whatever the speaker's game language.
     */
    private static List<String[]> allowedFor(CompiledRules rules, TextNormalizer.Normalized text, String language) {
        List<String[]> spoken = rules.speakerPhrases(language);
        if (spoken.isEmpty() || !EverydayWords.writtenIn(text, BundledWordPacks.everydayWords(language),
                rules.speakerWords(language))) {
            return rules.allowedPhrases();
        }
        List<String[]> merged = new ArrayList<>(rules.allowedPhrases().size() + spoken.size());
        merged.addAll(rules.allowedPhrases());
        merged.addAll(spoken);
        return merged;
    }

    /**
     * Returns the message with every banned-word/URL match replaced by {@code *}
     * while allowed phrases/URLs and the safe remainder stay intact.
     */
    public static String censor(String message, OfflineModerationSettings settings) {
        return censor(message, settings, null);
    }

    public static String censor(String message, OfflineModerationSettings settings, String speaker) {
        if (!settings.enabled() || message.isEmpty()) {
            return message;
        }
        CompiledRules rules = OfflineRuleCompiler.rulesFor(settings);
        char[] raw = message.toCharArray();

        StringBuilder lowerMirror = new StringBuilder(OfflineRuleCompiler.lower(message));
        for (String allowedUrl : rules.allowedUrls()) {
            maskOccurrences(lowerMirror, allowedUrl);
        }
        for (CompiledUrl url : rules.urls()) {
            int from = 0;
            int idx;
            while ((idx = lowerMirror.indexOf(url.normalized(), from)) >= 0) {
                star(raw, idx, idx + url.normalized().length());
                blank(lowerMirror, idx, idx + url.normalized().length());
                from = idx + url.normalized().length();
            }
        }
        if (settings.blockAnyUrl()) {
            starRegexMatches(raw, lowerMirror.toString(), MinecraftThreatDetector.URL_PATTERN);
            starRegexMatches(raw, lowerMirror.toString(), MinecraftThreatDetector.IPV4_PATTERN);
        }
        if (settings.blockServerAds()) {
            for (int[] span : ServerAdDetector.literalSpans(lowerMirror.toString())) {
                star(raw, span[0], span[1]);
            }
        }

        WordScan scan = WordScan.of(rules, settings, new String(raw), speaker);
        if (scan != null) {
            int[] rawIndex = scan.text().rawIndex();
            for (CompiledWord word : rules.words()) {
                if (!scan.mayHold(word)) {
                    continue;
                }
                int from = 0;
                int[] match;
                while ((match = scan.find(word, from)) != null) {
                    star(raw, rawIndex[match[0]], rawIndex[match[1] - 1] + 1);
                    from = match[1];
                }
            }
        }
        return new String(raw);
    }

    /**
     * The censored message, but only when censoring leaves nothing a rule still
     * flags. A disguised link or address cannot be located to star out, so a
     * caller that gets empty must block the message instead.
     */
    public static Optional<String> censorCompletely(String message, OfflineModerationSettings settings) {
        return censorCompletely(message, settings, null);
    }

    public static Optional<String> censorCompletely(String message, OfflineModerationSettings settings,
                                                    String speaker) {
        String censored = censor(message, settings, speaker);
        if (censored.equals(message) || evaluate(censored, settings, speaker).flagged()) {
            return Optional.empty();
        }
        return Optional.of(censored);
    }

    private static void star(char[] raw, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(raw.length, end); i++) {
            if (!Character.isWhitespace(raw[i])) {
                raw[i] = '*';
            }
        }
    }

    private static void blank(StringBuilder text, int start, int end) {
        for (int i = start; i < end && i < text.length(); i++) {
            text.setCharAt(i, ' ');
        }
    }

    private static void maskOccurrences(StringBuilder lowerText, String needle) {
        int from = 0;
        int idx;
        while ((idx = lowerText.indexOf(needle, from)) >= 0) {
            blank(lowerText, idx, idx + needle.length());
            from = idx + needle.length();
        }
    }

    private static void starRegexMatches(char[] raw, String haystack, Pattern pattern) {
        var matcher = pattern.matcher(haystack);
        while (matcher.find()) {
            star(raw, matcher.start(), matcher.end());
        }
    }
}
