package com.richi.notification;

import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class TelegramFlightNotifier implements FlightNotifier {
    
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";
    
    private final String botToken;
    private final String chatId;
    private final boolean enabled;
    private final CloseableHttpClient httpClient;
    
    public TelegramFlightNotifier() {
        this(ConfigManager.getInstance());
    }
    
    public TelegramFlightNotifier(ConfigManager config) {
        this.enabled = config.isTelegramEnabled();
        this.botToken = config.getTelegramBotToken();
        this.chatId = config.getTelegramChatId();
        this.httpClient = HttpClients.createDefault();
        
        if (enabled) {
            log.info("Telegram notifier initialized");
            if (!testConnection()) {
                log.warn("Telegram connection test failed");
            }
        } else {
            log.info("Telegram notifier disabled");
        }
    }
    
    @Override
    public void sendAlert(Flight flight) {
        if (!enabled) {
            log.debug("Telegram notifications disabled, skipping alert for {}", flight.flightNumber());
            return;
        }
        
        String message = formatFlightMessage(flight);
        sendMessage(message);
    }
    
    @Override
    public void sendBatchAlert(List<Flight> flights) {
        if (!enabled || flights.isEmpty()) {
            return;
        }
        
        String message = flights.stream()
                .map(this::formatFlightMessage)
                .collect(Collectors.joining("\n\n---\n\n"));
        
        String header = String.format("🛬 *%d New Flights Detected*\n\n", flights.size());
        sendMessage(header + message);
    }
    
    @Override
    public boolean testConnection() {
        if (!enabled) {
            return false;
        }
        
        try {
            URI uri = buildUri("🔄 FlightTracker bot connected successfully!");
            HttpGet request = new HttpGet(uri);
            
            return httpClient.execute(request, response -> {
                int statusCode = response.getCode();
                EntityUtils.consume(response.getEntity());
                
                if (statusCode == 200) {
                    log.info("Telegram connection test successful");
                    return true;
                } else {
                    log.error("Telegram API returned status: {}", statusCode);
                    return false;
                }
            });
        } catch (Exception e) {
            log.error("Telegram connection test failed: {}", e.getMessage());
            return false;
        }
    }
    
    private void sendMessage(String message) {
        try {
            URI uri = buildUri(message);
            HttpGet request = new HttpGet(uri);
            
            httpClient.execute(request, response -> {
                int statusCode = response.getCode();
                EntityUtils.consume(response.getEntity());
                
                if (statusCode == 200) {
                    log.info("Telegram message sent successfully");
                } else {
                    log.error("Failed to send Telegram message, status: {}", statusCode);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Error sending Telegram message: {}", e.getMessage());
        }
    }
    
    private URI buildUri(String message) throws URISyntaxException {
        return new URIBuilder(String.format(TELEGRAM_API, botToken))
                .addParameter("chat_id", chatId)
                .addParameter("text", message)
                .addParameter("parse_mode", "Markdown")
                .build();
    }
    
    private String formatFlightMessage(Flight flight) {
        return String.format(
                "✈️ *%s*%n" +
                "🛫 Origin: %s%n" +
                "🛩️ Aircraft: %s%n" +
                "🕐 Scheduled: %s",
                escapeMarkdown(flight.flightNumber()),
                escapeMarkdown(flight.origin()),
                escapeMarkdown(flight.aircraft()),
                flight.scheduledTime().toLocalTime()
        );
    }
    
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                   .replace("*", "\\*")
                   .replace("[", "\\[")
                   .replace("]", "\\]");
    }
    
    public void close() {
        try {
            httpClient.close();
            log.info("Telegram notifier closed");
        } catch (Exception e) {
            log.error("Error closing Telegram notifier: {}", e.getMessage());
        }
    }
}
