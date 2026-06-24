package com.flightscanner.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AirportCoordsTest {

    private static final double NM_TOLERANCE = 5.0; // ±5 nm tolerance for approximate coords

    // ── Haversine accuracy ───────────────────────────────────────────

    @Test
    void vlcToVlcIsZero() {
        double d = AirportCoords.haversineNm(39.4893, -0.4815, 39.4893, -0.4815);
        assertEquals(0.0, d, 0.001);
    }

    @Test
    void vlcToMadIsApproximately154nm() {
        // Valencia to Madrid-Barajas ≈ 154 nm
        double d = AirportCoords.haversineNm(39.4893, -0.4815, 40.4936, -3.5668);
        assertEquals(154.0, d, NM_TOLERANCE);
    }

    @Test
    void vlcToBcnIsApproximately160nm() {
        // Valencia to Barcelona El Prat ≈ 160 nm
        double d = AirportCoords.haversineNm(39.4893, -0.4815, 41.2971, 2.0785);
        assertEquals(160.0, d, NM_TOLERANCE);
    }

    @Test
    void haversineIsSymmetric() {
        double ab = AirportCoords.haversineNm(39.4893, -0.4815, 40.4936, -3.5668);
        double ba = AirportCoords.haversineNm(40.4936, -3.5668, 39.4893, -0.4815);
        assertEquals(ab, ba, 0.001);
    }

    // ── Resolve fallback chain ───────────────────────────────────────

    @Test
    void resolveReturnsTableEntryForMad() {
        // ConfigManager default has airport.code=MAD, no lat/lon overrides
        AirportCoords coords = AirportCoords.resolve(com.flightscanner.config.ConfigManager.getInstance());
        assertNotNull(coords);
        assertEquals("MAD", coords.code());
        assertEquals(40.4936, coords.lat(), 0.001);
        assertEquals(-3.5668, coords.lon(), 0.001);
        com.flightscanner.config.ConfigManager.reset();
    }

    @Test
    void resolveReturnsNullForUnknownCode() {
        // Directly test the table lookup for an unknown code
        assertNull(AirportCoords.SPANISH_AIRPORTS.get("XYZ"));
    }

    // ── Spanish airports table integrity ────────────────────────────

    @Test
    void spanishAirportsTableHasAllExpectedEntries() {
        String[] expected = {
            "VLC", "MAD", "BCN", "SVQ", "BIO", "AGP", "ALC", "IBZ", "PMI", "MAH",
            "TFS", "LPA", "FUE", "ACE", "REU", "OVD", "SDR", "PNA", "ZAZ", "LEI",
            "GRX", "BJZ", "VIT", "EAS", "MJV", "RMU"
        };
        for (String code : expected) {
            AirportCoords entry = AirportCoords.SPANISH_AIRPORTS.get(code);
            assertNotNull(entry, "Missing entry for " + code);
            assertTrue(Math.abs(entry.lat()) <= 90,  "Bad lat for " + code);
            assertTrue(Math.abs(entry.lon()) <= 180, "Bad lon for " + code);
        }
    }
}
