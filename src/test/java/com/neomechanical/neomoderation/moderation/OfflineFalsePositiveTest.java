package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.OfflineModerationSettings;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The safety net for evasion-resistant matching.
 *
 * <p>Matching a banned word across separators is what catches {@code f uck}. It
 * is also the change most likely to start punishing innocent players, because a
 * 400-entry list contains short entries that could in principle be assembled
 * from the ends and beginnings of adjacent innocent words. A filter that
 * punishes the innocent gets uninstalled faster than one that misses, so this
 * runs the full LDNOOBW list against ordinary English and Minecraft chat and
 * requires a clean sweep.</p>
 */
class OfflineFalsePositiveTest {

    private static final String[] CLEAN = {
            // Scunthorpe-family traps
            "scunthorpe is a town in lincolnshire",
            "the assignment is due on friday",
            "classic analysis of the bass line",
            "walking through the tall grass at night",
            "please pass me the compass",
            "mass production of glass bottles",
            "assassins creed is a good game",
            "assess the damage before rebuilding",
            "shiitake mushrooms in the stew",
            "the cockpit instruments were fine",
            "penistone is also a real place",
            "matsushita made the components",
            "i am in the class right now",
            "he was a bit of a basshunter fan",
            "the therapist will see you now",
            "we need to document everything",
            "constitutional law is dense",
            "the sussex coast is lovely",
            "an alien landed in the field",
            "a can of tuna and some rice",
            "she got a citation for parking",
            "the analyst reviewed the numbers",
            "cumulative damage over time",
            "the circumstances were unusual",
            "specialist equipment is required",
            "buttons on the control panel",
            "the button fell off my shirt",
            "we hit a snag in the schedule",
            "titanium is stronger than steel",
            "the substitute teacher arrived",
            "he is a competent administrator",
            "the assumption was wrong",
            "assorted colours are available",
            "he was assured it would work",
            "an assembly of parts",
            "the assets were transferred",
            "assign the task to someone else",
            "associate professor of history",
            "the passage was too narrow",
            "harassment is not tolerated here",
            // Ordinary Minecraft chat
            "anyone got spare cobblestone for the farm",
            "meet me at 120 64 -340 near the portal",
            "i need three stacks of iron for the farm",
            "the nether portal broke again somehow",
            "gg wp everyone that was a fun round",
            "we are running 1.20.4 on the server now",
            "x 128 y 64 z -1024 is the mob farm",
            "trading diamonds for emeralds at spawn",
            "brb dinner back in ten minutes",
            "who griefed the storage room last night",
            "lol that creeper got me again",
            "can someone craft me an elytra please",
            "the villager trading hall is finished",
            "my base is over at the ice spikes biome",
            "does anyone have a spare shulker box",
            "the raid finished and we got the totem",
            "i am mining at y minus 59 for diamonds",
            "the enchanting table needs more bookshelves",
            "can an admin tp me back to spawn please",
            "the end portal is at the stronghold",
            "we should build a gold farm next",
            "my pickaxe broke halfway through",
            "the wither killed all my armour stands",
            "anyone selling netherite scraps",
            "the redstone clock is running too fast",
            "i got lost in the caves again",
            "please claim your plot before building",
            "the server restarts in five minutes",
            "welcome to the server have fun everyone",
            "read the rules in the spawn building",
            "the auction house is open now",
            "our team won the last minigame",
            "the map reset is scheduled for sunday",
            "i built a working calculator in redstone",
            "the villagers keep breeding in my base",
            "someone left a chest unlocked at spawn",
            "the ocean monument raid starts at eight",
            "my horse ran off into the woods",
            "the beacon needs a full iron pyramid",
            "i finished the achievement list today",
            // General English with adjacent words that could concatenate
            "he ran a mile in six minutes",
            "she has a pet cat and a dog",
            "the ash from the fire covered everything",
            "a big ship sailed past the harbour",
            "we ate at a nice cafe near the park",
            "the sun set behind the hills",
            "put it back in the box please",
            "she said it was a good idea",
            "the top of the hill has a view",
            "his name is on the list already",
            "can you pass it on to him",
            "the bus is late again today",
            "we sat in the shade for a while",
            "the tea was too hot to drink",
            "i saw him at the shop earlier",
            "the cup is on the table",
            "she cut her hair short",
            "the dog barked at the postman",
            "he is a nice person to work with",
            "the fan in the corner is broken",
            // Contractions and numbers that used to be read as banned words
            "let's hit the nether fortress tonight",
            "he's hit by a skeleton every time",
            "that's hit or miss honestly",
            "who're you talking to",
            "it took 5 hits to kill the warden",
            "i have 455 diamonds in the chest",
    };

    @Test
    void theFullWordListNeverFlagsOrdinaryChat() throws Exception {
        List<String> words = loadWords();
        assertTrue(words.size() > 300, "expected the real word list, got " + words.size());

        OfflineModerationSettings settings = new OfflineModerationSettings(
                true, false, true, words, List.of(), List.of(), List.of());

        List<String> flagged = new ArrayList<>();
        for (String line : CLEAN) {
            OfflineModerationResult result = OfflineModerationEngine.evaluate(line, settings);
            if (result.flagged()) {
                flagged.add(line + "   -> " + result.reason());
            }
        }

        System.out.printf("false positives: %d/%d clean lines against %d rules%n",
                flagged.size(), CLEAN.length, words.size());
        flagged.forEach(f -> System.out.println("  " + f));

        assertTrue(flagged.isEmpty(), "evasion-resistant matching flagged innocent chat: " + flagged);
    }

    @Test
    void theFullWordListStillCatchesObfuscationAtScale() throws Exception {
        List<String> words = loadWords();
        OfflineModerationSettings settings = new OfflineModerationSettings(
                true, false, true, words, List.of(), List.of(), List.of());

        // A rule from the real list, obfuscated every way the corpus covers.
        for (String message : new String[]{
                "you are a b a s t a r d", "you are a bas tard", "you are a baaastard",
                "you are a b.a.s.t.a.r.d", "you are a bástard", "you are a ｂａｓｔａｒｄ"}) {
            assertTrue(OfflineModerationEngine.evaluate(message, settings).flagged(),
                    "missed at scale: " + message);
        }
    }

    private static List<String> loadWords() throws Exception {
        try (InputStream stream = OfflineFalsePositiveTest.class.getResourceAsStream("/badwords-en.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String> words = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    words.add(line.trim());
                }
            }
            return words;
        }
    }
}
