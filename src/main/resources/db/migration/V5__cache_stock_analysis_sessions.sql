ALTER TABLE stock_analysis_state
    ADD COLUMN IF NOT EXISTS historical_sessions_json JSONB;

COMMENT ON COLUMN stock_analysis_state.historical_sessions_json IS
    'Latest 61 validated historical sessions used as the reusable technical-analysis window. Refreshed only when the security is marked dirty by imported market data.';
