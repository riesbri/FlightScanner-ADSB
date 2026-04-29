package com.richi.service;

import com.richi.config.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;

/**
 * Enriches aircraft data with type/model info from adsb.lol API.
 * Results are cached in SQLite so each hex is looked up only once.
 */
@Slf4j
public class AircraftEnrichmentService implements AutoCloseable {

    private static final String ADSBLOL_API = "https://api.adsb.lol/v2/icao/%s";
    private static final long MIN_API_INTERVAL_MS = 30_000; // 30 seconds between calls

    private final Connection db;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final boolean enabled;
    private Instant lastApiCall = Instant.EPOCH;

    public AircraftEnrichmentService() throws SQLException {
        this(ConfigManager.getInstance());
    }

    public AircraftEnrichmentService(ConfigManager config) throws SQLException {
        this.enabled = true;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.json = new ObjectMapper();

        // Use a separate SQLite file for the cache
        String dbUrl = config.getDbUrl();
        String cachePath = dbUrl.replace("jdbc:sqlite:", "").replace("flights.db", "aircraft_cache.db");
        if (cachePath.equals(dbUrl)) cachePath = "aircraft_cache.db";

        this.db = DriverManager.getConnection("jdbc:sqlite:" + cachePath);
        initCache();
        log.info("Aircraft enrichment service initialized (cache: {})", cachePath);
    }

    private void initCache() throws SQLException {
        try (Statement stmt = db.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS aircraft_types (
                        icao_hex TEXT PRIMARY KEY,
                        icao_type TEXT,
                        description TEXT,
                        registration TEXT,
                        operator TEXT,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    /**
     * Look up aircraft type for a hex code. Returns cached value if available,
     * otherwise queries the adsb.lol API (rate-limited).
     */
    public AircraftInfo lookup(String hexIdent) {
        if (hexIdent == null || hexIdent.isEmpty()) {
            return AircraftInfo.UNKNOWN;
        }

        hexIdent = hexIdent.toUpperCase().trim();

        // Check cache first
        AircraftInfo cached = getCached(hexIdent);
        if (cached != null) {
            return cached;
        }

        // Query API (with rate limiting)
        AircraftInfo info = queryApi(hexIdent);
        if (info != null) {
            cacheResult(hexIdent, info);
        }
        return info != null ? info : AircraftInfo.UNKNOWN;
    }

    private AircraftInfo getCached(String hexIdent) {
        String sql = "SELECT icao_type, description, registration, operator FROM aircraft_types WHERE icao_hex = ?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, hexIdent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new AircraftInfo(
                            rs.getString("icao_type"),
                            rs.getString("description"),
                            rs.getString("registration"),
                            rs.getString("operator")
                    );
                }
            }
        } catch (SQLException e) {
            log.warn("Cache lookup failed for {}: {}", hexIdent, e.getMessage());
        }
        return null;
    }

    private void cacheResult(String hexIdent, AircraftInfo info) {
        String sql = "INSERT OR REPLACE INTO aircraft_types (icao_hex, icao_type, description, registration, operator, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, hexIdent);
            ps.setString(2, info.icaoType() != null ? info.icaoType() : "");
            ps.setString(3, info.description() != null ? info.description() : "");
            ps.setString(4, info.registration() != null ? info.registration() : "");
            ps.setString(5, info.operator() != null ? info.operator() : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to cache result for {}: {}", hexIdent, e.getMessage());
        }
    }

    private AircraftInfo queryApi(String hexIdent) {
        // Rate limiting
        long waitMs = MIN_API_INTERVAL_MS - Duration.between(lastApiCall, Instant.now()).toMillis();
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        lastApiCall = Instant.now();
        String url = String.format(ADSBLOL_API, hexIdent.toLowerCase());

        try {
            log.debug("Querying adsb.lol for {}", hexIdent);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "FlightScanner/1.0")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = json.readTree(response.body());
                JsonNode acArray = root.get("ac");
                if (acArray != null && acArray.isArray() && acArray.size() > 0) {
                    JsonNode ac = acArray.get(0);
                    return new AircraftInfo(
                            ac.has("t") ? ac.get("t").asText().toUpperCase() : "",
                            ac.has("desc") ? ac.get("desc").asText() : "",
                            ac.has("r") ? ac.get("r").asText() : "",
                            ac.has("ownop") ? ac.get("ownop").asText() : ""
                    );
                }
            } else if (response.statusCode() == 429) {
                log.warn("adsb.lol rate limited for {}", hexIdent);
                lastApiCall = Instant.now().plusSeconds(60); // Cool down
            } else {
                log.warn("adsb.lol API returned {} for {}", response.statusCode(), hexIdent);
            }

        } catch (Exception e) {
            log.warn("adsb.lol API error for {}: {}", hexIdent, e.getMessage());
        }

        return null;
    }

    @Override
    public void close() {
        try {
            if (db != null && !db.isClosed()) {
                db.close();
            }
        } catch (SQLException e) {
            log.warn("Error closing enrichment DB: {}", e.getMessage());
        }
    }

    /**
     * Immutable record holding enriched aircraft information.
     */
    public record AircraftInfo(String icaoType, String description, String registration, String operator) {
        public static final AircraftInfo UNKNOWN = new AircraftInfo("", "", "", "");

        public boolean isKnown() {
            return icaoType != null && !icaoType.isEmpty();
        }
    }
}
