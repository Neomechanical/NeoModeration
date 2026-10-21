package com.neomechanical.neomoderation.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrialPromptSupportTest {

    @Test
    void aButtonsHoverNamesTheSiteNeverTheClaimLink() {
        // The claim link keeps the trial for whoever opens it first, and a tooltip ends up in screenshots.
        assertEquals("neomechanical.com",
                TrialPromptSupport.siteOf("https://neomechanical.com/signup?trial=t.sig&src=neomoderation_trial"));
        assertEquals("not a link", TrialPromptSupport.siteOf("not a link"));
    }

    @Test
    void whatTheCloudCaughtCountsTheWholeTrialWhenItCanAndSaysNothingAtZero() {
        assertEquals(new TrialPromptSupport.Caught(12, true), TrialPromptSupport.caught(12, 3));
        assertEquals(new TrialPromptSupport.Caught(3, false), TrialPromptSupport.caught(-1, 3),
                "without the case history only this run is known");
        assertNull(TrialPromptSupport.caught(0, 5), "nothing caught over the trial says nothing, whatever this run saw");
        assertNull(TrialPromptSupport.caught(-1, 0));
    }
}
