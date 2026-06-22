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
        this.mapLat = (coords != null) ? coords.lat() : 39.49;
        this.mapLon = (coords != null) ? coords.lon() : -0.48;

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

            String tier = params.getOrDefault("tier", "noteworthy").toLowerCase();

            long hoursBack = java.time.Duration.between(since, LocalDateTime.now()).toHours() + 1;
            hoursBack = Math.max(1, Math.min(72, hoursBack));
            List<Flight> all = repository.findRecent((int) hoursBack);

            List<Flight> sinceFiltered = all.stream()
                    .filter(f -> f.scheduledTime() != null && !f.scheduledTime().isBefore(since))
                    .collect(Collectors.toList());

            List<Flight> result = switch (tier) {
                case "alert" -> sinceFiltered.stream()
                        .filter(alerter::isAlert)
                        .collect(Collectors.toList());
                case "all" -> sinceFiltered;
                default -> sinceFiltered.stream()
                        .filter(f -> AircraftTypes.classify(f.aircraft())
                                != AircraftTypes.AircraftCategory.COMMERCIAL)
                        .collect(Collectors.toList());
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("since", since.toString());
            response.put("tier", tier);
            response.put("count", result.size());
            response.put("flights", result.stream().map(this::flightToMap).collect(Collectors.toList()));

            byte[] body = mapper.writeValueAsBytes(response);
            sendResponse(ex, 200, "application/json", body);
        } catch (Exception e) {
            log.error("Error serving /api/flights: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    private void handleHtml(HttpExchange ex) {
        try {
            List<Flight> recent = repository.findRecent(24);
            List<Flight> noteworthy = recent.stream()
                    .filter(f -> AircraftTypes.classify(f.aircraft())
                            != AircraftTypes.AircraftCategory.COMMERCIAL)
                    .limit(50)
                    .collect(Collectors.toList());

            StringBuilder rows = new StringBuilder();
            for (Flight f : noteworthy) {
                String flag = ICAOCountry.flagFromHex(f.hexIdent());
                rows.append(String.format(
                        "      <tr><td>%s</td><td>%s</td><td>%s %s</td><td>%s</td><td>%s</td><td>%s</td></tr>%n",
                        he(f.scheduledTime() != null ? f.scheduledTime().format(TIME_FMT) : ""),
                        he(f.flightNumber()),
                        he(flag),
                        he(f.aircraft()),
                        f.altitude() != null ? f.altitude() + " ft" : "N/A",
                        f.speed() != null ? f.speed() + " kts" : "N/A",
                        he(f.operator() != null ? f.operator() : "")));
            }

            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String html = buildHtml(rows.toString(), now);
            sendResponse(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error serving /: {}", e.getMessage());
            sendError(ex, e);
        }
    }

    // ── HTML builder ─────────────────────────────────────────────────────────

    private String buildHtml(String tableRows, String refreshTime) {
        return "<!DOCTYPE html>\n"
             + "<html lang=\"en\">\n"
             + "<head>\n"
             + "  <meta charset=\"UTF-8\">\n"
             + "  <meta http-equiv=\"refresh\" content=\"30\">\n"
             + "  <title>FlightScanner</title>\n"
             + "  <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\"/>\n"
             + "  <style>\n"
             + "    body { background:#1a1a2e; color:#e0e0e0; font-family:monospace; padding:20px; margin:0; }\n"
             + "    h1   { color:#7eb8f7; margin-bottom:16px; }\n"
             + "    h2   { color:#7eb8f7; margin-top:24px; margin-bottom:8px; }\n"
             + "    #map { height:420px; width:100%; border:1px solid #2a2a5e; margin-bottom:20px; }\n"
             + "    .leaflet-popup-content-wrapper { background:#1a1a2e; color:#e0e0e0; border:1px solid #7eb8f7; }\n"
             + "    .leaflet-popup-tip { background:#1a1a2e; }\n"
             + "    table{ border-collapse:collapse; width:100%; }\n"
             + "    th   { background:#16213e; color:#7eb8f7; padding:6px 12px; text-align:left; border-bottom:2px solid #2a2a5e; }\n"
             + "    td   { padding:4px 12px; border-bottom:1px solid #2a2a4e; }\n"
             + "    tr:hover td { background:#16213e; }\n"
             + "    .footer { margin-top:16px; font-size:0.85em; color:#888; }\n"
             + "    a { color:#7eb8f7; }\n"
             + "  </style>\n"
             + "</head>\n"
             + "<body>\n"
             + "  <h1>FlightScanner</h1>\n"
             + "  <h2>Live Traffic</h2>\n"
             + "  <div id=\"map\"></div>\n"
             + "  <h2>Noteworthy Flights (last 24 h)</h2>\n"
             + "  <table>\n"
             + "    <thead>\n"
             + "      <tr><th>Time</th><th>Flight</th><th>Aircraft</th><th>Altitude</th><th>Speed</th><th>Operator</th></tr>\n"
             + "    </thead>\n"
             + "    <tbody>\n"
             + tableRows
             + "    </tbody>\n"
             + "  </table>\n"
             + "  <div class=\"footer\">\n"
             + "    Last refresh: " + he(refreshTime)
             + " &middot; <a href=\"/api/flights\">JSON API</a>"
             + " &middot; <a href=\"/api/live\">Live JSON</a>"
             + " &middot; <a href=\"/metrics\">Metrics</a>"
             + " &middot; <a href=\"/health\">Health</a>\n"
             + "  </div>\n"
             + "  <script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n"
             + "  <script>\n"
             + "    var map = L.map('map').setView([" + mapLat + ", " + mapLon + "], 7);\n"
             + "    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n"
             + "      attribution: '\\u00a9 <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a>',\n"
             + "      maxZoom: 15\n"
             + "    }).addTo(map);\n"
             + "    var markers = {};\n"
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
             + "          if (markers[ac.hex]) {\n"
             + "            markers[ac.hex].setLatLng([ac.lat, ac.lon]).setPopupContent(popup);\n"
             + "          } else {\n"
             + "            markers[ac.hex] = L.marker([ac.lat, ac.lon]).addTo(map).bindPopup(popup);\n"
             + "          }\n"
             + "        });\n"
             + "        Object.keys(markers).forEach(function(hex) {\n"
             + "          if (!seen.has(hex)) { map.removeLayer(markers[hex]); delete markers[hex]; }\n"
             + "        });\n"
             + "      }).catch(function() {});\n"
             + "    }\n"
             + "    updateMap();\n"
             + "    setInterval(updateMap, 30000);\n"
             + "  </script>\n"
             + "</body>\n"
             + "</html>\n";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> flightToMap(Flight f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flightNumber", f.flightNumber());
        m.put("aircraft", f.aircraft());
        m.put("altitude", f.altitude());
        m.put("speed", f.speed());
        m.put("scheduledTime", f.scheduledTime() != null ? f.scheduledTime().toString() : null);
        m.put("operator", f.operator());
        return m;
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
