package com.richi;

import com.richi.model.Flight;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import java.net.URI;

public class TelegramNotifier {
    private static final String TELEGRAM_API = "https://api.telegram.org/bot%s/sendMessage";
    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    public void sendAlert(Flight flight) throws Exception {
        String message = String.format("🚨 Interesting aircraft detected!%nFlight: %s%nAircraft: %s",
                flight.flightNumber(), flight.aircraft());

        URI uri = new URIBuilder(String.format(TELEGRAM_API, System.getenv("TELEGRAM_TOKEN")))
                .addParameter("chat_id", System.getenv("TELEGRAM_CHAT_ID"))
                .addParameter("text", message)
                .build();

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(uri);
            client.execute(request, response -> {
                EntityUtils.consume(response.getEntity());
                return null;
            });
        }
    }
}