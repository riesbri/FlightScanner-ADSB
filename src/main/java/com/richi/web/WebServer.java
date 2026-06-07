package com.richi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richi.adsb.ADSBStats;
import com.richi.adsb.ADSBStatsProvider;
import com.richi.analyzer.AircraftAlerter;
import com.richi.analyzer.AircraftTypes;
import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import com.richi.notification.DiscordStats;
import com.richi.repository.FlightRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta http-equiv="refresh" content="30">
              <title>FlightScanner</title>
              <style>
                body { background:#1a1a2e; color:#e0e0e0; font-family:monospace; padding:20px; margin:0; }
                h1   { color:#7eb8f7; margin-bottom:16px; }
                table{ border-collapse:collapse; width:100%%; }
                th   { background:#16213e; color:#7eb8f7; padding:6px 12px; text-align:left; border-bottom:2px solid #2a2a5e; }
                td   { padding:4px 12px; border-bottom:1px solid #2a2a4e; }
                tr:hover td { background:#16213e; }
                .footer { margin-top:16px; font-size:0.85em; color:#888; }
                a { color:#7eb8f7; }
              </style>
            </head>
            <body>
              <h1>FlightScanner — Noteworthy Flights (last 24 h)</h1>
              <table>
                <thead>
                  <tr><th>Time</th><th>Flight</th><th>Aircraft</th><th>Altitude</th><th>Speed</th><th>Operator</th></tr>
                </thead>
                <tbody>
            %s
                </tbody>
              </table>
              <div class="footer">
                Last refresh: %s &middot; <a href="/api/flights">Raw JSON</a> &middot; <a href="/metrics">Metrics</a>
              </div>
            </body>
            </html>
            """;

    private final HttpServer server;
    private final FlightRepository repository;
    private final ADSBStatsProvider statsProvider;
    private final Supplier<DiscordStats> discordStatsSupplier;
    private final LongSupplier flightsPersistedSupplier;
    private final AircraftAlerter alerter;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebServer(int port, String host,
                     FlightRepository repository,
                     ADSBStatsProvider statsProvider,
                     Supplier<DiscordStats> discordStatsSupplier,
                     LongSupplier flightsPersistedSupplier,
                     ConfigManager config) throws IOException {
        this.repository = repository;
        this.statsProvider = statsProvider;
        this.discordStatsSupplier = discordStatsSupplier;
        this.flightsPersistedSupplier = flightsPersistedSupplier;
        this.alerter = new AircraftAlerter(config);

        InetSocketAddress addr = ("0.0.0.0".equals(host) || host == null || host.isBlank())
                ? new InetSocketAddress(port)
                : new InetSocketAddress(host, port);
        this.server = HttpServer.create(addr, 0);
        this.server.createContext("/metrics", this::handleMetrics);
        this.server.createContext("/api/flights", this::handleApiFlights);
        this.server.createContext("/", this::handleHtml);
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

    // ── Handlers ────────────────────────────────────────────────────────────

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
                since = LocalDateTime.parse(params.get("since"));
            } else {
                since = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT);
            }

            String tier = params.getOrDefault("tier", "noteworthy").toLowerCase();

            // Fetch enough data to cover the 'since' window, cap at 72h
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
                default -> sinceFiltered.stream()  // "noteworthy"
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
                rows.append(String.format(
                        "      <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>%n",
                        he(f.scheduledTime() != null ? f.scheduledTime().format(TIME_FMT) : ""),
                        he(f.flightNumber()),
                        he(f.aircraft()),
                        f.altitude() != null ? f.altitude() + " ft" : "N/A",
                        f.speed() != null ? f.speed() + " kts" : "N/A",
                        he(f.operator() != null ? f.operator() : "")));
            }

            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String html = String.format(HTML_TEMPLATE, rows, now);
            sendResponse(ex, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error serving /: {}", e.getMessage());
            sendError(ex, e);
        }
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
