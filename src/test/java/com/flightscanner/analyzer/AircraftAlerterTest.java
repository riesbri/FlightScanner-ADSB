package com.flightscanner.analyzer;

import com.flightscanner.model.Flight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AircraftAlerterTest {

    private AircraftAlerter alerter;

    @BeforeEach
    void setUp() {
        alerter = new AircraftAlerter(
                Set.of("7500", "7600", "7700"),
                1500,
                List.of(new int[]{0x348000, 0x34FFFF}),
                List.of("usaf", "royal air force")
        );
    }

    /** Build a Flight with only the alert-relevant fields set. */
    private Flight flight(String squawk, Integer altitude, String hexIdent, String operator) {
        return new Flight("TST1", "XXX", "B738", LocalDateTime.now(), altitude, 300,
                squawk, hexIdent, operator);
    }

    // ── Emergency squawk ────────────────────────────────────────────

    @Test
    void emergencySquawk7700Triggers() {
        assertTrue(alerter.isAlert(flight("7700", 35000, "ABC123", null)));
    }

    @Test
    void emergencySquawk7500Triggers() {
        assertTrue(alerter.isAlert(flight("7500", 35000, "ABC123", null)));
    }

    @Test
    void nonEmergencySquawkDoesNotTrigger() {
        assertFalse(alerter.isAlert(flight("1234", 35000, "ABC123", null)));
    }

    @Test
    void nullSquawkDoesNotTrigger() {
        assertFalse(alerter.isAlert(flight(null, 35000, "ABC123", null)));
    }

    // ── Low altitude ────────────────────────────────────────────────

    @Test
    void belowThresholdTriggers() {
        assertTrue(alerter.isAlert(flight(null, 500, "ABC123", null)));
    }

    @Test
    void atThresholdDoesNotTrigger() {
        // threshold is 1500 (strict less-than)
        assertFalse(alerter.isAlert(flight(null, 1500, "ABC123", null)));
    }

    @Test
    void normalCruiseAltitudeDoesNotTrigger() {
        assertFalse(alerter.isAlert(flight(null, 35000, "ABC123", null)));
    }

    @Test
    void groundLevelZeroDoesNotTrigger() {
        // altitude == 0 is treated as "on ground / unknown" — not an alert
        assertFalse(alerter.isAlert(flight(null, 0, "ABC123", null)));
    }

    // ── Government hex range ────────────────────────────────────────

    @Test
    void hexInsideGovRangeTriggers() {
        // 0x348001 is within [0x348000, 0x34FFFF]
        assertTrue(alerter.isAlert(flight(null, 35000, "348001", null)));
    }

    @Test
    void hexExactlyAtLowBoundTriggers() {
        assertTrue(alerter.isAlert(flight(null, 35000, "348000", null)));
    }

    @Test
    void hexExactlyAtHighBoundTriggers() {
        assertTrue(alerter.isAlert(flight(null, 35000, "34FFFF", null)));
    }

    @Test
    void hexOutsideGovRangeDoesNotTrigger() {
        // 0x4CA001 is well above 0x34FFFF
        assertFalse(alerter.isAlert(flight(null, 35000, "4CA001", null)));
    }

    @Test
    void nullHexDoesNotTrigger() {
        assertFalse(alerter.isAlert(flight(null, 35000, null, null)));
    }

    // ── Military operator ───────────────────────────────────────────

    @Test
    void militaryOperatorSubstringTriggers() {
        assertTrue(alerter.isAlert(flight(null, 35000, "ABC123", "USAF Transport Wing")));
    }

    @Test
    void caseInsensitiveMatchTriggers() {
        assertTrue(alerter.isAlert(flight(null, 35000, "ABC123", "Royal Air Force")));
    }

    @Test
    void civilOperatorDoesNotTrigger() {
        assertFalse(alerter.isAlert(flight(null, 35000, "ABC123", "American Airlines")));
    }

    @Test
    void nullOperatorDoesNotTrigger() {
        assertFalse(alerter.isAlert(flight(null, 35000, "ABC123", null)));
    }

    // ── No triggers ─────────────────────────────────────────────────

    @Test
    void allNullFieldsDoNotTrigger() {
        assertFalse(alerter.isAlert(flight(null, null, null, null)));
    }
}
