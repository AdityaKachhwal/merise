package com.minevest.history;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Entity
@Table(name = "analysis_runs")
public class AnalysisRunEntity {
    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "imported_price_rows", nullable = false)
    private int importedPriceRows;

    @Column(name = "distinct_symbols", nullable = false)
    private int distinctSymbols;

    @Column(columnDefinition = "TEXT")
    private String note;

    @OneToMany(mappedBy = "analysisRun", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("recommendationOrder ASC")
    private List<StockRecommendationEntity> recommendations = new ArrayList<>();

    protected AnalysisRunEntity() {}

    public AnalysisRunEntity(AnalysisSnapshot snapshot) {
        this.runId = snapshot.runId();
        this.createdAt = snapshot.createdAt();
        this.status = snapshot.status();
        this.importedPriceRows = snapshot.importedPriceRows();
        this.distinctSymbols = snapshot.distinctSymbols();
        this.note = snapshot.note();
        if (snapshot.recommendations() != null) {
            for (int i = 0; i < snapshot.recommendations().size(); i++) {
                this.recommendations.add(new StockRecommendationEntity(this, i, snapshot.recommendations().get(i)));
            }
        }
    }

    public AnalysisSnapshot toSnapshot() {
        List<StockRecommendation> recs = recommendations == null
                ? List.of()
                : recommendations.stream()
                        .sorted(Comparator.comparingInt(StockRecommendationEntity::getRecommendationOrder))
                        .map(StockRecommendationEntity::toRecommendation)
                        .toList();
        return new AnalysisSnapshot(
                runId,
                createdAt,
                status,
                importedPriceRows,
                distinctSymbols,
                note,
                recs
        );
    }

    public String getRunId() { return runId; }
    public Instant getCreatedAt() { return createdAt; }
}
