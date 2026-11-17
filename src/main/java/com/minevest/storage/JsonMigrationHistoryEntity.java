package com.minevest.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "json_migration_history")
public class JsonMigrationHistoryEntity {
    @Id
    @Column(name = "migration_key", length = 100)
    private String migrationKey;

    @Column(name = "migrated_at", nullable = false)
    private Instant migratedAt;

    @Column(name = "prices_migrated", nullable = false)
    private int pricesMigrated;

    @Column(name = "sync_attempts_migrated", nullable = false)
    private int syncAttemptsMigrated;

    @Column(name = "analysis_runs_migrated", nullable = false)
    private int analysisRunsMigrated;

    @Column(columnDefinition = "TEXT")
    private String notes;

    protected JsonMigrationHistoryEntity() {}

    public JsonMigrationHistoryEntity(String migrationKey,
                                      Instant migratedAt,
                                      int pricesMigrated,
                                      int syncAttemptsMigrated,
                                      int analysisRunsMigrated,
                                      String notes) {
        this.migrationKey = migrationKey;
        this.migratedAt = migratedAt;
        this.pricesMigrated = pricesMigrated;
        this.syncAttemptsMigrated = syncAttemptsMigrated;
        this.analysisRunsMigrated = analysisRunsMigrated;
        this.notes = notes;
    }

    public String getMigrationKey() { return migrationKey; }
    public Instant getMigratedAt() { return migratedAt; }
    public int getPricesMigrated() { return pricesMigrated; }
    public int getSyncAttemptsMigrated() { return syncAttemptsMigrated; }
    public int getAnalysisRunsMigrated() { return analysisRunsMigrated; }
    public String getNotes() { return notes; }
}
