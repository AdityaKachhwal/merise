package com.minevest.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalysisSnapshot(
        String runId,
        Instant createdAt,
        String status,
        int importedPriceRows,
        int distinctSymbols,
        String note,
        List<StockRecommendation> recommendations
) {
    public static AnalysisSnapshot create(int rows, int symbols, List<StockRecommendation> recommendations, String note) {
        return new AnalysisSnapshot(UUID.randomUUID().toString(), Instant.now(),
                "COMPLETED", rows, symbols, note, List.copyOf(recommendations));
    }
}
