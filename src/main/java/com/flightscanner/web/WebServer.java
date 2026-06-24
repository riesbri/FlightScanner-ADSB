package com.flightscanner.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightscanner.adsb.ADSBStats;
import com.flightscanner.adsb.ADSBStatsProvider;
import com.flightscanner.analyzer.AircraftAlerter;
import com.flightscanner.analyzer.AircraftTypes;
import com.flightscanner.config.ConfigManager;
import com.flightscanner.geo.AirportCoords;
import com.flightscanner.geo.ICAOCountry;
import com.flightscanner.model.Flight;
import com.flightscanner.notification.DiscordStats;
import com.flightscanner.repository.FlightRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
public class WebServer implements AutoCloseable {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String PROM_CONTENT_TYPE = "text/plain; version=0.0.4";

    private final HttpServer server;
    private final FlightRepository repository;
    private final ADSBStatsProvider statsProvider;
    private final Supplier<DiscordStats> discordStatsSupplier;
    private final LongSupplier flightsPersistedSupplier;
    private final Supplier<List<Flight>> liveFlightsSupplier;
    private final AircraftAlerter alerter;
    private java.util.function.Consumer<java.time.LocalDate> digestTrigger = null;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant startedAt = Instant.now();
    private final double mapLat;
    private final double mapLon;

    public WebServer(int port, String host,
                     FlightRepository repository,
                     ADSBStatsProvider statsProvider,
                     Supplier<DiscordStats> discordStatsSupplier,
                     LongSupplier flightsPersistedSupplier,
                     Supplier<List<Flight>> liveFlightsSupplier,
                     ConfigManager config) throws IOException {
        this.repository = repository;
        this.statsProvider = statsProvider;
        this.discordStatsSupplier = discordStatsSupplier;
        this.flightsPersistedSupplier = flightsPersistedSupplier;
        this.liveFlightsSupplier = liveFlightsSupplier;
        this.alerter = new AircraftAlerter(config);

        AirportCoords coords = AirportCoords.resolve(config);
        this.mapLat = (coords != null) ? coords.lat() : 40.47;
        this.mapLon = (coords != null) ? coords.lon() : -3.56;

        InetSocketAddress addr = ("0.0.0.0".equals(host) || host == null || host.isBlank())
                ? new InetSocketAddress(port)
                : new InetSocketAddress(host, port);
        this.server = HttpServer.create(addr, 0);
        this.server.createContext("/metrics",             this::handleMetrics);
        this.server.createContext("/api/flights",         this::handleApiFlights);
        this.server.createContext("/api/live",            this::handleApiLive);
        this.server.createContext("/api/debug/digest",    this::handleDebugDigest);
        this.server.createContext("/health",              this::handleHealth);
        this.server.createContext("/",                    this::handleHtml);
        this.server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "webserver");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
        log.info("WebServer started on port {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(1);
        log.info("WebServer stopped");
    }

    @Override
    public void close() {
        stop();
    }

    /** Visible for tests — returns the port the server is actually bound to. */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /** Called by ADSBFlightTracker after construction to wire up the digest trigger. */
    public void setDigestTrigger(java.util.function.Consumer<java.time.LocalDate> trigger) {
        this.digestTrigger = trigger;
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private void handleHealth(HttpExchange ex) {
        try {
            ADSBStats stats = statsProvider.getStats();
            long uptimeSec = java.time.Duration.between(startedAt, Instant.now()).getSeconds();
            long lastMsgSec = stats.lastMessageTime() != null
                    ? java.time.Duration.between(stats.lastMessageTime(), Instant.now()).getSeconds()
                    : -1;
            String body = String.format(
                    "{\"status\":\"ok\",\"uptime_s\":%d,\"last_message_ago_s\":%d,\"connected\":%b,\"aircraft_tracked\":%d}",
                    uptimeSec, lastMsgSec, stats.connected(), stats.aircraftCount());
            sendResponse(ex, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error serving /health: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    private void handleDebugDigest(HttpExchange ex) {
        try {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                byte[] body = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
                sendResponse(ex, 403, "application/json", body);
                return;
            }
            if (digestTrigger == null) {
                byte[] body = "{\"error\":\"digest trigger not wired up\"}".getBytes(StandardCharsets.UTF_8);
                sendResponse(ex, 503, "application/json", body);
                return;
            }
            Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
            java.time.LocalDate date = params.containsKey("date")
                    ? java.time.LocalDate.parse(params.get("date"))
                    : java.time.LocalDate.now();
            java.util.function.Consumer<java.time.LocalDate> trigger = digestTrigger;
            new Thread(() -> trigger.accept(date), "digest-manual").start();
            byte[] body = ("{\"status\":\"digest triggered\",\"date\":\"" + date + "\"}").getBytes(StandardCharsets.UTF_8);
            sendResponse(ex, 200, "application/json", body);
        } catch (Exception e) {
            sendError(ex, e);
        }
    }

    private void handleApiLive(HttpExchange ex) {
        try {
            List<Flight> live = liveFlightsSupplier.get();
            List<Map<String, Object>> aircraft = live.stream()
                    .filter(f -> f.latitude() != null && f.longitude() != null)
                    .map(this::liveFlightToMap)
                    .collect(Collectors.toList());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", aircraft.size());
            response.put("aircraft", aircraft);

            byte[] body = mapper.writeValueAsBytes(response);
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            sendResponse(ex, 200, "application/json", body);
        } catch (Exception e) {
            log.error("Error serving /api/live: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    private void handleMetrics(HttpExchange ex) {
        try {
            ADSBStats stats = statsProvider.getStats();
            DiscordStats discord = discordStatsSupplier.get();
            String body = String.format("""
                    # HELP flightscanner_aircraft_tracked Current aircraft in the live map
                    # TYPE flightscanner_aircraft_tracked gauge
                    flightscanner_aircraft_tracked %d

                    # HELP flightscanner_messages_per_second ADS-B message rate
                    # TYPE flightscanner_messages_per_second gauge
                    flightscanner_messages_per_second %d

                    # HELP flightscanner_connected ADS-B source connection state (1=up, 0=down)
                    # TYPE flightscanner_connected gauge
                    flightscanner_connected %d

                    # HELP flightscanner_messages_received_total Total SBS messages received since startup
                    # TYPE flightscanner_messages_received_total counter
                    flightscanner_messages_received_total %d

                    # HELP flightscanner_flights_persisted_total Flights saved to flights.db since startup
                    # TYPE flightscanner_flights_persisted_total counter
                    flightscanner_flights_persisted_total %d

                    # HELP flightscanner_notifications_sent_total Discord notifications sent since startup
                    # TYPE flightscanner_notifications_sent_total counter
                    flightscanner_notifications_sent_total %d

                    # HELP flightscanner_alerts_sent_total ALERT-tier notifications sent since startup
                    # TYPE flightscanner_alerts_sent_total counter
                    flightscanner_alerts_sent_total %d

                    # HELP flightscanner_coalesced_pending Notifications currently waiting in the coalesce buffer
                    # TYPE flightscanner_coalesced_pending gauge
                    flightscanner_coalesced_pending %d
                    """,
                    stats.aircraftCount(),
                    stats.messagesPerSecond(),
                    stats.connected() ? 1 : 0,
                    stats.messagesReceived(),
                    flightsPersistedSupplier.getAsLong(),
                    discord.notificationsSent(),
                    discord.alertsSent(),
                    discord.coalescedCount());
            sendResponse(ex, 200, PROM_CONTENT_TYPE, body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error serving /metrics: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    private void handleApiFlights(HttpExchange ex) {
        try {
            Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
            String tier = params.getOrDefault("tier", "noteworthy").toLowerCase();

            if (params.containsKey("from") || params.containsKey("to")) {
                handleApiFlightsDateRange(ex, params, tier);
            } else {
                handleApiFlightsSince(ex, params, tier);
            }
        } catch (Exception e) {
            log.error("Error serving /api/flights: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    private void handleApiFlightsSince(HttpExchange ex, Map<String, String> params, String tier) throws Exception {
        LocalDateTime since;
        if (params.containsKey("since")) {
            try {
                since = LocalDateTime.parse(params.get("since"));
            } catch (java.time.format.DateTimeParseException e) {
                byte[] body = "{\"error\":\"invalid 'since' — expected ISO-8601 datetime, e.g. 2024-01-01T00:00:00\"}".getBytes(StandardCharsets.UTF_8);
                sendResponse(ex, 400, "application/json", body);
                return;
            }
        } else {
            since = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
        }

        long hoursBack = java.time.Duration.between(since, LocalDateTime.now()).toHours() + 1;
        hoursBack = Math.max(1, Math.min(72, hoursBack));
        List<Flight> all = repository.findRecent((int) hoursBack);

        List<Flight> sinceFiltered = all.stream()
                .filter(f -> f.scheduledTime() != null && !f.scheduledTime().isBefore(since))
                .collect(Collectors.toList());

        List<Flight> result = applyTierFilter(sinceFiltered, tier);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("since", since.toString());
        response.put("tier", tier);
        response.put("count", result.size());
        response.put("flights", result.stream().map(this::flightToMap).collect(Collectors.toList()));

        byte[] body = mapper.writeValueAsBytes(response);
        sendResponse(ex, 200, "application/json", body);
    }

    private void handleApiFlightsDateRange(HttpExchange ex, Map<String, String> params, String tier) throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate to;
        LocalDate from = null; // null = all time (no lower bound)
        try {
            String fromStr = params.getOrDefault("from", "").trim();
            String toStr   = params.getOrDefault("to",   "").trim();
            if (!fromStr.isEmpty()) from = LocalDate.parse(fromStr);
            to = toStr.isEmpty() ? today : LocalDate.parse(toStr);
        } catch (java.time.format.DateTimeParseException e) {
            byte[] body = "{\"error\":\"invalid date — expected YYYY-MM-DD\"}".getBytes(StandardCharsets.UTF_8);
            sendResponse(ex, 400, "application/json", body);
            return;
        }

        if (from != null && to.isBefore(from)) to = from;

        List<Flight> all = repository.findByDateRange(
                from != null ? from.atStartOfDay() : null,
                to.plusDays(1).atStartOfDay());
        List<Flight> result = applyTierFilter(all, tier);
        boolean truncated = result.size() == 10000;

        Map<String, Object> response = new LinkedHashMap<>();
        if (from == null) {
            response.put("allTime", true);
        } else {
            response.put("from", from.toString());
        }
        response.put("to", to.toString());
        response.put("tier", tier);
        response.put("count", result.size());
        if (truncated) response.put("truncated", true);
        response.put("flights", result.stream().map(this::flightToMap).collect(Collectors.toList()));

        byte[] body = mapper.writeValueAsBytes(response);
        sendResponse(ex, 200, "application/json", body);
    }

    private List<Flight> applyTierFilter(List<Flight> flights, String tier) {
        return switch (tier) {
            case "alert" -> flights.stream().filter(alerter::isAlert).collect(Collectors.toList());
            case "all"   -> flights;
            default      -> flights.stream()
                    .filter(f -> AircraftTypes.classify(f.aircraft()) != AircraftTypes.AircraftCategory.COMMERCIAL)
                    .collect(Collectors.toList());
        };
    }

    private void handleHtml(HttpExchange ex) {
        try {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String html = buildHtml(now);
            sendResponse(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error serving /: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    // ── HTML builder ─────────────────────────────────────────────────────────

    private String buildHtml(String refreshTime) {
        String today   = LocalDate.now().toString();
        String weekAgo = LocalDate.now().minusDays(7).toString();
        return "<!DOCTYPE html>\n"
             + "<html lang=\"en\">\n"
             + "<head>\n"
             + "  <meta charset=\"UTF-8\">\n"
             + "  <title>FlightScanner</title>\n"
             + "  <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n"
             + "  <script defer src=\"https://unpkg.com/alpinejs@3/dist/cdn.min.js\"></script>\n"
             + "  <style>\n"
             + "    *{box-sizing:border-box}\n"
             + "    html,body{height:100%;margin:0}\n"
             + "    [x-cloak]{display:none!important}\n"
             + "    .app-root{display:flex;height:100vh;font-family:monospace;background:#1a1a2e;color:#e0e0e0}\n"
             + "    a{color:#7eb8f7}\n"
             // sidebar
             + "    .sidebar{width:200px;flex-shrink:0;background:#0f0f23;border-right:1px solid #2a2a5e;"
             + "display:flex;flex-direction:column;padding:16px;gap:4px}\n"
             + "    .sidebar-logo{color:#7eb8f7;font-size:1.1em;font-weight:bold;margin-bottom:20px;letter-spacing:1px}\n"
             + "    .nav-btn{width:100%;background:none;color:#a0a0c0;border:1px solid transparent;"
             + "padding:8px 12px;margin-bottom:2px;text-align:left;cursor:pointer;"
             + "font-family:monospace;font-size:0.95em;border-radius:4px;transition:all 0.15s}\n"
             + "    .nav-btn:hover{background:#16213e;color:#e0e0e0}\n"
             + "    .nav-btn.active{background:#16213e;color:#7eb8f7;border-color:#2a2a5e}\n"
             + "    .sidebar-links{margin-top:auto;font-size:0.78em;color:#555;line-height:2}\n"
             + "    .sidebar-links a{color:#4a6a8a;text-decoration:none}\n"
             + "    .sidebar-links a:hover{color:#7eb8f7}\n"
             + "    .sidebar-ts{font-size:0.72em;color:#383838;margin-top:6px}\n"
             // main panels
             + "    .panel{flex:1;overflow:auto;padding:20px;min-width:0}\n"
             + "    #map{height:calc(100vh - 40px);border:1px solid #2a2a5e;border-radius:4px}\n"
             + "    .leaflet-popup-content-wrapper{background:#1a1a2e;color:#e0e0e0;border:1px solid #7eb8f7}\n"
             + "    .leaflet-popup-tip{background:#1a1a2e}\n"
             // spinner
             + "    .spinner-wrap{display:flex;align-items:center;gap:12px;padding:48px 0;color:#7eb8f7}\n"
             + "    .spinner{width:28px;height:28px;border:3px solid #2a2a5e;border-top-color:#7eb8f7;"
             + "border-radius:50%;animation:spin 0.8s linear infinite;flex-shrink:0}\n"
             + "    @keyframes spin{to{transform:rotate(360deg)}}\n"
             // presets
             + "    .presets{display:flex;gap:6px;margin-bottom:10px;flex-wrap:wrap}\n"
             + "    .preset-btn{background:#16213e;color:#a0a0c0;border:1px solid #2a2a5e;"
             + "padding:3px 11px;cursor:pointer;font-family:monospace;font-size:0.9em;border-radius:3px;transition:all 0.1s}\n"
             + "    .preset-btn:hover{border-color:#7eb8f7;color:#c0d8f0}\n"
             + "    .preset-btn.active{border-color:#7eb8f7;color:#7eb8f7;background:#1a2a40}\n"
             // search controls
             + "    #search-controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:12px}\n"
             + "    #search-controls label{display:flex;align-items:center;gap:4px;font-size:0.9em;color:#a0a0c0}\n"
             + "    #search-controls input,#search-controls select{background:#16213e;color:#e0e0e0;"
             + "border:1px solid #2a2a5e;padding:4px 8px;font-family:monospace;font-size:0.9em;border-radius:3px}\n"
             + "    #search-controls input:focus,#search-controls select:focus{outline:none;border-color:#7eb8f7}\n"
             + "    input:disabled{opacity:0.35;cursor:not-allowed}\n"
             + "    .search-btn{background:#2a2a5e;color:#7eb8f7;border:1px solid #7eb8f7;"
             + "padding:5px 16px;cursor:pointer;font-family:monospace;border-radius:3px;transition:background 0.15s}\n"
             + "    .search-btn:hover{background:#3a3a7e}\n"
             // result count
             + "    .result-count{color:#666;font-size:0.88em;margin-bottom:8px;min-height:1.3em}\n"
             + "    .trunc-warn{color:#f0a030;margin-left:8px}\n"
             // table
             + "    .table-wrap{overflow:auto}\n"
             + "    table{border-collapse:collapse;width:100%}\n"
             + "    thead th{position:sticky;top:0;background:#16213e;color:#7eb8f7;"
             + "padding:6px 12px;text-align:left;border-bottom:2px solid #2a2a5e;white-space:nowrap}\n"
             + "    td{padding:4px 12px;border-bottom:1px solid #2a2a4e;white-space:nowrap;font-size:0.92em}\n"
             + "    tr:hover td{background:#16213e}\n"
             + "    th.sortable{cursor:pointer;user-select:none}\n"
             + "    th.sortable:hover{color:#a0c8ff}\n"
             + "    th.sort-asc::after{content:' \\2191'}\n"
             + "    th.sort-desc::after{content:' \\2193'}\n"
             // tier badges + row accents
             + "    .badge{display:inline-block;padding:1px 6px;border-radius:3px;font-size:0.82em;font-weight:bold}\n"
             + "    .badge-alert{background:#3d1515;color:#ff6b6b;border:1px solid #6b2020}\n"
             + "    .badge-noteworthy{background:#1a2a40;color:#7eb8f7;border:1px solid #2a4a6e}\n"
             + "    .badge-commercial{background:#1e1e28;color:#808080;border:1px solid #2a2a3e}\n"
             + "    .tier-alert td{border-left:3px solid #ff6b6b}\n"
             + "    .tier-noteworthy td{border-left:3px solid #2a5a8a}\n"
             + "    .tier-commercial td{border-left:3px solid #2a2a4e}\n"
             + "  </style>\n"
             + "</head>\n"
             + "<body>\n"
             + "<div x-data=\"app()\" x-init=\"init()\" class=\"app-root\" x-cloak>\n"
             // ── Sidebar ────────────────────────────────────────────────────
             + "  <aside class=\"sidebar\">\n"
             + "    <div class=\"sidebar-logo\">&#9992; FlightScanner</div>\n"
             + "    <button class=\"nav-btn\" :class=\"mode==='live' ? 'active' : ''\" @click=\"switchMode('live')\">Live Traffic</button>\n"
             + "    <button class=\"nav-btn\" :class=\"mode==='search' ? 'active' : ''\" @click=\"switchMode('search')\">Flight Search</button>\n"
             + "    <div class=\"sidebar-links\">\n"
             + "      <a href=\"/api/flights\">API</a> &middot;\n"
             + "      <a href=\"/api/live\">Live JSON</a> &middot;\n"
             + "      <a href=\"/metrics\">Metrics</a> &middot;\n"
             + "      <a href=\"/health\">Health</a>\n"
             + "    </div>\n"
             + "    <div class=\"sidebar-ts\" x-text=\"'Updated: ' + refreshTime\"></div>\n"
             + "  </aside>\n"
             // ── Live Traffic panel ─────────────────────────────────────────
             + "  <main class=\"panel\" x-show=\"mode === 'live'\">\n"
             + "    <div id=\"map\"></div>\n"
             + "  </main>\n"
             // ── Flight Search panel ────────────────────────────────────────
             + "  <main class=\"panel\" x-show=\"mode === 'search'\">\n"
             // spinner
             + "    <div class=\"spinner-wrap\" x-show=\"loading\">\n"
             + "      <div class=\"spinner\"></div>\n"
             + "      <span>Loading flights&hellip;</span>\n"
             + "    </div>\n"
             // presets
             + "    <div x-show=\"!loading || allFlights.length > 0\">\n"
             + "      <div class=\"presets\">\n"
             + "        <button class=\"preset-btn\" :class=\"preset===7   ? 'active' : ''\" @click=\"setPreset(7)\">7d</button>\n"
             + "        <button class=\"preset-btn\" :class=\"preset===30  ? 'active' : ''\" @click=\"setPreset(30)\">30d</button>\n"
             + "        <button class=\"preset-btn\" :class=\"preset===90  ? 'active' : ''\" @click=\"setPreset(90)\">90d</button>\n"
             + "        <button class=\"preset-btn\" :class=\"preset==='all' ? 'active' : ''\" @click=\"setAllTime()\">All time</button>\n"
             + "      </div>\n"
             // search controls
             + "      <div id=\"search-controls\">\n"
             + "        <label>From <input type=\"date\" x-model=\"fromDate\" :disabled=\"preset === 'all'\"></label>\n"
             + "        <label>To <input type=\"date\" x-model=\"toDate\"></label>\n"
             + "        <select x-model=\"filterTier\" @change=\"applyFilters()\">\n"
             + "          <option value=\"all\">All</option>\n"
             + "          <option value=\"noteworthy\">Noteworthy</option>\n"
             + "          <option value=\"alert\">Alert</option>\n"
             + "          <option value=\"commercial\">Commercial</option>\n"
             + "        </select>\n"
             + "        <input x-model=\"filterFlight\"   @input=\"applyFilters()\" placeholder=\"Flight #\" size=\"9\">\n"
             + "        <input x-model=\"filterAircraft\" @input=\"applyFilters()\" placeholder=\"Aircraft\" size=\"7\">\n"
             + "        <input x-model=\"filterOperator\" @input=\"applyFilters()\" placeholder=\"Operator\" size=\"11\">\n"
             + "        <input type=\"number\" x-model=\"altMin\" @input=\"applyFilters()\" placeholder=\"Alt min ft\" size=\"8\">\n"
             + "        <input type=\"number\" x-model=\"altMax\" @input=\"applyFilters()\" placeholder=\"Alt max ft\" size=\"8\">\n"
             + "        <button class=\"search-btn\" @click=\"runSearch()\">Search</button>\n"
             + "      </div>\n"
             // result count
             + "      <div class=\"result-count\" x-show=\"!loading\">\n"
             + "        <span x-text=\"resultText\"></span>\n"
             + "        <span class=\"trunc-warn\" x-show=\"truncated\">&#9888; Showing first 10,000 &mdash; narrow the date range for more precision</span>\n"
             + "      </div>\n"
             // table
             + "      <div class=\"table-wrap\">\n"
             + "        <table id=\"flights-table\">\n"
             + "          <thead><tr>\n"
             + "            <th class=\"sortable\" data-col=\"scheduledTime\" @click=\"sortBy('scheduledTime')\">Time</th>\n"
             + "            <th class=\"sortable\" data-col=\"flightNumber\"  @click=\"sortBy('flightNumber')\">Flight</th>\n"
             + "            <th class=\"sortable\" data-col=\"aircraft\"      @click=\"sortBy('aircraft')\">Aircraft</th>\n"
             + "            <th class=\"sortable\" data-col=\"altitude\"      @click=\"sortBy('altitude')\">Altitude</th>\n"
             + "            <th class=\"sortable\" data-col=\"speed\"         @click=\"sortBy('speed')\">Speed</th>\n"
             + "            <th class=\"sortable\" data-col=\"operator\"      @click=\"sortBy('operator')\">Operator</th>\n"
             + "            <th class=\"sortable\" data-col=\"tier\"          @click=\"sortBy('tier')\">Tier</th>\n"
             + "            <th>Origin</th>\n"
             + "            <th>Squawk</th>\n"
             + "          </tr></thead>\n"
             + "          <tbody id=\"flights-tbody\"></tbody>\n"
             + "        </table>\n"
             + "      </div>\n"
             + "    </div>\n"
             + "  </main>\n"
             + "</div>\n"
             // ── Scripts ────────────────────────────────────────────────────
             + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n"
             + "<script>\n"
             // Leaflet init (called from Alpine x-init)
             + "  var _mapMarkers = {};\n"
             + "  function initLeafletMap() {\n"
             + "    window.leafletMap = L.map('map').setView([" + mapLat + ", " + mapLon + "], 7);\n"
             + "    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n"
             + "      attribution: '\\u00a9 <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>',\n"
             + "      maxZoom: 15\n"
             + "    }).addTo(window.leafletMap);\n"
             + "    function updateMap() {\n"
             + "      fetch('/api/live').then(r => r.json()).then(data => {\n"
             + "        var seen = new Set();\n"
             + "        (data.aircraft || []).forEach(function(ac) {\n"
             + "          if (ac.lat == null || ac.lon == null) return;\n"
             + "          seen.add(ac.hex);\n"
             + "          var popup = (ac.flag || '') + ' <b>' + ac.flightNumber + '</b><br>'\n"
             + "            + ac.aircraft + ' | ' + (ac.altitude != null ? ac.altitude + ' ft' : '?')\n"
             + "            + ' | ' + (ac.speed != null ? ac.speed + ' kts' : '?')\n"
             + "            + (ac.operator ? '<br>' + ac.operator : '');\n"
             + "          if (_mapMarkers[ac.hex]) {\n"
             + "            _mapMarkers[ac.hex].setLatLng([ac.lat, ac.lon]).setPopupContent(popup);\n"
             + "          } else {\n"
             + "            _mapMarkers[ac.hex] = L.marker([ac.lat, ac.lon]).addTo(window.leafletMap).bindPopup(popup);\n"
             + "          }\n"
             + "        });\n"
             + "        Object.keys(_mapMarkers).forEach(function(hex) {\n"
             + "          if (!seen.has(hex)) { window.leafletMap.removeLayer(_mapMarkers[hex]); delete _mapMarkers[hex]; }\n"
             + "        });\n"
             + "      }).catch(function() {});\n"
             + "    }\n"
             + "    updateMap();\n"
             + "    setInterval(updateMap, 30000);\n"
             + "  }\n"
             // renderTable and esc (plain JS, called from Alpine)
             + "  function renderTable(flights) {\n"
             + "    var html = '';\n"
             + "    flights.forEach(function(f) {\n"
             + "      var time = f.scheduledTime ? f.scheduledTime.substring(0,16).replace('T',' ') : '';\n"
             + "      var tier = f.tier || 'commercial';\n"
             + "      html += '<tr class=\"tier-' + tier + '\">'\n"
             + "        + '<td>' + esc(time) + '</td>'\n"
             + "        + '<td>' + esc(f.flightNumber) + '</td>'\n"
             + "        + '<td>' + esc(f.aircraft) + '</td>'\n"
             + "        + '<td>' + (f.altitude != null ? f.altitude + ' ft' : '\\u2014') + '</td>'\n"
             + "        + '<td>' + (f.speed    != null ? f.speed    + ' kts' : '\\u2014') + '</td>'\n"
             + "        + '<td>' + esc(f.operator) + '</td>'\n"
             + "        + '<td><span class=\"badge badge-' + tier + '\">' + esc(tier) + '</span></td>'\n"
             + "        + '<td>' + esc(f.origin) + '</td>'\n"
             + "        + '<td>' + esc(f.squawk) + '</td>'\n"
             + "        + '</tr>\\n';\n"
             + "    });\n"
             + "    document.getElementById('flights-tbody').innerHTML = html;\n"
             + "  }\n"
             + "  function esc(s) {\n"
             + "    if (s == null) return '';\n"
             + "    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');\n"
             + "  }\n"
             // Alpine app state
             + "  function app() {\n"
             + "    return {\n"
             + "      mode: 'search',\n"
             + "      loading: false,\n"
             + "      allFlights: [],\n"
             + "      truncated: false,\n"
             + "      sortCol: 'scheduledTime',\n"
             + "      sortAsc: false,\n"
             + "      preset: 7,\n"
             + "      fromDate: '" + weekAgo + "',\n"
             + "      toDate:   '" + today   + "',\n"
             + "      filterFlight: '', filterAircraft: '', filterOperator: '',\n"
             + "      filterTier: 'all',\n"
             + "      altMin: '', altMax: '',\n"
             + "      resultText: '',\n"
             + "      refreshTime: '" + he(refreshTime) + "',\n"
             + "      init() {\n"
             + "        initLeafletMap();\n"
             + "        this.runSearch();\n"
             + "      },\n"
             + "      switchMode(m) {\n"
             + "        this.mode = m;\n"
             + "        if (m === 'live') {\n"
             + "          this.$nextTick(() => { if (window.leafletMap) window.leafletMap.invalidateSize(); });\n"
             + "        }\n"
             + "      },\n"
             + "      setPreset(days) {\n"
             + "        this.preset = days;\n"
             + "        var d = new Date(); d.setDate(d.getDate() - days);\n"
             + "        this.fromDate = d.toISOString().slice(0, 10);\n"
             + "        this.toDate   = new Date().toISOString().slice(0, 10);\n"
             + "        this.runSearch();\n"
             + "      },\n"
             + "      setAllTime() {\n"
             + "        this.preset = 'all';\n"
             + "        this.toDate = new Date().toISOString().slice(0, 10);\n"
             + "        this.runSearch();\n"
             + "      },\n"
             + "      buildUrl() {\n"
             + "        var to = this.toDate || new Date().toISOString().slice(0, 10);\n"
             + "        if (this.preset === 'all') return '/api/flights?to=' + to + '&tier=all';\n"
             + "        return '/api/flights?from=' + this.fromDate + '&to=' + to + '&tier=all';\n"
             + "      },\n"
             + "      runSearch() {\n"
             + "        this.loading = true;\n"
             + "        fetch(this.buildUrl())\n"
             + "          .then(r => r.json())\n"
             + "          .then(data => {\n"
             + "            this.allFlights = data.flights || [];\n"
             + "            this.truncated  = data.truncated === true;\n"
             + "            this.loading    = false;\n"
             + "            this.applyFilters();\n"
             + "          })\n"
             + "          .catch(() => { this.loading = false; });\n"
             + "      },\n"
             + "      applyFilters() {\n"
             + "        var fn   = this.filterFlight.trim().toUpperCase();\n"
             + "        var ac   = this.filterAircraft.trim().toUpperCase();\n"
             + "        var op   = this.filterOperator.trim().toLowerCase();\n"
             + "        var tier = this.filterTier;\n"
             + "        var altMin = parseInt(this.altMin) || 0;\n"
             + "        var altMax = parseInt(this.altMax) || 999999;\n"
             + "        var filtered = this.allFlights.filter(f => {\n"
             + "          if (tier !== 'all' && f.tier !== tier) return false;\n"
             + "          if (fn && !(f.flightNumber||'').toUpperCase().includes(fn)) return false;\n"
             + "          if (ac && !(f.aircraft||'').toUpperCase().includes(ac)) return false;\n"
             + "          if (op && !(f.operator||'').toLowerCase().includes(op)) return false;\n"
             + "          if (f.altitude != null && (f.altitude < altMin || f.altitude > altMax)) return false;\n"
             + "          return true;\n"
             + "        });\n"
             + "        filtered = this.sortFlights(filtered);\n"
             + "        renderTable(filtered);\n"
             + "        this.resultText = filtered.length + ' of ' + this.allFlights.length + ' flights';\n"
             + "      },\n"
             + "      sortBy(col) {\n"
             + "        if (this.sortCol === col) { this.sortAsc = !this.sortAsc; }\n"
             + "        else { this.sortCol = col; this.sortAsc = true; }\n"
             + "        document.querySelectorAll('th.sortable').forEach(th => {\n"
             + "          th.classList.remove('sort-asc','sort-desc');\n"
             + "          if (th.dataset.col === col) th.classList.add(this.sortAsc ? 'sort-asc' : 'sort-desc');\n"
             + "        });\n"
             + "        this.applyFilters();\n"
             + "      },\n"
             + "      sortFlights(arr) {\n"
             + "        var col = this.sortCol, asc = this.sortAsc;\n"
             + "        return arr.slice().sort(function(a,b) {\n"
             + "          var va=a[col], vb=b[col];\n"
             + "          if(va==null&&vb==null) return 0; if(va==null) return 1; if(vb==null) return -1;\n"
             + "          var c = typeof va==='number'&&typeof vb==='number' ? va-vb : String(va).localeCompare(String(vb));\n"
             + "          return asc ? c : -c;\n"
             + "        });\n"
             + "      }\n"
             + "    };\n"
             + "  }\n"
             + "</script>\n"
             + "</body>\n"
             + "</html>\n";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> flightToMap(Flight f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flightNumber", f.flightNumber());
        m.put("origin", f.origin());
        m.put("aircraft", f.aircraft());
        m.put("altitude", f.altitude());
        m.put("speed", f.speed());
        m.put("scheduledTime", f.scheduledTime() != null ? f.scheduledTime().toString() : null);
        m.put("operator", f.operator());
        m.put("squawk", f.squawk());
        m.put("tier", computeTier(f));
        return m;
    }

    private String computeTier(Flight f) {
        if (alerter.isAlert(f)) return "alert";
        AircraftTypes.AircraftCategory cat = AircraftTypes.classify(f.aircraft());
        return cat == AircraftTypes.AircraftCategory.COMMERCIAL ? "commercial" : "noteworthy";
    }

    private Map<String, Object> liveFlightToMap(Flight f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hex", f.hexIdent());
        m.put("flightNumber", f.flightNumber());
        m.put("aircraft", f.aircraft());
        m.put("altitude", f.altitude());
        m.put("speed", f.speed());
        m.put("lat", f.latitude());
        m.put("lon", f.longitude());
        m.put("operator", f.operator());
        m.put("flag", ICAOCountry.flagFromHex(f.hexIdent()));
        return m;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Map.of();
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                           URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private void sendResponse(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendError(HttpExchange ex, Exception e) {
        try {
            byte[] body = ("{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}").getBytes(StandardCharsets.UTF_8);
            sendResponse(ex, 500, "application/json", body);
        } catch (IOException ignored) {}
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String he(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
