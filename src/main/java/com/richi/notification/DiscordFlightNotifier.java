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
        MILITARY("\uD83E\uDE96", 10181046, "MILITARY"),        // 🪖
        WIDEBODY("\uD83D\uDEEB", 15158332, "WIDEBODY"),        // 🛫
        BIZJET  ("\uD83D\uDEE9", 15844367, "BIZJET"),          // 🛩️
        COMMERCIAL("\u2708\uFE0F", 3447003, "");               // ✈️

        final String emoji;
        final int color;
        final String label;

        Category(String emoji, int color, String label) {
            this.emoji = emoji;
            this.color = color;
            this.label = label;
        }

        String title(String flightNumber) {
            if (label.isEmpty()) return emoji + " " + flightNumber;
            return emoji + " " + flightNumber + " [" + label + "]";
        }
    }

    // ── Instance ────────────────────────────────────────────────────

    private final String webhookUrl;
    private final boolean enabled;
    private final CloseableHttpClient httpClient;

    public DiscordFlightNotifier() {
        this(ConfigManager.getInstance());
    }

    public DiscordFlightNotifier(ConfigManager config) {
        this.enabled = config.isDiscordEnabled();
        this.webhookUrl = config.getDiscordWebhookUrl();
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
    public void sendBatchAlert(List<Flight> flights) {
        if (!enabled || flights.isEmpty()) return;
        var objects = flights.stream().map(this::buildEmbedObject).toArray(String[]::new);
        String payload = String.format(
            "{\"content\": \"\uD83D\uDEEB **%d new flights detected!**\", \"embeds\": [%s]}",
            flights.size(), String.join(",", objects));
        sendWebhook(payload);
    }

    @Override
    public boolean testConnection() {
        if (!enabled) return false;
        return doPost("{\"content\": \"\u2705 FlightTracker connected to Discord!\"}", "connection test");
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
        Category cat = classify(type);
        
        String altStr = flight.altitude() != null ? String.format("%d ft", flight.altitude()) : "N/A";
        String spdStr = flight.speed() != null ? String.format("%d kts", flight.speed()) : "N/A";
        
        return String.format("""
                {
                  "title": "%s",
                  "color": %d,
                  "fields": [
                    {"name": "Aircraft", "value": "%s", "inline": true},
                    {"name": "Altitude", "value": "%s", "inline": true},
                    {"name": "Speed", "value": "%s", "inline": true},
                    {"name": "Time", "value": "%s", "inline": true}
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

    private Category classify(String type) {
        if (AircraftTypes.MILITARY.contains(type)) return Category.MILITARY;
        if (AircraftTypes.WIDEBODY.contains(type)) return Category.WIDEBODY;
        if (AircraftTypes.BIZJET.contains(type)) return Category.BIZJET;
        return Category.COMMERCIAL;
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
