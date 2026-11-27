package com.minevest.fincrux;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class FincruxClient {

    private final RestClient restClient;
    private final String apiKey;

    public FincruxClient(
            RestClient.Builder builder,
            @Value("${fincrux.api.base-url:https://api.fincrux.org}") String baseUrl,
            @Value("${fincrux.api.key:}") String apiKey
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public JsonNode fetchFinancials(String symbol, String statementType) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Fincrux API key is missing. Configure it in application-local.yml."
            );
        }

        String uri = UriComponentsBuilder
                .fromPath("/api/financials/{symbol}")
                .queryParam("api_key", apiKey)
                .queryParam("statement_type", statementType)
                .encode()
                .buildAndExpand(symbol)
                .toUriString();

        try {
            JsonNode response = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || (response.has("success") && !response.path("success").asBoolean())) {
                String message = response == null
                        ? "Fincrux returned an empty response."
                        : response.path("message").asText("Fincrux reported an unsuccessful response.");
                throw new IllegalStateException(message);
            }
            return response;
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException(
                    "Fincrux request failed with HTTP " + ex.getStatusCode().value()
                            + ". Check the symbol, API key and provider status."
            );
        }
    }
}
