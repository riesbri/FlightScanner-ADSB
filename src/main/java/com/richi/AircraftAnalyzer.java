package com.richi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.richi.model.Flight;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import java.util.List;

@Slf4j
public class AircraftAnalyzer {
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private final CloseableHttpClient httpClient = HttpClients.createDefault();
    private final ObjectMapper mapper = new ObjectMapper();

    public List<String> findWidebodyFlightsUsingAI(List<Flight> flights) throws Exception {
        String prompt = "Identify widebody aircraft from this list and return only flight numbers in JSON array format: "
                + mapper.writeValueAsString(flights);

        HttpPost request = new HttpPost(DEEPSEEK_URL);
        request.setHeader("Authorization", "Bearer " + System.getenv("DEEPSEEK_API_KEY"));
        request.setHeader("Content-Type", "application/json");
        System.out.println(request);
        request.setEntity(new StringEntity("""
        {
            "model": "deepseek-ai",
            "messages": [{
                "role": "user",
                "content": "%s"
            }]
        }
        """.formatted(prompt)));

        return httpClient.execute(request, response -> {
            JsonNode root = mapper.readTree(EntityUtils.toString(response.getEntity()));
            return mapper.readValue(
                    root.path("choices").get(0).path("message").path("content").asText(),
                    new TypeReference<List<String>>() {}
            );
        });
    }
}