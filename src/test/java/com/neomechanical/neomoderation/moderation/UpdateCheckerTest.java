package com.neomechanical.neomoderation.moderation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version comparison for the startup update notice. A naive string compare gets
 * 1.10.0 vs 1.9.0 backwards, which would tell an up-to-date server it is behind
 * and, worse, tell a behind server it is current.
 */
class UpdateCheckerTest {

    @Test
    void recognisesAHigherPatch() {
        assertTrue(UpdateChecker.isNewer("1.5.1", "1.5.0"));
    }

    @Test
    void recognisesAHigherMinor() {
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.5.9"));
    }

    @Test
    void comparesSegmentsNumericallyNotAsText() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"));
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.10.0"));
    }

    @Test
    void theSameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.6.0", "1.6.0"));
    }

    @Test
    void anOlderReleaseIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.4.1", "1.5.1"));
    }

    @Test
    void treatsMissingTrailingSegmentsAsZero() {
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.0"));
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6"));
    }

    @Test
    void ignoresAPreReleaseSuffixWhenComparing() {
        assertFalse(UpdateChecker.isNewer("1.6.0-SNAPSHOT", "1.6.0"));
        assertTrue(UpdateChecker.isNewer("1.7.0-rc1", "1.6.0"));
    }

    @Test
    void stripsTheLeadingVFromAGitTag() {
        assertEquals("1.6.0", UpdateChecker.stripLeadingV("v1.6.0"));
        assertEquals("1.6.0", UpdateChecker.stripLeadingV("1.6.0"));
    }

    @Test
    void aMalformedVersionNeverReportsAnUpdate() {
        assertFalse(UpdateChecker.isNewer("not-a-version", "1.6.0"));
    }

    @Test
    void releasesComeFromTheRepositoryWhereItLivesNow() {
        // The old KyTDK address answers with a redirect, which read as "no update".
        assertTrue(UpdateChecker.RELEASES_API.contains("/repos/Neomechanical/NeoModeration/"));
        assertTrue(UpdateChecker.RELEASES_PAGE.startsWith("https://github.com/Neomechanical/NeoModeration/"));
    }
}
