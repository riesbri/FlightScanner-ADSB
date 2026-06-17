package com.flightscanner.analyzer;

import com.flightscanner.model.Flight;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class LocalAircraftAnalyzerTest {

    private static LocalAircraftAnalyzer analyzer;

    @BeforeAll
    static void setUp() {
        analyzer = new LocalAircraftAnalyzer();
    }

    // ── Type-based classification ────────────────────────────────────

    @Test
    void detectsWidebody() {
        assertTrue(analyzer.isWidebody("B777-300ER"));
    }

    @Test
    void detectsMilitary() {
        assertTrue(analyzer.isMilitary("F16"));
    }

    @Test
    void detectsBizjet() {
        assertTrue(analyzer.isBizjet("GLEX"));
    }

    @Test
    void interestingForEachSpecialCategory() {
        assertTrue(analyzer.isInteresting("B777-300ER"));
        assertTrue(analyzer.isInteresting("F16"));
        assertTrue(analyzer.isInteresting("GLEX"));
    }

    @Test
    void narrowbodyIsNotInteresting() {
        assertFalse(analyzer.isInteresting("B738"));
    }

    // ── ALERT detection ──────────────────────────────────────────────

    @Test
    void isAlertForEmergencySquawk() {
        Flight f = new Flight("AAL1", "JFK", "B738", LocalDateTime.now(), 20000, 400,
                "7700", "A12345", null);
        assertTrue(analyzer.isAlert(f));
    }

    @Test
    void isAlertForLowAltitude() {
        // 500 ft is below the default 1500 ft threshold
        Flight f = new Flight("EIN2", "DUB", "A320", LocalDateTime.now(), 500, 150,
                null, "4CA001", null);
        assertTrue(analyzer.isAlert(f));
    }

    @Test
    void normalFlightIsNotAlert() {
        // Normal cruise: no emergency squawk, high altitude, civil hex, no military operator
        Flight f = new Flight("RYR1", "DUB", "B738", LocalDateTime.now(), 35000, 450,
                "1234", "4CA2D6", "Ryanair");
        assertFalse(analyzer.isAlert(f));
    }

    @Test
    void isAlertFlightIsAlsoInteresting() {
        // ALERT implies interesting (alert || isInteresting(type))
        Flight f = new Flight("AAL1", "JFK", "B738", LocalDateTime.now(), 20000, 400,
                "7500", "A12345", null);
        assertTrue(analyzer.isAlert(f));
        // isInteresting(String) still works for type-based check
        assertTrue(analyzer.isInteresting("B777-300ER"));
    }

    // ── analyzeFlights (used by legacy FlightTrackerApp scraper) ────

    @Test
    void analyzeFlightsReturnsOnlyInteresting() {
        Flight widebody = new Flight("BA001", "LHR", "B77W", LocalDateTime.now());
        Flight narrowbody = new Flight("EZY123", "STN", "A320", LocalDateTime.now());
        Flight military = new Flight("RFR01", "MAD", "C130", LocalDateTime.now());

        List<Flight> result = analyzer.analyzeFlights(List.of(widebody, narrowbody, military));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(f -> f.flightNumber().equals("BA001")));
        assertTrue(result.stream().anyMatch(f -> f.flightNumber().equals("RFR01")));
        assertFalse(result.stream().anyMatch(f -> f.flightNumber().equals("EZY123")));
    }

    @Test
    void analyzeFlightsEmptyListReturnsEmpty() {
        assertTrue(analyzer.analyzeFlights(List.of()).isEmpty());
    }
}
