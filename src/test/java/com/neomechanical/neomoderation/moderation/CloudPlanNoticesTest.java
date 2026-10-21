package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPlanNoticesTest {

    private static final String CLAIM = "https://neomechanical.com/signup?trial=t&src=neomoderation_trial";
    private static final String END = "2026-10-22T00:00:00Z";
    private static final long NOW = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli();

    private static CloudPlanWatch.Snapshot snapshot(TrialClient.TrialStatusResult status) {
        return new CloudPlanWatch.Snapshot(CloudPlanWatch.classify(status), status, null);
    }

    private static TrialClient.TrialStatusResult trial(String state, String url) {
        return new TrialClient.TrialStatusResult(true, state, END, state.equals("active") ? 7 : 0, url);
    }

    private static long daysBeforeTheEnd(int days) {
        return Instant.parse(END).toEpochMilli() - days * 86_400_000L;
    }

    @Test
    void eachRefusalIsNamedByItsCauseWithTheLinkThatFixesIt() {
        assertEquals(new CloudPlanNotices.Reason("trial-ended", CloudRecovery.SIGNUP_URL, END),
                CloudPlanNotices.refusal(snapshot(trial("expired", CLAIM)), ModerationApiResult.Kind.CLIENT_AUTH),
                "an ended trial cannot be kept, so never its claim link");
        assertEquals(new CloudPlanNotices.Reason("trial-revoked", CloudRecovery.SIGNUP_URL, ""),
                CloudPlanNotices.refusal(snapshot(trial("revoked", CLAIM)), ModerationApiResult.Kind.CLIENT_AUTH),
                "the service sends the planned end, not the day it stopped");
        assertEquals(new CloudPlanNotices.Reason("trial-credits", "", ""), CloudPlanNotices.refusal(
                snapshot(trial("active", CLAIM)), ModerationApiResult.Kind.INSUFFICIENT_CREDITS),
                "a running trial out of credits is kept in an account first, by its claim link, which is only ever a "
                        + "button in game: the reason that every text surface shows carries none");
        TrialClient.TrialStatusResult standard =
                new TrialClient.TrialStatusResult(false, "standard_workspace", "", 0, "https://billing");
        assertEquals(new CloudPlanNotices.Reason("auth", CloudRecovery.API_KEYS_URL, ""),
                CloudPlanNotices.refusal(snapshot(standard), ModerationApiResult.Kind.CLIENT_AUTH));
        assertEquals(new CloudPlanNotices.Reason("credits", CloudRecovery.BILLING_URL, ""),
                CloudPlanNotices.refusal(snapshot(standard), ModerationApiResult.Kind.INSUFFICIENT_CREDITS));
        assertEquals("credits", CloudPlanNotices.refusal(CloudPlanWatch.Snapshot.UNKNOWN,
                ModerationApiResult.Kind.INSUFFICIENT_CREDITS).name(), "an unreachable trial service says nothing new");
        assertNull(CloudPlanNotices.refusal(snapshot(standard), ModerationApiResult.Kind.CLEAR));
        assertNull(CloudPlanNotices.refusal(snapshot(standard), ModerationApiResult.Kind.TRANSIENT_TRANSPORT));
        for (CloudPlanWatch.State state : CloudPlanWatch.State.values()) {
            TrialClient.TrialStatusResult status = state == CloudPlanWatch.State.TRIAL_ACTIVE
                    || state == CloudPlanWatch.State.TRIAL_ENDED ? trial("active", CLAIM) : null;
            for (ModerationApiResult.Kind kind : new ModerationApiResult.Kind[]{
                    ModerationApiResult.Kind.CLIENT_AUTH, ModerationApiResult.Kind.INSUFFICIENT_CREDITS}) {
                String name = CloudPlanNotices.refusal(new CloudPlanWatch.Snapshot(state, status, null), kind).name();
                assertEquals(true, CloudPlanNotices.REASONS.contains(name), state + " " + kind + " -> " + name);
            }
        }
    }

    @Test
    void theConsoleHearsTheRefusalOrAProblemAnAnswerShows() {
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.console-trial-ended", CloudRecovery.SIGNUP_URL, END),
                CloudPlanNotices.console(snapshot(trial("expired", CLAIM)), ModerationApiResult.Kind.CLIENT_AUTH));
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.console-trial-ended", CloudRecovery.SIGNUP_URL, END),
                CloudPlanNotices.console(snapshot(trial("expired", CLAIM)), null), "said at startup too");
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.console-auth", CloudRecovery.API_KEYS_URL, ""),
                CloudPlanNotices.console(new CloudPlanWatch.Snapshot(CloudPlanWatch.State.KEY_REJECTED, null, null),
                        null), "a key the trial service does not know either");
        assertEquals("cloud-plan.console-trial-credits", CloudPlanNotices.console(snapshot(trial("active", CLAIM)),
                ModerationApiResult.Kind.INSUFFICIENT_CREDITS).key());
        TrialClient.TrialStatusResult standard =
                new TrialClient.TrialStatusResult(false, "standard_workspace", "", 0, "https://billing");
        assertNull(CloudPlanNotices.console(snapshot(standard), null), "a working key at startup needs no line");
        assertNull(CloudPlanNotices.console(snapshot(trial("active", CLAIM)), null));
    }

    @Test
    void anAdminHearsOnceAboutATrialEndingSoonEndedOrOutOfCreditsCountedByTheClock() {
        CloudPlanWatch.Snapshot running = snapshot(trial("active", CLAIM));
        assertNull(CloudPlanNotices.join(running, daysBeforeTheEnd(CloudPlanNotices.ENDING_SOON_DAYS + 1)),
                "the days left are counted now, not taken from an old answer");
        CloudPlanNotices.Notice ending = CloudPlanNotices.join(running,
                daysBeforeTheEnd(CloudPlanNotices.ENDING_SOON_DAYS));
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.join-trial-ending", CLAIM, END), ending);
        assertEquals(".join-trial-ending", CloudPlanNotices.joinMarker(ending));

        CloudPlanNotices.Notice ended = CloudPlanNotices.join(snapshot(trial("expired", CLAIM)), NOW);
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.join-trial-ended", CloudRecovery.SIGNUP_URL, END), ended);
        assertEquals(".join-trial-ended", CloudPlanNotices.joinMarker(ended));
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.join-trial-revoked", CloudRecovery.SIGNUP_URL, ""),
                CloudPlanNotices.join(snapshot(trial("revoked", CLAIM)), NOW));

        CloudPlanNotices.Notice noCredits = CloudPlanNotices.join(new CloudPlanWatch.Snapshot(
                CloudPlanWatch.State.TRIAL_ACTIVE, trial("active", CLAIM), ModerationApiResult.Kind.INSUFFICIENT_CREDITS),
                NOW);
        assertEquals(new CloudPlanNotices.Notice("cloud-plan.join-trial-credits", CLAIM, ""), noCredits);
        assertEquals(".join-trial-credits", CloudPlanNotices.joinMarker(noCredits));

        assertNull(CloudPlanNotices.join(running, NOW), "a week left: nothing to say yet");
        assertNull(CloudPlanNotices.join(CloudPlanWatch.Snapshot.UNKNOWN, NOW));
        // Its end date passed since the service last answered: it may have been kept in its last hour, and the notice
        // is once per install, so it waits for the service to say.
        assertNull(CloudPlanNotices.join(new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ENDED,
                trial("active", CLAIM), null), NOW));

        assertEquals("trial.keep-button", CloudPlanNotices.joinButton(ending));
        assertEquals("trial.keep-button", CloudPlanNotices.joinButton(noCredits));
        assertEquals("trial.create-account-button", CloudPlanNotices.joinButton(ended), "an ended trial cannot be kept");
    }

    @Test
    void aTrialSentWithoutALinkGetsThePageThatWorksForItsStateNeverBilling() throws Exception {
        // The parser leaves a trial's link empty when the service sends none, so the fallback here is the one used.
        TrialClient.TrialStatusResult noLink = TrialClient.parseStatusSuccess(
                "{\"isTrial\":true,\"status\":\"active\",\"expiresAt\":\"2026-10-22T00:00:00Z\",\"daysRemaining\":7}");
        assertEquals("", noLink.upgradeUrl());
        assertEquals(CloudRecovery.SIGNUP_URL, CloudPlanNotices.join(snapshot(noLink), daysBeforeTheEnd(1)).url());
        assertEquals(CloudRecovery.BILLING_URL, TrialClient.parseStatusSuccess(
                "{\"isTrial\":false,\"status\":\"standard_workspace\"}").upgradeUrl(), "a key from an account has billing");
    }

    @Test
    void aRefusalToldWhileNothingWasKnownIsToldAgainOnceTheAnswerSaysWhatItIs() {
        // The trial service was unreachable when the 402 came, so the console said "out of credits, add some at
        // billing"; the later answer, a running trial, must correct it rather than say nothing.
        CloudPlanWatch.Snapshot known = new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE,
                trial("active", CLAIM), ModerationApiResult.Kind.INSUFFICIENT_CREDITS);
        assertEquals("cloud-plan.console-trial-credits", CloudPlanNotices.console(known, null).key());
        assertNull(CloudPlanNotices.console(new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE,
                trial("active", CLAIM), null), null), "a running trial with nothing refused says nothing");
    }

    @Test
    void creditsAreUsedUpOnlyForARunningTrialThatHeardOrSawA402() {
        CloudPlanWatch.Snapshot running = snapshot(trial("active", CLAIM));
        assertFalse(CloudPlanNotices.creditsUsedUp(running, null));
        assertTrue(CloudPlanNotices.creditsUsedUp(running, ModerationApiResult.Kind.INSUFFICIENT_CREDITS),
                "402, then 401, then 402: the watch heard only the first of each kind");
        assertTrue(CloudPlanNotices.creditsUsedUp(new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ACTIVE,
                trial("active", CLAIM), ModerationApiResult.Kind.INSUFFICIENT_CREDITS), ModerationApiResult.Kind.CLIENT_AUTH));
        assertFalse(CloudPlanNotices.creditsUsedUp(snapshot(trial("expired", CLAIM)),
                ModerationApiResult.Kind.INSUFFICIENT_CREDITS), "an ended trial is not kept");
    }

    @Test
    void everySurfaceNamesThePlanLineTheSameWay() {
        CloudPlanWatch.Snapshot running = snapshot(trial("active", CLAIM));
        assertEquals("trial", CloudPlanNotices.planLine(running, null, NOW).name());
        assertEquals("trial-credits", CloudPlanNotices.planLine(running, ModerationApiResult.Kind.INSUFFICIENT_CREDITS, NOW)
                .name(), "a last check refused for credits is enough");
        assertEquals("trial-ended", CloudPlanNotices.planLine(snapshot(trial("expired", CLAIM)), null, NOW).name());
        assertEquals("trial-revoked", CloudPlanNotices.planLine(snapshot(trial("revoked", CLAIM)), null, NOW).name());
        assertEquals(CloudRecovery.SIGNUP_URL, CloudPlanNotices.planLine(snapshot(trial("expired", CLAIM)), null, NOW)
                .values().get("url"), "an ended trial's way on is a new account, never its claim link");
        assertEquals("standard", CloudPlanNotices.planLine(snapshot(
                new TrialClient.TrialStatusResult(false, "standard_workspace", "", 0, "https://billing")), null, NOW).name());
        assertNull(CloudPlanNotices.planLine(CloudPlanWatch.Snapshot.UNKNOWN, null, NOW));
        assertNull(CloudPlanNotices.planLine(new CloudPlanWatch.Snapshot(CloudPlanWatch.State.KEY_REJECTED, null, null),
                null, NOW));
    }
}
