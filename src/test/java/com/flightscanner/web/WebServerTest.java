package com.flightscanner.web;

import com.flightscanner.adsb.ADSBStats;
import com.flightscanner.config.ConfigManager;
import com.flightscanner.model.Flight;
import com.flightscanner.notification.DiscordStats;
import com.flightscanner.repository.FlightRepository;
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
                List::of,
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
    void homePageHasNoAutoRefresh() throws Exception {
        String body = get("/").body();
        assertFalse(body.contains("http-equiv=\"refresh\""), "HTML must not auto-refresh (map uses JS polling)");
    }

    @Test @Order(18)
    void homePageContainsTable() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("<table"), "HTML must contain a table");
        assertTrue(body.contains("Flight</th>"), "table must have Flight column");
        assertTrue(body.contains("Aircraft</th>"), "table must have Aircraft column");
    }

    @Test @Order(19)
    void homePageContainsSearchUI() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("id=\"flights-tbody\""), "page must have flights table body");
        assertTrue(body.contains("id=\"search-controls\""), "page must have search controls");
        assertTrue(body.contains("id=\"flights-table\""), "page must have flights table");
        // flights are loaded client-side; RYR123 must not be in the initial HTML shell
        assertFalse(body.contains("RYR123"), "flights must not be server-rendered in the HTML shell");
    }

    @Test @Order(20)
    void homePageContainsSidebarLinks() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("refreshTime"), "page must embed refresh timestamp in Alpine state");
        assertTrue(body.contains("/api/flights"), "sidebar must link to JSON API");
        assertTrue(body.contains("/metrics"), "sidebar must link to metrics");
    }

    @Test @Order(21)
    void homePageContainsLeafletMap() throws Exception {
        String body = get("/").body();
        assertTrue(body.contains("leaflet"), "HTML must include Leaflet.js map");
        assertTrue(body.contains("id=\"map\""), "HTML must have map div");
    }

    // ── /health ──────────────────────────────────────────────────────────────

    @Test @Order(22)
    void healthReturns200() throws Exception {
        assertEquals(200, get("/health").statusCode());
    }

    @Test @Order(23)
    void healthContentTypeIsJson() throws Exception {
        String ct = get("/health").headers().firstValue("Content-Type").orElse("");
        assertEquals("application/json", ct);
    }

    @Test @Order(24)
    void healthBodyHasRequiredFields() throws Exception {
        String body = get("/health").body();
        assertAll(
                () -> assertTrue(body.contains("\"status\":\"ok\""),       "missing status:ok"),
                () -> assertTrue(body.contains("\"uptime_s\":"),           "missing uptime_s"),
                () -> assertTrue(body.contains("\"connected\":"),          "missing connected"),
                () -> assertTrue(body.contains("\"aircraft_tracked\":"),   "missing aircraft_tracked")
        );
    }

    // ── /api/live ────────────────────────────────────────────────────────────

    @Test @Order(25)
    void apiLiveReturns200() throws Exception {
        assertEquals(200, get("/api/live").statusCode());
    }

    @Test @Order(26)
    void apiLiveContentTypeIsJson() throws Exception {
        String ct = get("/api/live").headers().firstValue("Content-Type").orElse("");
        assertEquals("application/json", ct);
    }

    @Test @Order(27)
    void apiLiveResponseHasCountField() throws Exception {
        String body = get("/api/live").body();
        assertTrue(body.contains("\"count\":"), "response must have count field");
    }

    // ── /api/flights?from=&to= ───────────────────────────────────────────────

    @Test @Order(28)
    void apiFlightsFromToReturns200() throws Exception {
        String from = LocalDate.now().minusDays(7).toString();
        String to   = LocalDate.now().toString();
        assertEquals(200, get("/api/flights?from=" + from + "&to=" + to).statusCode());
    }

    @Test @Order(29)
    void apiFlightsFromToResponseHasFromToFields() throws Exception {
        String from = LocalDate.now().minusDays(7).toString();
        String to   = LocalDate.now().toString();
        String body = get("/api/flights?from=" + from + "&to=" + to).body();
        assertTrue(body.contains("\"from\":"), "response must have from field");
        assertTrue(body.contains("\"to\":"),   "response must have to field");
        assertTrue(body.contains("\"count\":"), "response must have count field");
    }

    @Test @Order(30)
    void apiFlightsFromToFlightsHaveTierField() throws Exception {
        String from = LocalDate.now().minusDays(7).toString();
        String to   = LocalDate.now().toString();
        String body = get("/api/flights?from=" + from + "&to=" + to + "&tier=all").body();
        // stub returns all test flights; at least one should carry a tier value
        assertTrue(body.contains("\"tier\":"), "each flight must include computed tier");
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
        @Override public List<Flight> findByDateRange(LocalDateTime from, LocalDateTime to) { return data; }
        @Override public void close() {}
    }
}
