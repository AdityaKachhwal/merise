CREATE INDEX IF NOT EXISTS idx_stock_news_market_date
    ON stock_news (published_at DESC)
    WHERE symbol = 'MARKET';

CREATE INDEX IF NOT EXISTS idx_stock_news_url
    ON stock_news (url);