package com.minevest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "minevest.ai.provider", havingValue = "gemini")
public class GeminiAiService implements PortfolioReviewProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-3.8-flash}")
    private String model;

    public GeminiAiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public JsonNode reviewPortfolio(JsonNode portfolio) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini API key is not configured.");
        }
        try {
            String prompt = "Assess every stock in the supplied holdings JSON independently and return valid JSON matching the portfolio review schema:\n"
                    + portfolio.toPrettyString();

            Map<String, Object> request = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("temperature", 0.2, "responseMimeType", "application/json")
            );

            JsonNode response = restClient.post()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent")
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode textNode = response == null ? null :
                    response.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (textNode == null || textNode.isMissingNode() || textNode.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an empty response.");
            }
            return objectMapper.readTree(textNode.asText());
        } catch (RestClientResponseException ex) {
            log.error("Gemini API returned HTTP {}: {}", ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini API error: " + ex.getStatusCode().value());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Stock assessment failed", ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to complete stock assessment.");
        }
    }
}
