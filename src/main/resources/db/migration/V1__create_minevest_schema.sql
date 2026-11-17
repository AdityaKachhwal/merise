
CREATE TABLE daily_prices (
                              id BIGSERIAL PRIMARY KEY,
                              trading_date DATE NOT NULL,
                              symbol VARCHAR(50) NOT NULL,
                              series VARCHAR(20) NOT NULL,
                              isin VARCHAR(20) NOT NULL DEFAULT '',
                              instrument_type VARCHAR(30),
                              open NUMERIC(20, 4),
                              high NUMERIC(20, 4),
                              low NUMERIC(20, 4),
                              close NUMERIC(20, 4),
                              previous_close NUMERIC(20, 4),
                              volume BIGINT NOT NULL DEFAULT 0,
                              traded_value NUMERIC(24, 4)
);

CREATE UNIQUE INDEX uq_daily_prices_security_date
    ON daily_prices (trading_date, symbol, series, COALESCE(isin, ''));

CREATE INDEX idx_daily_prices_symbol_date
    ON daily_prices (symbol, trading_date);

CREATE TABLE analysis_runs (
                               run_id VARCHAR(36) PRIMARY KEY,
                               created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                               status VARCHAR(30) NOT NULL,
                               imported_price_rows INTEGER NOT NULL,
                               distinct_symbols INTEGER NOT NULL,
                               note TEXT
);

CREATE TABLE stock_recommendations (
                                       id BIGSERIAL PRIMARY KEY,
                                       run_id VARCHAR(36) NOT NULL REFERENCES analysis_runs(run_id) ON DELETE CASCADE,
                                       recommendation_order INTEGER NOT NULL,
                                       symbol VARCHAR(50) NOT NULL,
                                       latest_close NUMERIC(20, 4),
                                       research_score INTEGER NOT NULL,
                                       return_5d DOUBLE PRECISION NOT NULL,
                                       return_20d DOUBLE PRECISION NOT NULL,
                                       return_60d DOUBLE PRECISION NOT NULL,
                                       sma_20 NUMERIC(20, 4),
                                       sma_50 NUMERIC(20, 4),
                                       volume_strength DOUBLE PRECISION NOT NULL,
                                       volatility_20d DOUBLE PRECISION NOT NULL,
                                       drawdown_60d DOUBLE PRECISION NOT NULL,
                                       trend VARCHAR(100),
                                       reason TEXT,
                                       risk TEXT,
                                       rsi_14 DOUBLE PRECISION NOT NULL,
                                       atr_14_percent DOUBLE PRECISION NOT NULL,
                                       average_daily_traded_value_20 NUMERIC(24, 4),
                                       momentum_score INTEGER NOT NULL,
                                       trend_score INTEGER NOT NULL,
                                       volume_score INTEGER NOT NULL,
                                       risk_score INTEGER NOT NULL,
                                       liquidity_score INTEGER NOT NULL,
                                       setup VARCHAR(100)
);

CREATE INDEX idx_recommendations_run
    ON stock_recommendations (run_id, recommendation_order);

CREATE TABLE nse_sync_attempts (
                                   sync_date DATE PRIMARY KEY,
                                   attempted_at TIMESTAMP WITH TIME ZONE,
                                   outcome VARCHAR(30) NOT NULL
);
