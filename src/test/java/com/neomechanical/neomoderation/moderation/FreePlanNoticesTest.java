package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The free plan's offers on an admin's join, and its plan lines. */
class FreePlanNoticesTest {

    private static final long NOW = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli();

    private static TrialClient.TrialStatusResult trial(String status, String end, boolean open) {
        return new TrialClient.TrialStatusResult(true, status, end, 0, "https://claim", "", null, open);
    }

    private static CloudPlanWatch.Snapshot snapshot(TrialClient.TrialStatusResult status) {
        return new CloudPlanWatch.Snapshot(CloudPlanWatch.classify(status), status, null);
    }

    private static List<String> keys(List<CloudPlanNotices.Notice> notices) {
        return notices.stream().map(CloudPlanNotices.Notice::key).toList();
    }

    @Test
    void aRunningTrialIsOfferedTheMoveFirstAndStillHearsItIsEnding() {
        CloudPlanWatch.Snapshot ending = snapshot(trial("active", "2026-11-03T12:00:00Z", true));
        List<CloudPlanNotices.Notice> notices = CloudPlanNotices.joinNotices(ending, NOW);
        assertEquals(List.of("cloud-plan.join-free-offer", "cloud-plan.join-trial-ending"), keys(notices));
        assertEquals("/nmod trial free", notices.get(0).url());
        assertTrue(CloudPlanNotices.runsCommand(notices.get(0)));
        assertEquals("trial.free-offer-button", CloudPlanNotices.joinButton(notices.get(0)));
        assertFalse(CloudPlanNotices.runsCommand(notices.get(1)), "the keep button opens the claim link");
    }

    @Test
    void anEndedTrialIsOfferedTheWayBackOnceButNeverARevokedOne() {
        CloudPlanWatch.Snapshot ended = snapshot(trial("expired", "2026-11-01T00:00:00Z", true));
        List<CloudPlanNotices.Notice> notices = CloudPlanNotices.joinNotices(ended, NOW);
        assertEquals(List.of("cloud-plan.join-come-back", "cloud-plan.join-trial-ended"), keys(notices));
        assertEquals("/nmod trial start", notices.get(0).url());

        CloudPlanWatch.Snapshot revoked = snapshot(trial("revoked", "2026-11-09T00:00:00Z", true));
        assertEquals(List.of("cloud-plan.join-trial-revoked"), keys(CloudPlanNotices.joinNotices(revoked, NOW)));
    }

    @Test
    void noOfferWhileThePlanIsNotOpenToThisInstall() {
        CloudPlanWatch.Snapshot running = snapshot(trial("active", "2026-11-12T00:00:00Z", false));
        assertEquals(List.of(), keys(CloudPlanNotices.joinNotices(running, NOW)));
    }

    @Test
    void theChartReadsTheMarkersTheOffersWrite() {
        assertEquals(CloudPlanNotices.FREE_OFFER_MARKER,
                CloudPlanNotices.joinMarker(new CloudPlanNotices.Notice(CloudPlanNotices.FREE_OFFER, "/x", "")));
        assertEquals(CloudPlanNotices.COME_BACK_MARKER,
                CloudPlanNotices.joinMarker(new CloudPlanNotices.Notice(CloudPlanNotices.COME_BACK, "/x", "")));
    }

    @Test
    void aFreePlansLineSaysWhetherItsDayIsUsedUp() {
        FreePlan open = new FreePlan(2000, 12, 300, 0, "2026-11-03T00:00:00Z", false, false);
        TrialClient.TrialStatusResult status =
                new TrialClient.TrialStatusResult(false, "free_plan", "", 0, "", "", open, false);
        CloudPlanWatch.Snapshot plan = snapshot(status);
        assertEquals("free", CloudPlanNotices.planLine(plan, ModerationApiResult.Kind.CLEAR, NOW).name());
        CloudPlanNotices.PlanLine capped = CloudPlanNotices.planLine(plan, ModerationApiResult.Kind.FREE_PLAN_CAPPED, NOW);
        assertEquals("free-capped", capped.name(), "a check refused for the cap counts before the service says so");
        assertEquals("00:00", capped.values().get("time"));
        assertNull(CloudPlanNotices.refusal(plan, ModerationApiResult.Kind.FREE_PLAN_CAPPED), "the cap is no refusal");
    }
}
