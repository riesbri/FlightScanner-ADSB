package com.richi.notification;

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
import java.util.Set;

@Slf4j
public class DiscordFlightNotifier implements FlightNotifier {

    // ── Category definitions ────────────────────────────────────────

    private enum Category {
        MILITARY("\uD83E\uDE96", 10181046, "MILITARY"),        // 🪖
        WIDEBODY("\uD83D\uDEEC", 15158332, "WIDEBODY"),        // 🛬
        BIZJET  ("\uD83D\uDE8E", 15844367, "BIZJET"),          // 🛩️
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

    private static final Set<String> MILITARY_TYPES = Set.of(
        "A400","C130","C17","C5M","C5","C141","C160","CN35","CN95",
        "E3TF","E737","EUFI","F15","F16","F18","F22","F35","F4","F5",
        "H47","H53","H60","H64","K35R","KC10","KC135","KC46",
        "P3","P8","R135","SU27","SU30","SU35","SU57",
        "T38","TOR","TU95","U2","V22"
    );

    private static final Set<String> WIDEBODY_TYPES = Set.of(
        "B747","B748","B74R","B767","B763","B764",
        "B777","B772","B773","B77W","B77L","B77F",
        "B787","B788","B789","B78X",
        "A330","A332","A333","A337","A338","A339",
        "A340","A342","A343","A345","A346",
        "A350","A359","A35K",
        "A380","A388",
        "MD11","MD1F","IL96","IL76","AN124","AN22","AN225"
    );

    private static final Set<String> BIZJET_TYPES = Set.of(
        "C25A","C25B","C25C","C510","C525","C550","C560",
        "C56X","C680","C700","C750","CL30","CL35","CL60",
        "E35L","E50P","E55P","E545","E550","FA50","FA7X",
        "FA8X","F2TH","F900","G150","G200","G280","GALX",
        "GL5T","GL6T","GL7T","GLF4","GLF5","GLF6","GLEX",
        "H25B","H25C","HA4T","HDJT","LJ35","LJ40","LJ45",
        "LJ55","LJ60","LJ70","LJ75","LJ85","PRM1","PC12",
        "PC24","SF50","TBM7","TBM8","TBM9","BE40","BE20",
        "BE9L","BE9T","P180","PAY1","PAY2","PAY3","PAY4"
    );

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
            "{\"content\": \"\uD83D\uDEEC **%d new flights detected!**\", \"embeds\": [%s]}",
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
        if (MILITARY_TYPES.contains(type)) return Category.MILITARY;
        if (WIDEBODY_TYPES.contains(type)) return Category.WIDEBODY;
        if (BIZJET_TYPES.contains(type)) return Category.BIZJET;
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
