package com.richi.web;

import com.richi.adsb.ADSBStats;
import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import com.richi.notification.DiscordStats;
import com.richi.repository.FlightRepository;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebServerTest {

    private static WebServer server;
    private static String baseUrl;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws Exception {
        ConfigManager.reset();
        ConfigManager config = ConfigManager.getInstance();

        List<Flight> testFlights = List.of(
                // Noteworthy (widebody)
                new Flight("RYR123", "DUB", "B77W",
                        LocalDateTime.now().minusHours(1), 35000, 450, null, null, "Ryanair"),
                // Noteworthy (bizjet)
                new Flight("GLF001", "LGW", "GLF6",
                        LocalDateTime.now().minusHours(2), 41000, 480, null, null, "Private"),
                // Commercial (filtered out by noteworthy)
                new Flight("VLG123", "BCN", "A320",
                        LocalDateTime.now().minusHours(1), 28000, 410, null, null, "Vueling"),
                // ALERT-tier (emergency squawk)
                new Flight("IBE999", "MAD", "A333",
                        LocalDateTime.now().minusMinutes(30), 1200, 200, "7700", "4CA1B2", "Iberia")
        );

        FlightRepository repo = new StubFlightRepository(testFlights);

        ADSBStats fixedStats = new ADSBStats(42, 12345, 87, Instant.now(), true, "localhost:30003");
        DiscordStats fixedDiscord = new DiscordStats(5, 1, 2, Instant.now());

        server = new WebServer(0, "localhost", repo,
                () -> fixedStats,
                () -> fixedDiscord,
                () -> 678L,
                config);
        server.start();
        baseUrl = "http://localhost:" + server.getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
        ConfigManager.reset();
    }

    // ── /metrics ────────────────────────────────────────────────────────────

    @Test @Order(1)
    void metricsReturns200() throws Exception {
        var resp = get("/metrics");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(2)
    void metricsContentTypeIsPrometheus() throws Exception {
        var resp = get("/metrics");
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.startsWith("text/plain"), "Expected text/plain, got: " + ct);
    }

    @Test @Order(3)
    void metricsContainsAllRequiredLines() throws Exception {
        String body = get("/metrics").body();
        assertAll(
                () -> assertTrue(body.contains("flightscanner_aircraft_tracked"), "missing aircraft_tracked"),
                () -> assertTrue(body.contains("flightscanner_messages_per_second"), "missing msg/sec"),
                () -> assertTrue(body.contains("flightscanner_connected"), "missing connected"),
                () -> assertTrue(body.contains("flightscanner_messages_received_total"), "missing messages_received"),
                () -> assertTrue(body.contains("flightscanner_flights_persisted_total"), "missing flights_persisted"),
                () -> assertTrue(body.contains("flightscanner_notifications_sent_total"), "missing notifications_sent"),
                () -> assertTrue(body.contains("flightscanner_alerts_sent_total"), "missing alerts_sent"),
                () -> assertTrue(body.contains("flightscanner_coalesced_pending"), "missing coalesced_pending")
        );
    }

    @Test @Order(4)
    void metricsHasHelpAndTypeLines() throws Exception {
        String body = get("/metrics").body();
        assertTrue(body.contains("# HELP"), "missing # HELP lines");
        assertTrue(body.contains("# TYPE"), "missing # TYPE lines");
    }

    @Test @Order(5)
    void metricsValuesMatchFixedStats() throws Exception {
        String body = get("/metrics").body();
        assertTrue(body.contains("flightscanner_aircraft_tracked 42"), "aircraft count mismatch");
        assertTrue(body.contains("flightscanner_connected 1"), "connected should be 1");
        assertTrue(body.contains("flightscanner_flights_persisted_total 678"), "persisted count mismatch");
        assertTrue(body.contains("flightscanner_notifications_sent_total 5"), "notifications mismatch");
        assertTrue(body.contains("flightscanner_alerts_sent_total 1"), "alerts mismatch");
        assertTrue(body.contains("flightscanner_coalesced_pending 2"), "coalesced mismatch");
    }

    // ── /api/flights ─────────────────────────────────────────────────────────

    @Test @Order(6)
    void apiFlightsReturns200() throws Exception {
        assertEquals(200, get("/api/flights").statusCode());
    }

    @Test @Order(7)
    void apiFlightsContentTypeIsJson() throws Exception {
        String ct = get("/api/flights").headers().firstValue("Content-Type").orElse("");
        assertEquals("application/json", ct);
    }

    @Test @Order(8)
    void apiFlightsDefaultTierIsNoteworthy() throws Exception {
        String body = get("/api/flights").body();
        assertTrue(body.contains("\"tier\":\"noteworthy\""), "default tier should be noteworthy");
    }

    @Test @Order(9)
    void apiFlightsNoteworthyFiltersCommercial() throws Exception {
        String body = get("/api/flights?tier=noteworthy").body();
        // B77W and GLF6 are noteworthy; A320 is commercial → must not appear
        assertTrue(body.contains("RYR123"), "B77W widebody should appear");
        assertTrue(body.contains("GLF001"), "bizjet should appear");
        assertFalse(body.contains("VLG123"), "commercial A320 should be filtered out");
    }

    @Test @Order(10)
    void apiFlightsTierAllReturnsEverything() throws Exception {
        String body = get("/api/flights?tier=all").body();
        assertTrue(body.contains("RYR123"), "B77W should appear");
        assertTrue(body.contains("VLG123"), "commercial should appear in tier=all");
    }

    @Test @Order(11)
    void apiFlightsTierAlertOnlyReturnsAlerts() throws Exception {
        String body = get("/api/flights?tier=alert").body();
        // IBE999 has squawk 7700 → alert; others don't
        assertTrue(body.contains("IBE999"), "7700 squawk should trigger ALERT");
        assertFalse(body.contains("RYR123"), "normal widebody should not appear in tier=alert");
    }

    @Test @Order(12)
    void apiFlightsResponseHasCountField() throws Exception {
        String body = get("/api/flights?tier=all").body();
        assertTrue(body.contains("\"count\":"), "response must have count field");
    }

    @Test @Order(13)
    void apiFlightsResponseHasSinceField() throws Exception {
        String body = get("/api/flights").body();
        assertTrue(body.contains("\"since\":"), "response must have since field");
    }

    @Test @Order(14)
    void apiFlightsSinceParam() throws Exception {
        String since = LocalDateTime.now().minusHours(3).toString();
        String body = get("/api/flights?since=" + since.replace(":", "%3A")).body();
        assertTrue(body.contains("\"since\":"), "response must have since field");
    }

    // ── / (HTML) ─────────────────────────────────────────────────────────────

    @Test @Order(15)
    void homePageReturns200() throws Exception {
        assertEquals(200, get("/").statusCode());
    }

    @Test @Order(16)
    void homePageContentTypeIsHtml() throws Exception {
        String ct = get("/").headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.startsWith("text/html"), "Expected text/html, got: " + ct);
    }

    @Test @Order(17)
    void homePageContainsRefreshMeta() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("http-equiv=\"refresh\""), "HTML must auto-refresh");
        assertTrue(body.contains("content=\"30\""), "refresh interval should be 30s");
    }

    @Test @Order(18)
    void homePageContainsTable() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("<table"), "HTML must contain a table");
        assertTrue(body.contains("<th>Flight</th>"), "table must have Flight column");
        assertTrue(body.contains("<th>Aircraft</th>"), "table must have Aircraft column");
    }

    @Test @Order(19)
    void homePageContainsNoteworthyFlights() throws Exception {
        String body = get("/").body();
        // B77W widebody should appear, commercial A320 should not
        assertTrue(body.contains("RYR123"), "widebody flight should appear in HTML");
        assertFalse(body.contains("VLG123"), "commercial flight should not appear in HTML");
    }

    @Test @Order(20)
    void homePageContainsFooterWithLink() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("Last refresh"), "footer should show last refresh time");
        assertTrue(body.contains("/api/flights"), "footer should link to JSON API");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── test double ──────────────────────────────────────────────────────────

    static class StubFlightRepository implements FlightRepository {
        private final List<Flight> data;

        StubFlightRepository(List<Flight> data) { this.data = data; }

        @Override public void initialize() {}
        @Override public boolean saveFlight(Flight f) { return false; }
        @Override public int saveFlights(List<Flight> f) { return 0; }
        @Override public boolean flightExists(String fn, String st) { return false; }
        @Override public Optional<Flight> findByFlightNumberAndTime(String fn, String st) { return Optional.empty(); }
        @Override public List<Flight> findByDate(LocalDate date) { return data; }
        @Override public List<Flight> findRecent(int hoursBack) { return data; }
        @Override public void close() {}
    }
}
