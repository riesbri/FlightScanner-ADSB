package com.richi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ADSBFlightTrackerDryRunTest {

    // ── parseArgs ────────────────────────────────────────────────────

    @Test
    void noArgsIsNormalMode() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{});
        assertFalse(a.dryRun());
        assertFalse(a.help());
        assertEquals(0, a.seconds());
    }

    @Test
    void helpFlagShort() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{"-h"});
        assertTrue(a.help());
        assertFalse(a.dryRun());
    }

    @Test
    void helpFlagLong() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{"--help"});
        assertTrue(a.help());
        assertFalse(a.dryRun());
    }

    @Test
    void dryRunWithoutSeconds() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{"--dry-run"});
        assertTrue(a.dryRun());
        assertFalse(a.help());
        assertEquals(0, a.seconds()); // caller falls back to config key default
    }

    @Test
    void dryRunWithExplicitSeconds() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{"--dry-run", "120"});
        assertTrue(a.dryRun());
        assertEquals(120, a.seconds());
    }

    @Test
    void dryRunWithNonNumericNextArgDoesNotConsume() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{"--dry-run", "--help"});
        assertTrue(a.dryRun());
        assertTrue(a.help());
        assertEquals(0, a.seconds());
    }

    @Test
    void unknownFlagsAreIgnored() {
        ADSBFlightTracker.Args a = ADSBFlightTracker.parseArgs(new String[]{"--verbose", "--dry-run", "30"});
        assertTrue(a.dryRun());
        assertEquals(30, a.seconds());
        assertFalse(a.help());
    }
}
