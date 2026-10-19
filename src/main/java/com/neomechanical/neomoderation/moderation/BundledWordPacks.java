package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.messages.MessageService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Curated starter banned words for bundled locales.
 *
 * <p>Provides immediate out-of-the-box local profanity and slur protection for
 * servers operating in non-English locales ({@code es_ES}, {@code fr_FR},
 * {@code de_DE}, {@code pt_BR}, {@code tr_TR}) as well as {@code en_US}.
 * Every starter word is lowercase, normalized, and at least 4 characters long
 * to prevent false positives on common short words or game terms.</p>
 */
public final class BundledWordPacks {

    private static final List<String> EN_WORDS = List.of(
            "fuck", "fucks", "fucked", "fucking", "fucker", "fuckers",
            "motherfucker", "motherfuckers",
            "bitch", "bitches",
            "cunt", "cunts",
            "asshole", "assholes",
            "bastard", "bastards",
            "dickhead", "dickheads",
            "whore", "whores",
            "slut", "sluts",
            "nigger", "niggers",
            "nigga", "niggas",
            "faggot", "faggots",
            "retard", "retarded"
    );

    private static final List<String> ES_WORDS = List.of(
            "mierda", "mierdas",
            "puta", "putas", "puto", "putos", "putero",
            "cabron", "cabrones",
            "cojon", "cojones",
            "pendejo", "pendejos", "pendejada",
            "maricon", "maricones", "marica",
            "culiao", "culiaos",
            "gilipollas",
            "hijodeputa", "hijosdeputa", "hijadeputa",
            "malparido", "malparidos",
            "chinga", "chingada", "chingar"
    );

    private static final List<String> FR_WORDS = List.of(
            "merde", "merdes", "merdeux",
            "putain", "putains",
            "salope", "salopes", "salop",
            "connard", "connards", "connasse", "connasses",
            "encule", "encules", "enculeur",
            "batard", "batards",
            "pouffiasse", "pouffiasses",
            "foutre", "bordel",
            "tarlouze", "tarlouzes",
            "tapette", "tapettes",
            "branleur", "branleurs"
    );

    private static final List<String> DE_WORDS = List.of(
            "arschloch", "arschlocher",
            "hurensohn", "hurensohne",
            "fotze", "fotzen",
            "schlampe", "schlampen",
            "wichser", "wichsers",
            "scheisse", "scheisser",
            "bastard", "bastarde",
            "spast", "spasti", "spastiker",
            "missgeburt", "missgeburten",
            "hure", "huren",
            "drecksack", "kacke", "kackbratze",
            "vollidiot", "vollidioten"
    );

    private static final List<String> PT_WORDS = List.of(
            "merda", "merdas",
            "puta", "putas", "puto", "putos",
            "caralho", "caralhos",
            "foder", "fodase", "fudido", "fudida",
            "filhadaputa", "filhodaputa", "filhasdaputa", "filhosdaputa",
            "viado", "viados",
            "bicha", "bichas",
            "arrombado", "arrombados", "arrombada", "arrombadas",
            "babaca", "babacas",
            "desgracado", "desgracados"
    );

    private static final List<String> TR_WORDS = List.of(
            "orospu", "orospular", "orospucocugu",
            "sikis", "sikmek", "sikerim", "sikik", "sikeyim",
            "yarak", "yarrak", "yarraklar",
            "gotveren", "amcik", "amcuk",
            "amina", "aminakoyayim", "aminakoyim",
            "ibne", "ibneler",
            "pezevenk", "pezevenkler",
            "kahpe", "kahpeler",
            "gotlek", "dalyarak", "picler"
    );

    private BundledWordPacks() {
    }

    /**
     * Returns an unmodifiable list of curated starter banned words for the given
     * locale code. Resolves short codes, country variants, and hyphens via
     * {@link MessageService#resolveLocaleCode(String)}.
     *
     * @param localeCode locale code such as {@code "en_US"}, {@code "es"}, {@code "pt-br"}
     * @return unmodifiable list of starter banned words, or empty list for unknown locales
     */
    public static List<String> starterWordsForLocale(String localeCode) {
        if (localeCode == null || localeCode.isBlank()) {
            return EN_WORDS;
        }
        String resolved = MessageService.resolveLocaleCode(localeCode);
        return switch (resolved) {
            case "en_US" -> EN_WORDS;
            case "es_ES" -> ES_WORDS;
            case "fr_FR" -> FR_WORDS;
            case "de_DE" -> DE_WORDS;
            case "pt_BR" -> PT_WORDS;
            case "tr_TR" -> TR_WORDS;
            default -> {
                String lower = resolved.trim().toLowerCase(Locale.ROOT).replace('-', '_');
                if (lower.startsWith("en")) yield EN_WORDS;
                if (lower.startsWith("es")) yield ES_WORDS;
                if (lower.startsWith("fr")) yield FR_WORDS;
                if (lower.startsWith("de")) yield DE_WORDS;
                if (lower.startsWith("pt")) yield PT_WORDS;
                if (lower.startsWith("tr")) yield TR_WORDS;
                yield List.of();
            }
        };
    }

    /**
     * Merges configured banned words with starter words for the specified locale.
     *
     * <p>If {@code includeLocalePack} is false or {@code configuredWords} is null or
     * empty, returns the configured words without adding the starter pack (respecting
     * an operator who deliberately emptied the word list).</p>
     *
     * @param configuredWords   the operator-configured banned words
     * @param localeCode         the active server locale code
     * @param includeLocalePack whether to merge the locale pack
     * @return deduplicated unmodifiable list of words
     */
    public static List<String> mergeWithLocalePack(
            List<String> configuredWords,
            String localeCode,
            boolean includeLocalePack
    ) {
        if (!includeLocalePack || configuredWords == null || configuredWords.isEmpty()) {
            return configuredWords == null ? List.of() : List.copyOf(configuredWords);
        }
        List<String> starter = starterWordsForLocale(localeCode);
        if (starter.isEmpty()) {
            return List.copyOf(configuredWords);
        }
        Set<String> merged = new LinkedHashSet<>(configuredWords.size() + starter.size());
        merged.addAll(configuredWords);
        merged.addAll(starter);
        return List.copyOf(merged);
    }
}
