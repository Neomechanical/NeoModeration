package com.neomechanical.neomoderation.messages;

import com.neomechanical.neomoderation.moderation.BundledWordPacks;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the reason a detector gives into words for staff and players.
 *
 * <p>A reason is {@code kind:detail} as the detectors write it:
 * {@code blocked_word:fuck}, {@code blocked_url:grabify.link},
 * {@code server_ad:play.example.net}, {@code spam:caps},
 * {@code platform:harassment}, {@code map_art:sexual}. {@code split_} before
 * the kind marks a word assembled from several short messages, and
 * {@code spaced_} one read across a space in a single message ("f uck").</p>
 */
public final class Reasons {

    /** What kind of rule a reason names. */
    public enum Kind {
        WORD("word"),
        LINK("link"),
        ANY_LINK("any-link"),
        SERVER_AD("server-ad"),
        SPAM_RATE("spam-rate"),
        SPAM_DUPLICATE("spam-duplicate"),
        SPAM_CAPS("spam-caps"),
        SPAM_FLOOD("spam-flood"),
        SPAM_COMMAND_RATE("spam-command-rate"),
        CLOUD("cloud"),
        MAP("map"),
        OTHER("other");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /** The locale key suffix, as in {@code reason.server-ad}. */
        public String key() {
            return key;
        }
    }

    /** A reason taken apart: {@code split} across messages, {@code spaced} across a space in one. */
    public record Parsed(Kind kind, boolean split, boolean spaced, String detail) {
    }

    private Reasons() {
    }

    public static Parsed parse(String reason) {
        String original = reason == null ? "" : reason.trim();
        String raw = original;
        boolean split = raw.startsWith("split_");
        if (split) {
            raw = raw.substring("split_".length());
        }
        boolean spaced = raw.startsWith("spaced_");
        if (spaced) {
            raw = raw.substring("spaced_".length());
        }
        int colon = raw.indexOf(':');
        String kind = (colon >= 0 ? raw.substring(0, colon) : raw).toLowerCase(Locale.ROOT);
        String detail = colon >= 0 ? raw.substring(colon + 1) : "";
        Kind parsed = switch (kind) {
            case "blocked_word" -> Kind.WORD;
            case "blocked_url" -> "any".equals(detail) ? Kind.ANY_LINK : Kind.LINK;
            case "server_ad" -> Kind.SERVER_AD;
            case "spam" -> switch (detail) {
                case "rate" -> Kind.SPAM_RATE;
                case "duplicate" -> Kind.SPAM_DUPLICATE;
                case "caps" -> Kind.SPAM_CAPS;
                case "flood" -> Kind.SPAM_FLOOD;
                case "command_rate" -> Kind.SPAM_COMMAND_RATE;
                default -> Kind.OTHER;
            };
            case "platform" -> Kind.CLOUD;
            case "map_art" -> Kind.MAP;
            default -> Kind.OTHER;
        };
        return new Parsed(parsed, split, spaced, parsed == Kind.OTHER ? original : detail);
    }

    /** The reason in words, in the language {@code reader} reads: "banned word "fuck"". */
    public static String describe(MessageService messages, CommandSender reader, String reason) {
        Parsed parsed = parse(reason);
        String detail = parsed.detail();
        if (parsed.kind() == Kind.CLOUD || parsed.kind() == Kind.MAP) {
            detail = detail.isBlank() ? "" : category(messages, reader, detail);
        }
        boolean unlabelled = detail.isBlank() && (parsed.kind() == Kind.CLOUD || parsed.kind() == Kind.MAP);
        // A word read across a gap says so, so a staff member can see why "put a 5" was blocked.
        String key = "reason." + parsed.kind().key()
                + (parsed.split() && parsed.kind() == Kind.WORD ? "-split" : "")
                + (parsed.spaced() && !parsed.split() && parsed.kind() == Kind.WORD ? "-spaced" : "")
                + (unlabelled ? "-plain" : "");
        return messages.format(reader, key, Map.of("detail", detail));
    }

    /**
     * A line saying that the word a reason names is an everyday word in other
     * languages, and when it passes there, in the language {@code reader} reads:
     * "fag" is an everyday word in Danish and Norwegian. {@code trusted} when the
     * server trusts players' game languages; {@code written} is what the player
     * wrote, when known (see {@link BundledWordPacks#languagesUsing(String, String)}).
     * Empty for any other reason, and for a word read across a space or across
     * messages, which the setting would not let through.
     */
    public static Optional<String> languageWordHint(MessageService messages, CommandSender reader, String reason,
                                                    String written, boolean trusted) {
        Parsed parsed = parse(reason);
        List<String> codes = parsed.kind() == Kind.WORD && !parsed.split() && !parsed.spaced()
                ? BundledWordPacks.languagesUsing(parsed.detail(), written) : List.of();
        if (codes.isEmpty()) {
            return Optional.empty();
        }
        Locale readerLocale = Locale.forLanguageTag(messages.localeCodeOf(reader).replace('_', '-'));
        List<String> names = codes.stream().map(code -> languageName(code, readerLocale)).distinct().toList();
        return Optional.of(messages.format(reader, trusted ? "reason.language-word-trusted" : "reason.language-word",
                Map.of("detail", BundledWordPacks.nativeSpelling(parsed.detail(), written),
                        "languages", list(messages, reader, names))));
    }

    /**
     * "Danish", "Danish and Norwegian", "Bosnian, Croatian and Serbian", with the
     * reader's separators: French repeats its preposition ("danois et en norvégien").
     */
    private static String list(MessageService messages, CommandSender reader, List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(messages.text(reader, "label.list-separator"), names.subList(0, names.size() - 1))
                + messages.text(reader, "label.and") + names.get(names.size() - 1);
    }

    /**
     * A language's name in the reader's language: "da" is "Danish", Bokmål and
     * Nynorsk are Norwegian, and a country's form names the country:
     * "Portuguese (Portugal)".
     */
    private static String languageName(String code, Locale reader) {
        Locale language = Locale.forLanguageTag(code.replace('_', '-'));
        if (language.getLanguage().equals("nb") || language.getLanguage().equals("nn")) {
            language = Locale.forLanguageTag("no");
        }
        return language.getCountry().isEmpty() ? language.getDisplayLanguage(reader) : language.getDisplayName(reader);
    }

    /** A cloud category in words: "self-harm". */
    public static String category(MessageService messages, CommandSender reader, String category) {
        String key = "category." + category;
        String text = messages.format(reader, key, Map.of());
        return text.equals(key) ? category : text;
    }

    /** The locale key that tells a player why their text was stopped. */
    public static String whyKey(String reason) {
        Kind kind = parse(reason).kind();
        return switch (kind) {
            case ANY_LINK -> "why.link";
            case OTHER, MAP -> "why.cloud";
            default -> "why." + kind.key();
        };
    }

    /** The statistics bucket of a reason, as /nmod mode lists it. */
    /** A map held because the cloud could not check it (mapArt.failClosed): nobody judged it. */
    public static boolean unchecked(String reason) {
        return reason != null && reason.endsWith(":unchecked");
    }

    public static String bucket(String reason) {
        Parsed parsed = parse(reason);
        return switch (parsed.kind()) {
            case WORD -> parsed.split() ? "split-word" : "word";
            case LINK, ANY_LINK -> "link";
            case SERVER_AD -> "server-ad";
            case SPAM_RATE, SPAM_DUPLICATE, SPAM_CAPS, SPAM_FLOOD, SPAM_COMMAND_RATE -> "spam";
            case CLOUD -> "cloud";
            case MAP -> "map";
            case OTHER -> "other";
        };
    }
}
