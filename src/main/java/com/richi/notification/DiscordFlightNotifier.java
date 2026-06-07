package com.richi.notification;

import com.richi.analyzer.AircraftTypes;
import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.util.List;

@Slf4j
public class DiscordFlightNotifier implements FlightNotifier {

    // ── Category definitions ────────────────────────────────────────

    private enum Category {
        ALERT    ("🚨", 16711680, ""),       // 🚨  danger red (0xFF0000)
        MILITARY ("🪖", 10181046, "MILITARY"),// 🪖
        WIDEBODY ("🛫", 15158332, "WIDEBODY"),// 🛫
        BIZJET   ("🛩", 15844367, "BIZJET"),  // 🛩️
        COMMERCIAL("✈️", 3447003,  "");       // ✈️

        final String emoji;
        final int color;
        final String label;

        Category(String emoji, int color, String label) {
            this.emoji = emoji;
            this.color = color;
            this.label = label;
        }

        String title(String flightNumber) {
            if (this == ALERT) return emoji + " AIRCRAFT ALERT — " + flightNumber;
            if (label.isEmpty()) return emoji + " " + flightNumber;
            return emoji + " " + flightNumber + " [" + label + "]";
        }

        static Category from(AircraftTypes.AircraftCategory category) {
            return switch (category) {
                case ALERT     -> ALERT;
                case MILITARY  -> MILITARY;
                case WIDEBODY  -> WIDEBODY;
                case BIZJET    -> BIZJET;
                case COMMERCIAL -> COMMERCIAL;
            };
        }
    }

    // ── Instance ────────────────────────────────────────────────────

    private final String webhookUrl;
    private final boolean enabled;
    private final boolean startupTestMessage;
    private final CloseableHttpClient httpClient;

    public DiscordFlightNotifier() {
        this(ConfigManager.getInstance());
    }

    public DiscordFlightNotifier(ConfigManager config) {
        this.enabled = config.isDiscordEnabled();
        this.webhookUrl = config.getDiscordWebhookUrl();
        this.startupTestMessage = config.isStartupTestMessage();
        this.httpClient = HttpClients.createDefault();
        if (enabled) {
            log.info("Discord notifier initialized");
            if (!testConnection()) log.warn("Discord connection test failed");
        } else {
            log.info("Discord notifier disabled");
        }
    }

    @Override
    public void sendAlert(Flight flight) {
        if (!enabled) return;
        String payload = String.format("{\"embeds\": [%s]}", buildEmbedObject(flight));
        sendWebhook(payload);
    }

    @Override
    public void sendCriticalAlert(Flight flight) {
        if (!enabled) return;
        // ALERT embeds are never batched — always sent individually with the ALERT category
        String payload = String.format("{\"embeds\": [%s]}", buildAlertEmbedObject(flight));
        sendWebhook(payload);
    }

    @Override
    public void sendBatchAlert(List<Flight> flights) {
        if (!enabled || flights.isEmpty()) return;
        var objects = flights.stream().map(this::buildEmbedObject).toArray(String[]::new);
        String payload = String.format(
            "{\"content\": \"🛫 **%d new flights detected!**\", \"embeds\": [%s]}",
            flights.size(), String.join(",", objects));
        sendWebhook(payload);
    }

    @Override
    public boolean testConnection() {
        if (!enabled) return false;
        if (!startupTestMessage) {
            log.debug("Startup test message suppressed (discord.startup.test.message=false)");
            return true;
        }
        return doPost("{\"content\": \"✅ FlightTracker connected to Discord!\"}", "connection test");
    }

    private void sendWebhook(String payload) {
        if (doPost(payload, "message")) log.info("Discord message sent successfully");
    }

    private boolean doPost(String payload, String description) {
        try {
            HttpPost request = new HttpPost(webhookUrl);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
            return httpClient.execute(request, response -> {
                int sc = response.getCode();
                if (sc == 200 || sc == 204) {
                    log.info("Discord {} successful", description);
                    EntityUtils.consume(response.getEntity());
                    return true;
                }
                if (sc == 429) { log.warn("Discord rate limited"); return false; }
                String body = "";
                try { body = EntityUtils.toString(response.getEntity()); } catch (Exception ignored) {}
                log.error("Discord {} failed: HTTP {} body: {}", description, sc,
                    body.length() > 200 ? body.substring(0, 200) : body);
                return false;
            });
        } catch (Exception e) {
            log.error("Discord {} error: {}", description, e.getMessage());
            return false;
        }
    }

    private String buildEmbedObject(Flight flight) {
        String type = flight.aircraft() != null ? flight.aircraft().toUpperCase() : "UNKNOWN";
        Category cat = Category.from(AircraftTypes.classify(type));

        String altStr = flight.altitude() != null ? String.format("%d ft", flight.altitude()) : "N/A";
        String spdStr = flight.speed()    != null ? String.format("%d kts", flight.speed())   : "N/A";

        return String.format("""
                {
                  "title": "%s",
                  "color": %d,
                  "fields": [
                    {"name": "Aircraft", "value": "%s", "inline": true},
                    {"name": "Altitude", "value": "%s", "inline": true},
                    {"name": "Speed",    "value": "%s", "inline": true},
                    {"name": "Time",     "value": "%s", "inline": true}
                  ],
                  "footer": {"text": "FlightTracker ADS-B"}
                }
                """,
                escapeJson(cat.title(flight.flightNumber())),
                cat.color,
                escapeJson(type),
                escapeJson(altStr),
                escapeJson(spdStr),
                flight.scheduledTime().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );
    }

    /** Builds an ALERT-specific embed with squawk shown prominently. */
    private String buildAlertEmbedObject(Flight flight) {
        String type     = flight.aircraft() != null ? flight.aircraft().toUpperCase() : "UNKNOWN";
        String altStr   = flight.altitude() != null ? String.format("%d ft",  flight.altitude()) : "N/A";
        String spdStr   = flight.speed()    != null ? String.format("%d kts", flight.speed())    : "N/A";
        String squawk   = (flight.squawk() != null && !flight.squawk().isBlank()) ? flight.squawk() : "N/A";
        String hexIdent = (flight.hexIdent() != null && !flight.hexIdent().isBlank()) ? flight.hexIdent() : "N/A";

        return String.format("""
                {
                  "title": "%s",
                  "color": %d,
                  "fields": [
                    {"name": "Aircraft",  "value": "%s", "inline": true},
                    {"name": "Squawk",    "value": "%s", "inline": true},
                    {"name": "Hex",       "value": "%s", "inline": true},
                    {"name": "Altitude",  "value": "%s", "inline": true},
                    {"name": "Speed",     "value": "%s", "inline": true},
                    {"name": "Time",      "value": "%s", "inline": true}
                  ],
                  "footer": {"text": "FlightTracker ADS-B — ALERT"}
                }
                """,
                escapeJson(Category.ALERT.title(flight.flightNumber())),
                Category.ALERT.color,
                escapeJson(type),
                escapeJson(squawk),
                escapeJson(hexIdent),
                escapeJson(altStr),
                escapeJson(spdStr),
                flight.scheduledTime().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        );
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void close() {
        try { httpClient.close(); log.info("Discord notifier closed"); }
        catch (Exception e) { log.error("Error closing Discord notifier: {}", e.getMessage()); }
    }
}
