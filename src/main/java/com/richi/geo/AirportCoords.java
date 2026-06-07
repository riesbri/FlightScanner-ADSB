package com.richi.geo;

import com.richi.config.ConfigManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Airport coordinates with haversine distance helper.
 *
 * Coordinates resolve order:
 *   1. airport.coordinates.lat + airport.coordinates.lon in config
 *   2. Hardcoded SPANISH_AIRPORTS table keyed on airport.code
 *   3. null  →  proximity filter disabled at call site
 */
@Slf4j
public record AirportCoords(String code, double lat, double lon) {

    // Earth radius in nautical miles (mean radius 6371 km → 3440.065 nm)
    private static final double R_NM = 3440.065;

    /** Great-circle distance in nautical miles between two lat/lon points. */
    public static double haversineNm(double lat1, double lon1, double lat2, double lon2) {
        double dlat = Math.toRadians(lat2 - lat1);
        double dlon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dlat / 2) * Math.sin(dlat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dlon / 2) * Math.sin(dlon / 2);
        return 2 * R_NM * Math.asin(Math.sqrt(a));
    }

    /** Hardcoded IATA → (lat, lon) for common Spanish airports. */
    public static final Map<String, AirportCoords> SPANISH_AIRPORTS = Map.ofEntries(
        Map.entry("VLC", new AirportCoords("VLC",  39.4893,  -0.4815)),
        Map.entry("MAD", new AirportCoords("MAD",  40.4936,  -3.5668)),
        Map.entry("BCN", new AirportCoords("BCN",  41.2971,   2.0785)),
        Map.entry("SVQ", new AirportCoords("SVQ",  37.4180,  -5.8931)),
        Map.entry("BIO", new AirportCoords("BIO",  43.3011,  -2.9106)),
        Map.entry("AGP", new AirportCoords("AGP",  36.6749,  -4.4991)),
        Map.entry("ALC", new AirportCoords("ALC",  38.2822,  -0.5582)),
        Map.entry("IBZ", new AirportCoords("IBZ",  38.8729,   1.3731)),
        Map.entry("PMI", new AirportCoords("PMI",  39.5517,   2.7388)),
        Map.entry("MAH", new AirportCoords("MAH",  39.8626,   4.2185)),
        Map.entry("TFS", new AirportCoords("TFS",  28.0445, -16.5725)),
        Map.entry("LPA", new AirportCoords("LPA",  27.9319, -15.3866)),
        Map.entry("FUE", new AirportCoords("FUE",  28.4527, -13.8638)),
        Map.entry("ACE", new AirportCoords("ACE",  28.9455, -13.6052)),
        Map.entry("REU", new AirportCoords("REU",  41.1474,   1.1672)),
        Map.entry("OVD", new AirportCoords("OVD",  43.5636,  -6.0346)),
        Map.entry("SDR", new AirportCoords("SDR",  43.4271,  -3.8200)),
        Map.entry("PNA", new AirportCoords("PNA",  42.7700,  -1.6463)),
        Map.entry("ZAZ", new AirportCoords("ZAZ",  41.6663,  -1.0415)),
        Map.entry("LEI", new AirportCoords("LEI",  36.8439,  -2.3701)),
        Map.entry("GRX", new AirportCoords("GRX",  37.1887,  -3.7774)),
        Map.entry("BJZ", new AirportCoords("BJZ",  38.8913,  -6.8213)),
        Map.entry("VIT", new AirportCoords("VIT",  42.8828,  -2.7245)),
        Map.entry("EAS", new AirportCoords("EAS",  43.3565,  -1.7906)),
        Map.entry("MJV", new AirportCoords("MJV",  37.7750,  -0.8124)),
        Map.entry("RMU", new AirportCoords("RMU",  37.8034,  -1.1252))
    );

    /**
     * Resolve airport coordinates from config, falling back to the hardcoded table.
     * Returns null if the airport is unknown and no lat/lon is configured — the caller
     * should disable the proximity filter in that case.
     */
    public static AirportCoords resolve(ConfigManager config) {
        String code = config.getString("airport.code", "VLC").toUpperCase().trim();
        String latStr = config.getString("airport.coordinates.lat", "").trim();
        String lonStr = config.getString("airport.coordinates.lon", "").trim();

        if (!latStr.isEmpty() && !lonStr.isEmpty()) {
            try {
                double lat = Double.parseDouble(latStr);
                double lon = Double.parseDouble(lonStr);
                log.info("Airport filter: using configured coordinates for {} ({}, {})", code, lat, lon);
                return new AirportCoords(code, lat, lon);
            } catch (NumberFormatException e) {
                log.warn("Invalid airport.coordinates in config (lat='{}', lon='{}'), falling back to table",
                        latStr, lonStr);
            }
        }

        AirportCoords known = SPANISH_AIRPORTS.get(code);
        if (known != null) {
            log.info("Airport filter: using table coordinates for {} ({}, {})", code, known.lat(), known.lon());
            return known;
        }

        log.warn("Airport '{}' not in table and no lat/lon configured — proximity filter disabled", code);
        return null;
    }
}
