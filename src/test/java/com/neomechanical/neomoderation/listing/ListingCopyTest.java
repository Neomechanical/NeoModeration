package com.neomechanical.neomoderation.listing;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.commands.NeoModerationCommand;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Modrinth, Hangar and SpigotMC listing text in {@code assets/listing}, held to the
 * Neomechanical design system's writing rules (DESIGN.md section 6) and to the plugin's code.
 */
class ListingCopyTest {

    private static final Path DIR = Path.of("assets", "listing");
    private static final Path SUMMARY = DIR.resolve("summary.txt");
    private static final Map<Path, String> PAGES = Map.of(
            DIR.resolve("modrinth.md"), "neomoderation_modrinth",
            DIR.resolve("hangar.md"), "neomoderation_hangar",
            DIR.resolve("spigotmc.bbcode"), "neomoderation_spigot");

    /** SpigotMC's tag line field, the shortest of the three sites' summary fields. */
    private static final int SUMMARY_LIMIT = 100;

    /** DESIGN.md section 6, as neomechanical-platform's tests/public-copy-voice.test.js reads it. */
    private static final Map<String, Pattern> WRITING_RULES = new LinkedHashMap<>();

    static {
        WRITING_RULES.put("antithesis", Pattern.compile("(—|--|,)\\s*(not|never)\\s+\\w|\\brather than\\b"
                + "|\\bnot just\\b[^.]{0,60}\\bbut\\b|\\bisn['’]t\\s+(just\\s+)?\\w+[^.]{0,40},\\s*it['’]s\\b",
                Pattern.CASE_INSENSITIVE));
        WRITING_RULES.put("aphorism", Pattern.compile("\\b(is|are|was|were)\\s+not\\s+(a|an|the)\\s+\\w+\\.\\s"));
        WRITING_RULES.put("virtue signalling", Pattern.compile("\\b(published anyway|the ones we lose"
                + "|including the losses|we publish every one|whether or not we liked|nothing is averaged"
                + "|in the open\\b|we publish the record|results that flatter)", Pattern.CASE_INSENSITIVE));
        WRITING_RULES.put("spaced dash", Pattern.compile("[\\w)'\"’”%]\\s+[—–]\\s+[\\w('\"‘“$]"));
        WRITING_RULES.put("arrow", Pattern.compile("→"));
        WRITING_RULES.put("middle dot", Pattern.compile(" · "));
        WRITING_RULES.put("colon flourish", Pattern.compile(":\\s+(the|a|an)\\s+\\w+[^.<>{}\"]{40,}\\."));
        WRITING_RULES.put("sign-in words", Pattern.compile("\\blog ?(in|out)\\b|\\blogin\\b", Pattern.CASE_INSENSITIVE));
        WRITING_RULES.put("company name", Pattern.compile("NeoMechanical"));
        WRITING_RULES.put("American spelling", Pattern.compile("\\b(color|behavior|organization|normalize"
                + "|recognize|analyze|customize|center|license)s?\\b", Pattern.CASE_INSENSITIVE));
        // Public copy never names the moderation vendor (tests/no-vendor-mentions.test.js) and makes
        // no claim about detecting minors (tests/docs-categories.test.js).
        WRITING_RULES.put("moderation vendor", Pattern.compile("openai|\\bomni\\b", Pattern.CASE_INSENSITIVE));
        WRITING_RULES.put("minors", Pattern.compile("minor|child|csam|underage", Pattern.CASE_INSENSITIVE));
    }

    @Test
    void summaryIsOneLineThatFitsEverySite() throws IOException {
        String summary = Files.readString(SUMMARY).strip();
        assertFalse(summary.isEmpty(), "the summary is empty");
        assertFalse(summary.contains("\n"), "the summary must be one line");
        assertTrue(summary.length() <= SUMMARY_LIMIT,
                "the summary is " + summary.length() + " characters; SpigotMC takes " + SUMMARY_LIMIT);
    }

    @Test
    void everyPageIntroducesTheProductByTheCompany() throws IOException {
        for (Path page : PAGES.keySet()) {
            assertTrue(Files.readString(page).startsWith("NeoModeration by Neomechanical "),
                    page + " must open with \"NeoModeration by Neomechanical\" (DESIGN.md, One company)");
        }
    }

    @Test
    void theThreeSitesSayTheSameThing() throws IOException {
        String modrinth = markdownText(Files.readString(DIR.resolve("modrinth.md")));
        assertEquals(modrinth, markdownText(Files.readString(DIR.resolve("hangar.md"))),
                "hangar.md must say what modrinth.md says");
        assertEquals(modrinth, bbcodeText(Files.readString(DIR.resolve("spigotmc.bbcode"))),
                "spigotmc.bbcode must say what modrinth.md says");
    }

    @Test
    void everyCompanyLinkCarriesItsSitesSource() throws IOException {
        Pattern link = Pattern.compile("https://neomechanical\\.com[^\\s)'\\]]*");
        for (Map.Entry<Path, String> page : PAGES.entrySet()) {
            Matcher matcher = link.matcher(Files.readString(page.getKey()));
            int links = 0;
            while (matcher.find()) {
                links++;
                assertTrue(matcher.group().endsWith("?src=" + page.getValue()),
                        page.getKey() + ": " + matcher.group() + " must end with ?src=" + page.getValue());
            }
            assertTrue(links > 0, page.getKey() + " links nowhere on neomechanical.com");
        }
    }

    @Test
    void copyFollowsTheWritingRules() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> files = new ArrayList<>(PAGES.keySet());
        files.add(SUMMARY);
        for (Path file : files) {
            String text = Files.readString(file);
            WRITING_RULES.forEach((rule, pattern) -> {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    violations.add(file + " (" + rule + "): " + matcher.group());
                }
            });
        }
        assertTrue(violations.isEmpty(), () -> "Listing copy breaks DESIGN.md:\n" + String.join("\n", violations));
    }

    @Test
    void everyCommandTheCopyNamesExists() throws IOException {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        List<String> commands = new NeoModerationCommand(mock(NeoModerationPlugin.class))
                .onTabComplete(sender, null, "nmod", new String[]{""});
        assertFalse(commands.isEmpty(), "the command router listed no subcommands");

        List<String> unknown = new ArrayList<>();
        int named = 0;
        Pattern command = Pattern.compile("/nmod (\\w+)");
        for (Path page : PAGES.keySet()) {
            Matcher matcher = command.matcher(Files.readString(page));
            while (matcher.find()) {
                named++;
                if (!commands.contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                    unknown.add(page + ": " + matcher.group());
                }
            }
        }
        assertTrue(named > 0, "the listing names no /nmod commands");
        assertTrue(unknown.isEmpty(), () -> "The listing names commands the plugin does not have:\n"
                + String.join("\n", unknown));
    }

    /** The words of a Markdown page, without its markup. */
    private static String markdownText(String markdown) {
        return words(markdown
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                .replaceAll("(?m)^(#+|-|\\d+\\.)\\s+", "")
                .replace("`", ""));
    }

    /** The words of a BBCode page, without its markup. */
    private static String bbcodeText(String bbcode) {
        return words(bbcode
                .replaceAll("\\[URL='[^']*'\\]([^\\[]*)\\[/URL\\]", "$1")
                .replaceAll("\\[/?(B|LIST(=1)?|FONT(=[^\\]]*)?)\\]", "")
                .replace("[*]", ""));
    }

    private static String words(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
