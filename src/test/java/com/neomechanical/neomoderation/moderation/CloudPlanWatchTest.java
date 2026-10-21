package com.neomechanical.neomoderation.moderation;

import com.neomechanical.neomoderation.config.ModerationApiSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudPlanWatchTest {

    private static final TrialClient.TrialStatusResult ACTIVE =
            new TrialClient.TrialStatusResult(true, "active", "2026-10-22T00:00:00Z", 2, "https://claim");
    private static final TrialClient.TrialStatusResult ENDED =
            new TrialClient.TrialStatusResult(true, "expired", "2026-10-22T00:00:00Z", 0, "https://signup");
    private static final TrialClient.TrialStatusResult STANDARD =
            new TrialClient.TrialStatusResult(false, "standard_workspace", "", 0, "https://billing");

    private final TrialClient client = mock(TrialClient.class);
    private final AtomicLong clock = new AtomicLong(Instant.parse("2026-10-20T12:00:00Z").toEpochMilli());
    private final List<Runnable> pending = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();
    /** The key in config.yml: the plugin loads it into the settings before telling the watch, as {@link #key} does. */
    private final AtomicReference<String> configuredKey = new AtomicReference<>("");
    private final CloudPlanWatch watch = new CloudPlanWatch(client,
            () -> new ModerationApiSettings("https://api.test/v1/events", configuredKey.get(), 1000, 1000),
            pending::add, clock::get,
            (snapshot, refusal) -> answers.add(snapshot.state() + (refusal == null ? "" : " after " + refusal)));

    private boolean key(String apiKey) {
        configuredKey.set(apiKey);
        return watch.keyChanged(apiKey);
    }

    private void runPending() {
        List<Runnable> tasks = new ArrayList<>(pending);
        pending.clear();
        tasks.forEach(Runnable::run);
    }

    private void minutes(long minutes) {
        clock.addAndGet(TimeUnit.MINUTES.toMillis(minutes));
    }

    @Test
    void readsEachAnswerAsTheStepItNeeds() {
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, CloudPlanWatch.classify(ACTIVE));
        assertEquals(CloudPlanWatch.State.TRIAL_ENDED, CloudPlanWatch.classify(ENDED));
        assertEquals(CloudPlanWatch.State.TRIAL_ENDED, CloudPlanWatch.classify(
                new TrialClient.TrialStatusResult(true, "revoked", "2026-10-22T00:00:00Z", 0, "https://signup")));
        assertEquals(CloudPlanWatch.State.STANDARD, CloudPlanWatch.classify(STANDARD));
        assertEquals(CloudPlanWatch.State.UNKNOWN, CloudPlanWatch.classify(null));
    }

    @Test
    void asksOnceForEachNewKeyAndForgetsTheOldOne() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_one");
        runPending();
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, watch.state());
        assertEquals(List.of("TRIAL_ACTIVE"), answers);

        key(" nm_one ");
        assertEquals(0, pending.size(), "the same key asks nothing");

        when(client.fetchTrialStatus(any())).thenReturn(STANDARD);
        key("nm_two");
        assertEquals(CloudPlanWatch.State.UNKNOWN, watch.state(), "a new key forgets the old key's answer");
        runPending();
        assertEquals(CloudPlanWatch.State.STANDARD, watch.state());

        key("");
        assertEquals(0, pending.size(), "no key asks nothing");
        assertEquals(CloudPlanWatch.State.UNKNOWN, watch.state());
        verify(client, times(2)).fetchTrialStatus(any());
    }

    @Test
    void aRefusalAsksWhyAtMostEveryTenMinutes() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        key("nm_trial");
        runPending();
        answers.clear();

        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        runPending();
        assertEquals(List.of("TRIAL_ENDED after CLIENT_AUTH"), answers);

        clock.addAndGet(CloudPlanWatch.RECHECK_MILLIS - 1);
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        assertEquals(0, pending.size(), "asked within ten minutes, so it answers from what it knows");
        assertEquals(List.of("TRIAL_ENDED after CLIENT_AUTH", "TRIAL_ENDED after CLIENT_AUTH"), answers);

        clock.addAndGet(1);
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        assertEquals(1, pending.size(), "ten minutes on, it asks again");
        runPending();
        verify(client, times(3)).fetchTrialStatus(any());
    }

    @Test
    void aRefusalWhileAnAnswerIsInFlightWaitsForThatAnswer() throws Exception {
        // Answering from nothing known would log "key rejected" for an ended trial, then the answer would add
        // "trial ended".
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        key("nm_trial");
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        assertEquals(List.of(), answers);
        assertEquals(1, pending.size(), "one question, not two");
        runPending();
        assertEquals(List.of("TRIAL_ENDED after CLIENT_AUTH"), answers);
        verify(client, times(1)).fetchTrialStatus(any());
    }

    @Test
    void aKeyTheTrialServiceRefusesTooIsRejectedAndAnUnreachableServiceSaysNothing() throws Exception {
        when(client.fetchTrialStatus(any())).thenThrow(
                new TrialClient.TrialException(TrialClient.TrialError.FORBIDDEN, "API key rejected"));
        key("nm_wrong");
        runPending();
        assertEquals(CloudPlanWatch.State.KEY_REJECTED, watch.state());

        // doThrow: re-stubbing with when() would call the method that already throws.
        doThrow(new TrialClient.TrialException(TrialClient.TrialError.TRANSPORT_ERROR, "down"))
                .when(client).fetchTrialStatus(any());
        key("nm_other");
        runPending();
        assertEquals(CloudPlanWatch.State.UNKNOWN, watch.state());
        assertNull(watch.current().status());
    }

    @Test
    void anUnexpectedFailureStillLandsAnAnswerSoTheWatchAsksAgainLater() throws Exception {
        doThrow(new IllegalStateException("bug")).when(client).fetchTrialStatus(any());
        key("nm_key");
        runPending();
        assertEquals(CloudPlanWatch.State.UNKNOWN, watch.state());
        minutes(60);
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "not stuck waiting on the answer that failed");
    }

    @Test
    void anAnswerForAKeyThatWasReplacedMeanwhileIsDropped() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        key("nm_old");
        List<Runnable> forOldKey = new ArrayList<>(pending);
        pending.clear();
        when(client.fetchTrialStatus(any())).thenReturn(STANDARD);
        key("nm_new");
        runPending();
        forOldKey.forEach(Runnable::run);
        assertEquals(CloudPlanWatch.State.STANDARD, watch.state());
        assertEquals(List.of("STANDARD"), answers);
    }

    @Test
    void aStatusFetchedElsewhereIsKeptWithoutAskingAgainWhenItIsForThisKey() throws Exception {
        key("nm_key");
        pending.clear();
        watch.accept("nm_other", ENDED);
        assertEquals(CloudPlanWatch.State.UNKNOWN, watch.state(), "a status for another key is not this key's");
        watch.accept("nm_key", STANDARD);
        assertEquals(CloudPlanWatch.State.STANDARD, watch.state());
        verify(client, never()).fetchTrialStatus(any());
    }

    @Test
    void aRunningTrialReadsAsEndedOnceItsEndDatePassesWithoutAsking() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_trial");
        runPending();
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, watch.state());
        clock.set(Instant.parse("2026-10-22T00:00:00Z").toEpochMilli());
        assertEquals(CloudPlanWatch.State.TRIAL_ENDED, watch.state());
        verify(client, times(1)).fetchTrialStatus(any());
    }

    @Test
    void anOldAnswerIsAskedAgainHourlyDuringATrialAndEverySixHoursOtherwise() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_trial");
        runPending();
        minutes(59);
        watch.refreshIfStale();
        assertEquals(0, pending.size());
        minutes(1);
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "a trial kept in an account shows within the hour");
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "one question at a time");

        when(client.fetchTrialStatus(any())).thenReturn(STANDARD);
        runPending();
        assertEquals(CloudPlanWatch.State.STANDARD, watch.state());
        minutes(6 * 60 - 1);
        watch.refreshIfStale();
        assertEquals(0, pending.size());
        minutes(1);
        watch.refreshIfStale();
        assertEquals(1, pending.size());

        key("");
        pending.clear();
        minutes(24 * 60);
        watch.refreshIfStale();
        assertEquals(0, pending.size(), "no key, nothing to ask");
    }

    @Test
    void theFirstSuccessAfterAnEndedTrialAsksWhetherItEndedAfterAll() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        key("nm_trial");
        runPending();
        when(client.fetchTrialStatus(any())).thenReturn(STANDARD);
        watch.succeeded();
        assertEquals(1, pending.size(), "a key that works again is asked about");
        runPending();
        assertEquals(CloudPlanWatch.State.STANDARD, watch.state());

        watch.succeeded();
        assertEquals(0, pending.size(), "a working key's successes ask nothing");
    }

    @Test
    void aRunningTrialsRefusalIsRememberedUntilTheKeyWorksAgain() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_trial");
        runPending();
        watch.refused(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        runPending();
        assertEquals(ModerationApiResult.Kind.INSUFFICIENT_CREDITS, watch.current().refusal(),
                "a running trial refused with a 402 has used its credits");
        minutes(60);
        watch.refreshIfStale();
        runPending();
        assertEquals(ModerationApiResult.Kind.INSUFFICIENT_CREDITS, watch.current().refusal(),
                "a fresh answer keeps it: the trial service does not count credits");
        watch.succeeded();
        assertNull(watch.current().refusal());
        assertEquals(0, pending.size());
    }

    @Test
    void aQuestionIsAskedWithTheSettingsOfTheKeyItIsAbout() throws Exception {
        // A reload loads the new key into the settings before it tells the watch: a question that read the settings
        // when it ran asked about the new key and landed its answer under the old one.
        when(client.fetchTrialStatus(any())).thenAnswer(call ->
                call.<ModerationApiSettings>getArgument(0).apiKey().equals("nm_a") ? ACTIVE : ENDED);
        key("nm_a");
        configuredKey.set("nm_b");
        runPending();
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, watch.state(), "asked with nm_a's settings, so nm_a's answer");
    }

    @Test
    void aKeyFlippedAwayAndBackDropsTheQuestionAskedBeforeTheFlip() throws Exception {
        AtomicLong calls = new AtomicLong();
        when(client.fetchTrialStatus(any())).thenAnswer(call -> calls.incrementAndGet() == 1 ? ACTIVE : ENDED);
        key("nm_a");
        Runnable askedBeforeTheFlip = pending.remove(0);
        key("nm_b");
        pending.remove(0);
        key("nm_a");
        runPending();
        askedBeforeTheFlip.run();
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, watch.state(),
                "the question asked before the flip is not the one in flight, whichever key it was about");
    }

    @Test
    void aSuccessMomentsAfterARefusalMarksTheAnswerOldSoTheNextRefreshAsks() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        key("nm_trial");
        runPending();
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        runPending();
        minutes(1);
        watch.succeeded();
        assertEquals(0, pending.size(), "asked a minute ago, so not again at once");
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "but the next ten-minute tick does, not six hours on");
    }

    @Test
    void anAnswerToAQuestionAskedBeforeAStatusTheAdminFetchedIsDropped() throws Exception {
        // An admin keeps the trial in an account and runs /nmod trial status; an older question still in flight
        // would put "free trial" back for up to an hour.
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_trial");
        watch.accept("nm_trial", STANDARD);
        runPending();
        assertEquals(CloudPlanWatch.State.STANDARD, watch.state(), "the admin's newer answer stands");
        minutes(6 * 60);
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "and the watch is free to ask again once that answer is old");
    }

    @Test
    void aQuestionThatGetsNoAnswerKeepsTheLastOneAndAsksAgainAnHourLater() throws Exception {
        // One timed-out or failed answer, during a platform deploy say, once turned a running trial into "unknown"
        // for an hour, and the plugin sent a trial out of credits to billing again.
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_trial");
        runPending();
        watch.refused(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        runPending();
        minutes(60);
        doThrow(new TrialClient.TrialException(TrialClient.TrialError.SERVER_ERROR, "HTTP 502"))
                .when(client).fetchTrialStatus(any());
        watch.refreshIfStale();
        answers.clear();
        runPending();
        assertEquals(CloudPlanWatch.State.TRIAL_ACTIVE, watch.state());
        assertEquals(ACTIVE, watch.current().status());
        assertEquals(ModerationApiResult.Kind.INSUFFICIENT_CREDITS, watch.current().refusal());
        assertEquals(List.of("TRIAL_ACTIVE"), answers, "told with what was known");

        minutes(59);
        watch.refreshIfStale();
        assertEquals(0, pending.size(), "not asked again within the hour");
        minutes(1);
        watch.refreshIfStale();
        assertEquals(1, pending.size());
    }

    @Test
    void aKeyThatWorksWhileAQuestionIsOutHasThatAnswerAskedAgainAtTheNextRefresh() throws Exception {
        // Support extends an ended trial: chat works again while a question asked before is still out, and its
        // "ended" answer would otherwise stand for six hours beside "Last cloud check: worked".
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        key("nm_trial");
        runPending();
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        watch.succeeded();
        runPending();
        assertEquals(CloudPlanWatch.State.TRIAL_ENDED, watch.state());
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "the next ten-minute tick asks again");
    }

    @Test
    void aSchedulerThatThrowsAnErrorLeavesTheWatchFreeToAskLater() throws Exception {
        AtomicReference<Boolean> fail = new AtomicReference<>(true);
        List<Runnable> tasks = new ArrayList<>();
        AtomicReference<String> configured = new AtomicReference<>("nm_key");
        CloudPlanWatch failing = new CloudPlanWatch(client,
                () -> new ModerationApiSettings("https://api.test/v1/events", configured.get(), 1000, 1000),
                task -> {
                    if (fail.get()) {
                        throw new LinkageError("a class gone after a reload");
                    }
                    tasks.add(task);
                }, clock::get, (snapshot, refusal) -> { });
        assertThrows(LinkageError.class, () -> failing.keyChanged("nm_key"));
        fail.set(false);
        failing.refreshIfStale();
        assertEquals(1, tasks.size(), "not stuck waiting for an answer that was never asked");
    }

    @Test
    void aFetchThatThrowsAnErrorStillFreesTheWatch() throws Exception {
        doThrow(new NoClassDefFoundError("a class gone after a reload")).when(client).fetchTrialStatus(any());
        key("nm_key");
        assertThrows(NoClassDefFoundError.class, this::runPending);
        minutes(60);
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "not stuck waiting on the question that failed");
    }

    @Test
    void aSchedulerThatRefusesTheQuestionLeavesTheWatchFreeToAskLater() throws Exception {
        AtomicReference<Boolean> refuse = new AtomicReference<>(true);
        List<Runnable> tasks = new ArrayList<>();
        AtomicReference<String> configured = new AtomicReference<>("nm_key");
        CloudPlanWatch refusing = new CloudPlanWatch(client,
                () -> new ModerationApiSettings("https://api.test/v1/events", configured.get(), 1000, 1000),
                task -> {
                    if (refuse.get()) {
                        throw new IllegalStateException("plugin disabled");
                    }
                    tasks.add(task);
                }, clock::get, (snapshot, refusal) -> { });
        when(client.fetchTrialStatus(any())).thenReturn(STANDARD);
        refusing.keyChanged("nm_key");
        refuse.set(false);
        refusing.refreshIfStale();
        assertEquals(1, tasks.size(), "not stuck waiting for an answer that was never asked");
    }

    @Test
    void aRunningTrialIsAskedAboutOnceWhenItsEndDatePassesAfterItsAnswer() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        clock.set(Instant.parse("2026-10-21T23:30:00Z").toEpochMilli());
        key("nm_trial");
        runPending();
        clock.set(Instant.parse("2026-10-22T00:00:00Z").toEpochMilli());
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "half an hour after its answer: kept in its last hour, it must not read as ended for long");

        // A server clock ahead of the service's: the service still says the trial runs.
        runPending();
        minutes(10);
        watch.refreshIfStale();
        assertEquals(0, pending.size(), "an end that passed before the answer asks nothing every ten minutes");
        minutes(50);
        watch.refreshIfStale();
        assertEquals(1, pending.size(), "a running trial's answer is still asked again hourly");
    }

    @Test
    void theTrialServiceRejectingTheKeyIsKeptForThatKeyOnly() {
        key("nm_key");
        pending.clear();
        watch.rejected("nm_other");
        assertEquals(CloudPlanWatch.State.UNKNOWN, watch.state());
        watch.rejected("nm_key");
        assertEquals(CloudPlanWatch.State.KEY_REJECTED, watch.state());
    }

    @Test
    void aTrialThatRanOutOfCreditsAndThenEndedIsAskedAboutAtOnce() throws Exception {
        when(client.fetchTrialStatus(any())).thenReturn(ACTIVE);
        key("nm_trial");
        runPending();
        watch.refused(ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        runPending();
        answers.clear();
        when(client.fetchTrialStatus(any())).thenReturn(ENDED);
        minutes(1);
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        assertEquals(1, pending.size(), "a 401 after a 402 is news: answering from the old answer would say key rejected");
        runPending();
        assertEquals(List.of("TRIAL_ENDED after CLIENT_AUTH"), answers);
        watch.refused(ModerationApiResult.Kind.CLIENT_AUTH);
        assertEquals(0, pending.size(), "the same kind again within ten minutes is answered from what it knows");
    }
}
