package com.flightscanner.notification;

import com.flightscanner.analyzer.AircraftTypes;
import com.flightscanner.config.ConfigManager;
import com.flightscanner.geo.ICAOCountry;
import com.flightscanner.model.Flight;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
    private final DiscordRateLimiter rateLimiter;

    private final AtomicLong notificationsSent = new AtomicLong(0);
    private final AtomicLong alertsSent = new AtomicLong(0);
    private volatile java.time.Instant lastSendAt = null;

    public DiscordFlightNotifier() {
        this(ConfigManager.getInstance());
    }

    public DiscordFlightNotifier(ConfigManager config) {
        this.enabled = config.isDiscordEnabled();
        this.webhookUrl = config.getDiscordWebhookUrl();
        this.startupTestMessage = config.isStartupTestMessage();
        this.httpClient = HttpClients.createDefault();
        int ratePerMin = config.getInt("discord.rate.limit.per.minute", 10);
        boolean coalesce = config.getBoolean("discord.rate.coalesce.enabled", true);
        this.rateLimiter = new DiscordRateLimiter(ratePerMin, coalesce);
        if (enabled) {
            log.info("Discord notifier initialized");
            if (!testConnection()) log.warn("Discord connection test failed");
        } else {
            log.info("Discord notifier disabled");
        }
    }

    @Override
    public void sendAlert(Flight flight) {
        sendAlert(flight, null);
    }

    @Override
    public void sendAlert(Flight flight, String note) {
        if (!enabled) return;
        if (!rateLimiter.tryAcquire()) {
            if (rateLimiter.isCoalesceEnabled()) {
                int n = rateLimiter.recordCoalesced();
                log.debug("Rate limit: coalescing {} (total queued: {})", flight.flightNumber(), n);
            } else {
                log.debug("Rate limit: dropped notification for {} (coalesce disabled)", flight.flightNumber());
            }
            return;
        }
        String payload = String.format("{\"embeds\": [%s]}", buildEmbedObject(flight, note));
        sendWebhook(payload);
        notificationsSent.incrementAndGet();
        lastSendAt = java.time.Instant.now();
    }

    @Override
    public void sendCriticalAlert(Flight flight) {
        if (!enabled) return;
        // ALERT embeds bypass the rate limiter — they are rare and critical
        String payload = String.format("{\"embeds\": [%s]}", buildAlertEmbedObject(flight));
        sendWebhook(payload);
        alertsSent.incrementAndGet();
        lastSendAt = java.time.Instant.now();
    }

    /** Returns a point-in-time snapshot of notification counters. */
    public DiscordStats getDiscordStats() {
        return new DiscordStats(
                notificationsSent.get(),
                alertsSent.get(),
                rateLimiter.getCoalescedCount(),
                lastSendAt
        );
    }

    @Override
    public void flushCoalescedSummary() {
        if (!enabled) return;
        int count = rateLimiter.takeCoalescedCount();
        if (count == 0) return;
        String time = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String payload = String.format(
                "{\"embeds\": [{\"title\": \"📊 +%d more flights detected in the last minute (rate limited)\","
                + " \"color\": 9807270,"
                + " \"fields\": [{\"name\": \"Time\", \"value\": \"%s\", \"inline\": true}],"
                + " \"footer\": {\"text\": \"FlightTracker ADS-B\"}}]}",
                count, time);
        sendWebhook(payload);
        log.info("Flushed coalesced summary: {} rate-limited flights", count);
    }

    @Override
    public void sendBatchAlert(List<Flight> flights) {
        if (!enabled || flights.isEmpty()) return;
        var objects = flights.stream().map(f -> buildEmbedObject(f, null)).toArray(String[]::new);
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
        doPost(payload, "message");
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

    private String buildEmbedObject(Flight flight, String note) {
        String type = flight.aircraft() != null ? flight.aircraft().toUpperCase() : "UNKNOWN";
        Category cat = Category.from(AircraftTypes.classify(type));

        String altStr  = flight.altitude() != null ? String.format("%d ft",  flight.altitude()) : "N/A";
        String spdStr  = flight.speed()    != null ? String.format("%d kts", flight.speed())    : "N/A";
        String flag    = ICAOCountry.flagFromHex(flight.hexIdent());
        String titlePrefix = flag.isEmpty() ? "" : flag + " ";

        StringBuilder fields = new StringBuilder();
        fields.append(String.format(
            "{\"name\": \"Aircraft\", \"value\": \"%s\", \"inline\": true},"
          + "{\"name\": \"Altitude\", \"value\": \"%s\", \"inline\": true},"
          + "{\"name\": \"Speed\",    \"value\": \"%s\", \"inline\": true},"
          + "{\"name\": \"Time\",     \"value\": \"%s\", \"inline\": true}",
            escapeJson(type), escapeJson(altStr), escapeJson(spdStr),
            flight.scheduledTime().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        ));
        if (note != null && !note.isBlank()) {
            fields.append(String.format(",{\"name\": \"ℹ️\", \"value\": \"%s\", \"inline\": true}",
                    escapeJson(note)));
        }

        return String.format("""
                {
                  "title": "%s",
                  "color": %d,
                  "fields": [%s],
                  "footer": {"text": "FlightScanner ADS-B"}
                }
                """,
                escapeJson(titlePrefix + cat.title(flight.flightNumber())),
                cat.color,
                fields
        );
    }

    @Override
    public void sendDailyDigest(List<Flight> flights, java.time.LocalDate date) {
        if (!enabled || flights.isEmpty()) return;
        long widebody = flights.stream().filter(f ->
                AircraftTypes.classify(f.aircraft()) == AircraftTypes.AircraftCategory.WIDEBODY).count();
        long military = flights.stream().filter(f ->
                AircraftTypes.classify(f.aircraft()) == AircraftTypes.AircraftCategory.MILITARY).count();
        long bizjet = flights.stream().filter(f ->
                AircraftTypes.classify(f.aircraft()) == AircraftTypes.AircraftCategory.BIZJET).count();
        long total = flights.size();

        String payload = String.format(
            "{\"embeds\": [{\"title\": \"📋 Daily digest — %s\","
          + " \"color\": 5793266,"
          + " \"fields\": ["
          + "   {\"name\": \"Total flights\", \"value\": \"%d\", \"inline\": true},"
          + "   {\"name\": \"🛫 Widebody\",  \"value\": \"%d\", \"inline\": true},"
          + "   {\"name\": \"🪖 Military\",  \"value\": \"%d\", \"inline\": true},"
          + "   {\"name\": \"🛩 Bizjet\",    \"value\": \"%d\", \"inline\": true}"
          + " ],"
          + " \"footer\": {\"text\": \"FlightScanner ADS-B\"}"
          + "}]}",
            date, total, widebody, military, bizjet);
        sendWebhook(payload);
        log.info("Daily digest sent for {}: {} flights ({} widebody, {} military, {} bizjet)",
                date, total, widebody, military, bizjet);
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
