package com.neomechanical.neomoderation.commands.subcommands;

import com.neomechanical.neomoderation.NeoModerationPlugin;
import com.neomechanical.neomoderation.Permissions;
import com.neomechanical.neomoderation.moderation.CaseLog.CaseRecord;
import com.neomechanical.neomoderation.moderation.CaseLog;
import com.neomechanical.neomoderation.testing.TestPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CasesCmdTest {
    private final NeoModerationPlugin plugin = TestPlugin.with(new YamlConfiguration());
    private final CaseLog caseLog = plugin.caseLog();
    private final CommandSender staff = mock(CommandSender.class);

    @Test
    void aFullPageOffersTheNextOne() {
        when(caseLog.recent(null, 11, 0)).thenReturn(cases(11));

        run("cases");

        List<String> lines = TestPlugin.lines(staff);
        assertEquals("[NeoMod] Cases. Open one with /nmod case <number>.", lines.get(0));
        assertEquals(12, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(1).startsWith("  #1 Blocked Steve in chat: banned word \"badword\" "), lines.get(1));
        assertEquals("  Older cases: /nmod cases page 2", lines.get(11));
    }

    @Test
    void aPlayersHistoryKeepsTheirNameOnEveryPage() {
        when(caseLog.recent("Steve", 11, 10)).thenReturn(cases(11));

        run("cases", "Steve", "page", "2");

        List<String> lines = TestPlugin.lines(staff);
        assertEquals("[NeoMod] Cases for Steve. Open one with /nmod case <number>.", lines.get(0));
        assertEquals("  Older cases: /nmod cases Steve page 3", lines.get(lines.size() - 1));
    }

    @Test
    void aPlayerNamedWithDigitsIsListedRatherThanOpened() {
        when(caseLog.recent("1234", 11, 0)).thenReturn(cases(1));

        run("cases", "1234", "page", "1");

        verify(caseLog, never()).byId(anyLong());
        assertEquals("[NeoMod] Cases for 1234. Open one with /nmod case <number>.", TestPlugin.lines(staff).get(0));
    }

    @Test
    void aPlayerNamedLikeAnActionIsListedFromTheMoreFromButton() {
        when(caseLog.recent("Resolve", 11, 0)).thenReturn(cases(1));

        run("cases", "Resolve", "page", "1");

        verify(caseLog, never()).updateStatus(anyLong(), anyString());
        assertEquals("[NeoMod] Cases for Resolve. Open one with /nmod case <number>.", TestPlugin.lines(staff).get(0));
    }

    @Test
    void anEmptyPageSaysWhetherAnythingWasEverCaught() {
        when(caseLog.recent(null, 11, 0)).thenReturn(List.of());
        run("cases");
        when(caseLog.recent(null, 11, 10)).thenReturn(List.of());
        run("cases", "page", "2");

        assertEquals(List.of("[NeoMod] Nothing caught yet.", "[NeoMod] No older cases."), TestPlugin.lines(staff));
    }

    @Test
    void aLoneNumberOpensThatCaseWithItsButtons() {
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:badword", "blocked|mute:600|strike:2")));
        when(staff.hasPermission(Permissions.ADMIN)).thenReturn(true);

        run("case", "#7");

        String transcript = TestPlugin.transcript(staff);
        assertTrue(transcript.startsWith("[NeoMod] Case #7: Blocked Steve in chat, "), transcript);
        assertTrue(transcript.contains("  Why: banned word \"badword\""), transcript);
        assertTrue(transcript.contains("  Status: open"), transcript);
        assertTrue(transcript.contains("  They wrote: this is a badword"), transcript);
        // The console cannot click, so each button comes with the command it runs.
        assertTrue(transcript.contains("  [Resolve] /nmod cases resolve 7\n  [Dismiss] /nmod cases dismiss 7\n"
                + "  [Allow \"badword\"] /nmod allow word add badword\n  [More from Steve] /nmod cases Steve page 1\n"
                + "  [Mute 10m] /nmod mute Steve 10m"), transcript);
    }

    @Test
    void aWordAnotherLanguageUsesEveryDayPointsToTheSettingInsteadOfAllowingIt() {
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:fag", "blocked")));
        when(staff.hasPermission(Permissions.ADMIN)).thenReturn(true);

        run("case", "7");

        String transcript = TestPlugin.transcript(staff);
        assertTrue(transcript.contains("  \"fag\" is an everyday word in Danish and Norwegian. Set "
                + "moderation.offline.trustPlayerLanguage to true to let players use it when they write in their own "
                + "language."), transcript);
        assertFalse(transcript.contains("Allow"), "allowing the word would let the slur through for everyone");
    }

    @Test
    void aNativeWordReadAsABannedOneIsExplainedToo() {
        // Danish "putte" (put) is read as the banned "pute"; Portugal's "puto" (kid) is not Brazil's.
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:pute", "blocked", "putte det i kisten")));
        when(caseLog.byId(8)).thenReturn(Optional.of(record(8, "blocked_word:puto", "blocked")));
        when(staff.hasPermission(Permissions.ADMIN)).thenReturn(true);

        run("case", "7");
        run("case", "8");

        String transcript = TestPlugin.transcript(staff);
        assertTrue(transcript.contains("\"putte\" is an everyday word in Danish and Norwegian."), transcript);
        assertTrue(transcript.contains("\"puto\" is an everyday word in Portuguese (Portugal)."), transcript);
        assertFalse(transcript.contains("Allow"), transcript);
    }

    @Test
    void withoutTheTextANativeReadingIsStillExplainedAndNotAllowed() {
        // Servers keep no message text by default, so the case cannot tell French "pute" from Danish "putte".
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:pute", "blocked", "")));
        when(staff.hasPermission(Permissions.ADMIN)).thenReturn(true);

        run("case", "7");

        String transcript = TestPlugin.transcript(staff);
        assertTrue(transcript.contains("\"putte\" is an everyday word in Danish and Norwegian."), transcript);
        assertFalse(transcript.contains("Allow"), transcript);
    }

    @Test
    void aMessageThatRepeatsAWordOpensItsCase() {
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:fuck", "blocked", "fuck you fuck you")));
        when(staff.hasPermission(Permissions.ADMIN)).thenReturn(true);

        run("case", "7");

        assertTrue(TestPlugin.transcript(staff).contains("[Allow \"fuck\"]"), TestPlugin.transcript(staff));
    }

    @Test
    void theSameBannedWordWrittenAsItselfGetsNoNativeHint() {
        // French "pute" is the slur itself: no Danish speaker wrote "putte" here.
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:pute", "blocked", "sale pute")));
        when(staff.hasPermission(Permissions.ADMIN)).thenReturn(true);

        run("case", "7");

        String transcript = TestPlugin.transcript(staff);
        assertFalse(transcript.contains("everyday word"), transcript);
        assertTrue(transcript.contains("[Allow \"pute\"]"), transcript);
    }

    @Test
    void aWordWrittenInPiecesGetsNoHint() {
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "spaced_blocked_word:fag", "blocked")));

        run("case", "7");

        assertFalse(TestPlugin.transcript(staff).contains("everyday word"), TestPlugin.transcript(staff));
    }

    @Test
    void onlyAdminsAreOfferedToAllowAWord() {
        when(caseLog.byId(7)).thenReturn(Optional.of(record(7, "blocked_word:badword", "blocked")));

        run("case", "7");

        String transcript = TestPlugin.transcript(staff);
        assertTrue(transcript.contains("  [Resolve] /nmod cases resolve 7\n  [Dismiss] /nmod cases dismiss 7\n"
                + "  [More from Steve] /nmod cases Steve page 1\n  [Mute 10m] /nmod mute Steve 10m"), transcript);
        assertFalse(transcript.contains("Allow"), transcript);
    }

    @Test
    void resolvingAndDismissingNameTheCase() {
        when(caseLog.updateStatus(7, "resolved")).thenReturn(true);
        when(caseLog.updateStatus(8, "dismissed")).thenReturn(false);

        run("cases", "resolve", "7");
        run("cases", "dismiss", "#8");
        run("cases", "resolve", "seven");

        assertEquals(List.of(
                "[NeoMod] Case #7 marked resolved.",
                "[NeoMod] There is no case #8.",
                "[NeoMod] There is no case #seven."), TestPlugin.lines(staff));
    }

    @Test
    void historyThatCannotBeKeptSaysWhy() {
        when(caseLog.isAvailable()).thenReturn(false);
        run("cases");

        assertEquals(List.of("[NeoMod] Case history can't be saved: this server has no SQLite driver."),
                TestPlugin.lines(staff));
        verify(caseLog, never()).recent(anyString(), anyInt(), anyInt());
    }

    private void run(String... args) {
        new CasesCmd(plugin).execute(staff, "nmod", args);
    }

    private static List<CaseRecord> cases(int count) {
        List<CaseRecord> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(record(i, "blocked_word:badword", "blocked"));
        }
        return rows;
    }

    private static CaseRecord record(long id, String reason, String action) {
        return record(id, reason, action, "this is a badword");
    }

    private static CaseRecord record(long id, String reason, String action, String preview) {
        return new CaseRecord(id, System.currentTimeMillis() - 120_000L, "uuid", "Steve", "chat", reason, action,
                "enforce", preview);
    }
}
