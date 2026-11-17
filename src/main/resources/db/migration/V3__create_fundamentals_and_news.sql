CREATE TABLE IF NOT EXISTS company_fundamentals (
    symbol VARCHAR(50) PRIMARY KEY,
    company_name VARCHAR(200),
    sector VARCHAR(100),
    market_cap NUMERIC(24, 2),
    pe_ratio NUMERIC(10, 2),
    pb_ratio NUMERIC(10, 2),
    roe NUMERIC(8, 2),
    debt_to_equity NUMERIC(8, 2),
    revenue_growth_yoy NUMERIC(8, 2),
    profit_growth_yoy NUMERIC(8, 2),
    dividend_yield NUMERIC(8, 2),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS stock_news (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    headline TEXT NOT NULL,
    summary TEXT,
    source VARCHAR(100),
    url TEXT,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sentiment_tag VARCHAR(20) NOT NULL DEFAULT 'NEUTRAL'
);

CREATE INDEX IF NOT EXISTS idx_stock_news_symbol_date
    ON stock_news (symbol, published_at DESC);
