CREATE TABLE IF NOT EXISTS fincrux_financial_snapshots (
    symbol VARCHAR(50) NOT NULL,
    statement_type VARCHAR(20) NOT NULL,
    company_name VARCHAR(250),
    provider_updated_at VARCHAR(100),
    fetched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (symbol, statement_type),
    CONSTRAINT chk_fincrux_statement_type
        CHECK (statement_type IN ('consolidated', 'standalone'))
);

CREATE TABLE IF NOT EXISTS fincrux_daily_usage (
    usage_date DATE PRIMARY KEY,
    call_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_fincrux_daily_call_count CHECK (call_count >= 0)
);
