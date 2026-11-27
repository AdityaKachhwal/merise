CREATE INDEX idx_daily_prices_analysis_security_date
    ON daily_prices (
        UPPER(TRIM(symbol)),
        UPPER(TRIM(COALESCE(isin, ''))),
        trading_date DESC
    )
    WHERE UPPER(TRIM(COALESCE(series, ''))) = 'EQ'
      AND UPPER(TRIM(COALESCE(instrument_type, ''))) = 'STK'
      AND UPPER(TRIM(COALESCE(isin, ''))) LIKE 'INE%'
      AND close IS NOT NULL
      AND close > 0
      AND high IS NOT NULL
      AND low IS NOT NULL;

CREATE TABLE stock_analysis_state (
    symbol VARCHAR(50) NOT NULL,
    isin VARCHAR(20) NOT NULL DEFAULT '',
    latest_processed_date DATE,
    recommendation_json JSONB,
    has_sufficient_history BOOLEAN NOT NULL DEFAULT FALSE,
    passed_liquidity BOOLEAN NOT NULL DEFAULT FALSE,
    passed_filters BOOLEAN NOT NULL DEFAULT FALSE,
    dirty BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (symbol, isin)
);

CREATE INDEX idx_stock_analysis_state_dirty
    ON stock_analysis_state (dirty)
    WHERE dirty = TRUE;

CREATE INDEX idx_stock_analysis_state_shortlist
    ON stock_analysis_state (passed_filters)
    WHERE passed_filters = TRUE;

CREATE TABLE market_analysis_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value VARCHAR(200) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE market_analysis_cache (
    cache_id INTEGER PRIMARY KEY,
    snapshot_json JSONB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
