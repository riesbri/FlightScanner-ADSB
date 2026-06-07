package com.richi;

import com.richi.geo.AirportCoords;
import com.richi.model.Flight;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the in/out decision logic applied by ADSBFlightTracker.onAircraftDetected.
 * Uses AirportCoords.haversineNm directly so no live connection is needed.
 */
class AirportFilterTest {

    private static final AirportCoords VLC = AirportCoords.SPANISH_AIRPORTS.get("VLC");
    private static final double RADIUS_NM = 100.0;

    private boolean outOfRange(Flight flight) {
        if (flight.latitude() != null && flight.longitude() != null) {
            return AirportCoords.haversineNm(VLC.lat(), VLC.lon(),
                    flight.latitude(), flight.longitude()) > RADIUS_NM;
        }
        return false; // requirePosition=false default
    }

    private Flight flight(Double lat, Double lon) {
        return new Flight("TST1", "VLC", "B738", LocalDateTime.now(),
                35000, 450, "1234", "4CA001", "TestAir", lat, lon);
    }

    @Test
    void flightNearAirportIsWithinRange() {
        // Valencia city ~1 nm from VLC airport
        assertFalse(outOfRange(flight(39.47, -0.38)));
    }

    @Test
    void flightAtMadridIsOutOfRange() {
        // MAD is ~270 nm from VLC, well beyond 100 nm radius
        assertTrue(outOfRange(flight(40.4936, -3.5668)));
    }

    @Test
    void flightWithNoPositionPassesThroughByDefault() {
        // requirePosition=false (default): null lat/lon is never dropped
        assertFalse(outOfRange(flight(null, null)));
    }

    @Test
    void flightExactlyAtAirportIsWithinRange() {
        assertFalse(outOfRange(flight(VLC.lat(), VLC.lon())));
    }
}
