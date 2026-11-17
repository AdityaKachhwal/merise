package com.minevest.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.minevest.history.AnalysisRunEntity;
import com.minevest.history.AnalysisRunRepository;
import com.minevest.history.AnalysisSnapshot;
import com.minevest.marketdata.DailyPrice;
import com.minevest.marketdata.MarketDataService;
import com.minevest.marketdata.NseSyncAttempt;
import com.minevest.marketdata.NseSyncAttemptEntity;
import com.minevest.marketdata.NseSyncAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class JsonToPostgresMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(JsonToPostgresMigrationRunner.class);
    public static final String MIGRATION_KEY = "initial-json-to-postgres-v1";

    private final JsonMigrationHistoryRepository migrationHistoryRepository;
    private final JsonFileStore jsonFileStore;
    private final MarketDataService marketDataService;
    private final NseSyncAttemptRepository syncAttemptRepository;
    private final AnalysisRunRepository analysisRunRepository;
    private final boolean enabled;

    public JsonToPostgresMigrationRunner(
            JsonMigrationHistoryRepository migrationHistoryRepository,
            JsonFileStore jsonFileStore,
            MarketDataService marketDataService,
            NseSyncAttemptRepository syncAttemptRepository,
            AnalysisRunRepository analysisRunRepository,
            @Value("${minevest.migration.json-to-postgres.enabled:true}") boolean enabled) {
        this.migrationHistoryRepository = migrationHistoryRepository;
        this.jsonFileStore = jsonFileStore;
        this.marketDataService = marketDataService;
        this.syncAttemptRepository = syncAttemptRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) {
            log.info("Automatic JSON-to-PostgreSQL migration is disabled via configuration.");
            return;
        }

        if (migrationHistoryRepository.existsById(MIGRATION_KEY)) {
            log.info("JSON-to-PostgreSQL migration '{}' already completed. Skipping.", MIGRATION_KEY);
            return;
        }

        log.info("Starting automatic one-time JSON-to-PostgreSQL migration ('{}')...", MIGRATION_KEY);

        // 1. Migrate prices.json -> daily_prices
        List<DailyPrice> legacyPrices = jsonFileStore.readPrices();
        int pricesMigrated = marketDataService.savePrices(legacyPrices);

        // 2. Migrate nse-sync-state.json -> nse_sync_attempts
        List<NseSyncAttempt> legacyAttempts = jsonFileStore.readJsonList(
                "nse-sync-state.json", new TypeReference<List<NseSyncAttempt>>() {});
        int syncAttemptsMigrated = 0;
        if (!legacyAttempts.isEmpty()) {
            List<NseSyncAttemptEntity> newAttemptEntities = new ArrayList<>();
            for (NseSyncAttempt attempt : legacyAttempts) {
                if (attempt != null && attempt.date() != null && !syncAttemptRepository.existsById(attempt.date())) {
                    newAttemptEntities.add(new NseSyncAttemptEntity(attempt));
                }
            }
            if (!newAttemptEntities.isEmpty()) {
                syncAttemptRepository.saveAll(newAttemptEntities);
                syncAttemptsMigrated = newAttemptEntities.size();
            }
        }

        // 3. Migrate history.json -> analysis_runs + stock_recommendations
        List<AnalysisSnapshot> legacyRuns = jsonFileStore.readJsonList(
                "history.json", new TypeReference<List<AnalysisSnapshot>>() {});
        int analysisRunsMigrated = 0;
        if (!legacyRuns.isEmpty()) {
            List<AnalysisRunEntity> newRunEntities = new ArrayList<>();
            for (AnalysisSnapshot snapshot : legacyRuns) {
                if (snapshot != null && snapshot.runId() != null && !analysisRunRepository.existsById(snapshot.runId())) {
                    newRunEntities.add(new AnalysisRunEntity(snapshot));
                }
            }
            if (!newRunEntities.isEmpty()) {
                analysisRunRepository.saveAll(newRunEntities);
                analysisRunsMigrated = newRunEntities.size();
            }
        }

        String note = String.format(
                "Migrated %d price rows (from %d JSON rows), %d NSE sync attempts, and %d analysis runs.",
                pricesMigrated, legacyPrices.size(), syncAttemptsMigrated, analysisRunsMigrated
        );

        migrationHistoryRepository.save(new JsonMigrationHistoryEntity(
                MIGRATION_KEY,
                Instant.now(),
                pricesMigrated,
                syncAttemptsMigrated,
                analysisRunsMigrated,
                note
        ));

        log.info("Completed one-time JSON-to-PostgreSQL migration: {}", note);
    }
}
