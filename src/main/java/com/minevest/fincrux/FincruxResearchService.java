package com.minevest.fincrux;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

@Service
public class FincruxResearchService {

    private final FincruxClient client;
    private final FincruxSnapshotRepository snapshotRepository;
    private final FincruxDailyUsageService dailyUsageService;
    private final ObjectMapper objectMapper;

    public FincruxResearchService(
            FincruxClient client,
            FincruxSnapshotRepository snapshotRepository,
            FincruxDailyUsageService dailyUsageService,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.snapshotRepository = snapshotRepository;
        this.dailyUsageService = dailyUsageService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCached(String symbol, String statementType) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedType = normalizeStatementType(statementType);

        FincruxSnapshotEntity entity = snapshotRepository.findById(
                new FincruxSnapshotEntity.SnapshotId(normalizedSymbol, normalizedType)
        ).orElseThrow(() -> new IllegalArgumentException(
                "No cached Fincrux data found for " + normalizedSymbol
                        + " (" + normalizedType + "). Call the refresh endpoint first."
        ));

        return toResponse(entity, false);
    }

    @Transactional
    public Map<String, Object> refresh(String symbol, String statementType) {
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedType = normalizeStatementType(statementType);

        // This commits independently, so failed provider calls still count toward the daily cap.
        dailyUsageService.recordCallOrThrow();

        JsonNode payload = client.fetchFinancials(normalizedSymbol, normalizedType);

        FincruxSnapshotEntity entity = snapshotRepository.findById(
                new FincruxSnapshotEntity.SnapshotId(normalizedSymbol, normalizedType)
        ).orElseGet(FincruxSnapshotEntity::new);

        entity.setSymbol(normalizedSymbol);
        entity.setStatementType(normalizedType);
        entity.setCompanyName(payload.path("company").asText(null));
        entity.setProviderUpdatedAt(payload.path("last_updated_at").asText(null));
        entity.setFetchedAt(Instant.now());

        try {
            entity.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not store Fincrux response.", ex);
        }

        snapshotRepository.save(entity);
        return toResponse(entity, true);
    }

    private Map<String, Object> toResponse(FincruxSnapshotEntity entity, boolean refreshed) {
        try {
            return Map.of(
                    "symbol", entity.getSymbol(),
                    "companyName", entity.getCompanyName() == null ? "" : entity.getCompanyName(),
                    "statementType", entity.getStatementType(),
                    "source", "Fincrux",
                    "fetchedAt", entity.getFetchedAt(),
                    "providerUpdatedAt", entity.getProviderUpdatedAt() == null ? "" : entity.getProviderUpdatedAt(),
                    "refreshed", refreshed,
                    "data", objectMapper.readTree(entity.getPayload())
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored Fincrux response is not valid JSON.", ex);
        }
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol is required.");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeStatementType(String statementType) {
        String value = statementType == null || statementType.isBlank()
                ? "consolidated"
                : statementType.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("consolidated") && !value.equals("standalone")) {
            throw new IllegalArgumentException("statementType must be consolidated or standalone.");
        }
        return value;
    }
}
