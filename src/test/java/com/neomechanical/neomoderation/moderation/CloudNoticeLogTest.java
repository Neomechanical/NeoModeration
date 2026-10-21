package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CloudNoticeLogTest {

    private static final CloudPlanWatch.Snapshot ENDED = new CloudPlanWatch.Snapshot(CloudPlanWatch.State.TRIAL_ENDED,
            new TrialClient.TrialStatusResult(true, "expired", "2026-10-22T00:00:00Z", 0, "https://signup"), null);
    private static final CloudPlanWatch.Snapshot STANDARD = new CloudPlanWatch.Snapshot(CloudPlanWatch.State.STANDARD,
            new TrialClient.TrialStatusResult(false, "standard_workspace", "", 0, "https://billing"), null);

    @Test
    void eachNoticeIsToldOnceForAKey() {
        CloudNoticeLog log = new CloudNoticeLog();
        assertEquals("cloud-plan.console-trial-ended", log.next(ENDED, null).key(), "said at startup");
        assertNull(log.next(ENDED, ModerationApiResult.Kind.CLIENT_AUTH), "the refusal says nothing new");
        assertNull(log.next(ENDED, null), "nor does the next refresh");
        assertNull(log.next(STANDARD, null), "a working key needs no line");
    }

    @Test
    void aKeyThatWorksAgainHasItsNextRefusalToldAgain() {
        CloudNoticeLog log = new CloudNoticeLog();
        assertEquals("cloud-plan.console-credits", log.next(STANDARD, ModerationApiResult.Kind.INSUFFICIENT_CREDITS).key());
        assertNull(log.next(STANDARD, ModerationApiResult.Kind.INSUFFICIENT_CREDITS));
        log.forget(); // credits were added and a check worked
        assertEquals("cloud-plan.console-credits", log.next(STANDARD, ModerationApiResult.Kind.INSUFFICIENT_CREDITS).key(),
                "an empty wallet again, later in the same run");
    }
}
