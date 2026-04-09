package com.richi.analyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richi.config.ConfigManager;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.util.List;

@Slf4j
public class DeepSeekAircraftAnalyzer {
    
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    
    private final String apiKey;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper mapper;
    
    public DeepSeekAircraftAnalyzer(ConfigManager config) {
        this.apiKey = config.getDeepseekApiKey();
        this.httpClient = HttpClients.createDefault();
        this.mapper = new ObjectMapper();
    }
    
    public List<String> findWidebodyFlights(List<Flight> flights) throws Exception {
        if (flights.isEmpty()) {
            return List.of();
        }
        
        log.info("Calling DeepSeek API to analyze {} flights", flights.size());
        
        String prompt = buildPrompt(flights);
        
        HttpPost request = new HttpPost(DEEPSEEK_URL);
        request.setHeader("Authorization", "Bearer " + apiKey);
        request.setHeader("Content-Type", "application/json");
        
        String jsonBody = String.format("""
            {
                "model": "deepseek-chat",
                "messages": [{
                    "role": "user",
                    "content": %s
                }],
                "temperature": 0.1
            }
            """, mapper.writeValueAsString(prompt));
        
        request.setEntity(new StringEntity(jsonBody));
        
        return httpClient.execute(request, response -> {
            int statusCode = response.getCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            
            if (statusCode != 200) {
                log.error("DeepSeek API returned status {}: {}", statusCode, responseBody);
                throw new RuntimeException("API call failed with status " + statusCode);
            }
            
            JsonNode root = mapper.readTree(responseBody);
            String content = root.path("choices").get(0).path("message").path("content").asText();
            
            // Parse JSON array from content
            try {
                return mapper.readValue(content, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse AI response as JSON, trying to extract flight numbers: {}", content);
                // Fallback: extract flight numbers using regex
                return extractFlightNumbers(content);
            }
        });
    }
    
    private String buildPrompt(List<Flight> flights) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze these flights and identify which ones use WIDEBODY aircraft (Boeing 747, 767, 777, 787, Airbus A330, A340, A350, A380, etc.).\n\n");
        sb.append("Return ONLY a JSON array of flight numbers that are widebody aircraft. Example: [\"AA123\", \"BA456\"]\n\n");
        sb.append("Flights to analyze:\n");
        
        for (Flight f : flights) {
            sb.append(String.format("- %s: %s from %s\n", 
                    f.flightNumber(), f.aircraft(), f.origin()));
        }
        
        sb.append("\nReturn only the JSON array, no other text.");
        
        return sb.toString();
    }
    
    private List<String> extractFlightNumbers(String text) {
        // Extract alphanumeric codes that look like flight numbers
        return java.util.regex.Pattern.compile("[A-Z]{2,3}\\d{1,4}")
                .matcher(text)
                .results()
                .map(m -> m.group())
                .distinct()
                .toList();
    }
    
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.error("Error closing HTTP client: {}", e.getMessage());
        }
    }
}
