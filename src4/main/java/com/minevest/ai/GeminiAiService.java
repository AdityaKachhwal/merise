package com.minevest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Service
public class GeminiAiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-3.8-flash}")
    private String primaryModel;

    // Fallback chain using active models
    private static final List<String> FALLBACK_MODELS = List.of("gemini-3.8-flash", "gemini-3.5-flash");

    public GeminiAiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    public JsonNode reviewPortfolio(JsonNode portfolio) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Gemini API key is not configured."
            );
        }

        String prompt = buildStreamlinedPrompt(portfolio);
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "responseMimeType", "application/json"
                )
        );

        RestClientResponseException lastException = null;

        for (String currentModel : getModelCandidates()) {
            try {
                log.info("Attempting portfolio review using model: {}", currentModel);
                JsonNode response = callGeminiWithExponentialBackoff(requestBody, currentModel);

                JsonNode textNode = response == null ? null :
                        response.path("candidates").path(0).path("content").path("parts").path(0).path("text");

                if (textNode == null || textNode.isMissingNode() || textNode.asText().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned an empty response.");
                }

                return objectMapper.readTree(textNode.asText());

            } catch (RestClientResponseException ex) {
                lastException = ex;
                log.warn("Model {} failed with HTTP {}: {}. Trying next option...",
                        currentModel, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            } catch (Exception ex) {
                log.error("Unexpected error with model {}: {}", currentModel, ex.getMessage());
            }
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Gemini models are currently unavailable. Last error: " +
                        (lastException != null ? lastException.getStatusCode().value() : "Unknown")
        );
    }

    private List<String> getModelCandidates() {
        java.util.LinkedHashSet<String> models = new java.util.LinkedHashSet<>();
        if (primaryModel != null && !primaryModel.isBlank()) {
            models.add(primaryModel);
        }
        models.addAll(FALLBACK_MODELS);
        return new ArrayList<>(models);
    }

    private JsonNode callGeminiWithExponentialBackoff(Map<String, Object> request, String targetModel) {
        int maxAttempts = 3;
        Random random = new Random();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.post()
                        .uri("https://generativelanguage.googleapis.com/v1beta/models/" + targetModel + ":generateContent")
                        .header("x-goog-api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);

            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                boolean temporaryError = status == 429 || status == 503 || status == 500 || status == 502 || status == 504;

                if (!temporaryError || attempt == maxAttempts) {
                    throw ex;
                }

                long baseDelayMillis = (long) (Math.pow(2, attempt - 1) * 1000L);
                long jitter = random.nextInt(400);
                long delayMillis = Math.min(baseDelayMillis + jitter, 10000L);

                log.warn("Gemini model {} unavailable (HTTP {}). Retrying attempt {}/{} after {} ms...",
                        targetModel, status, attempt + 1, maxAttempts, delayMillis);

                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini retry interrupted.");
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini request failed after max retries.");
    }

    private String buildStreamlinedPrompt(JsonNode portfolio) {
        return """
                You are an independent Indian equity research analyst.
                
                TASK:
                Assess EVERY stock in the supplied holdings array focusing on business quality, qualitative competitive moat, and structural industry tailwinds. 
                Do not invent or fabricate live financial ratios, P/E multiples, or balance sheet numbers.
                
                RULES:
                1. Clearly state that quantitative valuation metrics require live verification.
                2. Return ONLY valid JSON matching this exact structure:
                {
                  "summary": "Overview of assessed holdings",
                  "holdings": [
                    {
                      "symbol": "SYMBOL",
                      "businessOverview": "Core business description",
                      "businessQuality": "Qualitative competitive position",
                      "growthDrivers": ["Growth factor"],
                      "valuationView": "Valuation status requires verification",
                      "fundamentalDataStatus": "VERIFY_REQUIRED",
                      "stockOutlook": "POSITIVE, NEUTRAL or NEGATIVE",
                      "action": "HOLD, REVIEW or EXIT",
                      "confidence": "Medium",
                      "reasoning": "Rationale for the outlook",
                      "risks": ["Business risk"],
                      "dataGaps": ["Live financial statements"],
                      "holdingAnalysis": "Assessment of position"
                    }
                  ],
                  "portfolioRisks": [],
                  "alternatives": [],
                  "disclaimer": "Preliminary assessment. Verify financial data before investing."
                }
                
                Input holdings data:
                """ + portfolio.toPrettyString();
    }
}