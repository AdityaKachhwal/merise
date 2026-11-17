CREATE TABLE IF NOT EXISTS json_migration_history (
    migration_key VARCHAR(100) PRIMARY KEY,
    migrated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    prices_migrated INTEGER NOT NULL DEFAULT 0,
    sync_attempts_migrated INTEGER NOT NULL DEFAULT 0,
    analysis_runs_migrated INTEGER NOT NULL DEFAULT 0,
    notes TEXT
);
