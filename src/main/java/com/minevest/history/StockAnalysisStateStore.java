package com.minevest.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.minevest.marketdata.DailyPrice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StockAnalysisStateStore {
    private static final int CACHE_ID = 1;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public StockAnalysisStateStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void markDirty(List<DailyPrice> prices) {
        if (prices != null && !prices.isEmpty()) {
            Set<SecurityKey> securities = new LinkedHashSet();

            for(DailyPrice price : prices) {
                if (price != null && price.symbol() != null && !price.symbol().isBlank() && price.isin() != null && price.isin().trim().toUpperCase().startsWith("INE") && "STK".equalsIgnoreCase(price.instrumentType()) && "EQ".equalsIgnoreCase(price.series())) {
                    securities.add(new SecurityKey(price.symbol().trim().toUpperCase(), price.isin().trim().toUpperCase()));
                }
            }

            if (!securities.isEmpty()) {
                this.jdbc.batchUpdate("INSERT INTO stock_analysis_state (symbol, isin, dirty)\nVALUES (?, ?, TRUE)\nON CONFLICT (symbol, isin)\nDO UPDATE SET\n    dirty = TRUE,\n    updated_at = NOW()\n", securities, 250, (ps, key) -> {
                    ps.setString(1, key.symbol());
                    ps.setString(2, key.isin());
                });
            }
        }
    }

    @Transactional
    public void seedEligibleSecuritiesIfEmpty() {
        Integer initialized = (Integer)this.jdbc.queryForObject("SELECT COUNT(*) FROM market_analysis_metadata\nWHERE metadata_key = 'stock_analysis_state_seeded'\n  AND metadata_value = 'true'\n", Integer.class);
        if (initialized == null || initialized <= 0) {
            this.jdbc.update("INSERT INTO stock_analysis_state (symbol, isin, dirty)\nSELECT DISTINCT\n    UPPER(TRIM(symbol)),\n    UPPER(TRIM(COALESCE(isin, ''))),\n    TRUE\nFROM daily_prices\nWHERE UPPER(TRIM(COALESCE(instrument_type, ''))) = 'STK'\n  AND UPPER(TRIM(COALESCE(series, ''))) = 'EQ'\n  AND UPPER(TRIM(COALESCE(isin, ''))) LIKE 'INE%'\n  AND symbol IS NOT NULL\n  AND TRIM(symbol) <> ''\n  AND close IS NOT NULL\n  AND close > 0\n  AND high IS NOT NULL\n  AND low IS NOT NULL\nON CONFLICT (symbol, isin) DO NOTHING\n");
            this.jdbc.update("INSERT INTO market_analysis_metadata\n    (metadata_key, metadata_value, updated_at)\nVALUES ('stock_analysis_state_seeded', 'true', NOW())\nON CONFLICT (metadata_key)\nDO UPDATE SET\n    metadata_value = 'true',\n    updated_at = NOW()\n");
        }
    }

    public List<SecurityKey> findSecuritiesWithHistory() {
        return this.jdbc.query("SELECT symbol, isin\nFROM stock_analysis_state\nWHERE has_sufficient_history = TRUE\nORDER BY symbol, isin\n", (rs, rowNum) -> new SecurityKey(rs.getString("symbol"), rs.getString("isin")));
    }

    public Map<SecurityKey, List<DailyPrice>> findLatestSessionsForBatch(List<SecurityKey> securities, int limit) {
        if (securities == null || securities.isEmpty()) return Map.of();

        StringBuilder values = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < securities.size(); i++) {
            if (i > 0) values.append(",");
            values.append("(?, ?)");
            args.add(securities.get(i).symbol());
            args.add(securities.get(i).isin());
        }

        String sql = """
                WITH target(symbol, isin) AS (VALUES %s)
                SELECT s.symbol, s.isin, s.historical_sessions_json::text
                FROM stock_analysis_state s
                JOIN target t ON t.symbol = s.symbol AND t.isin = s.isin
                WHERE s.has_sufficient_history = TRUE
                  AND s.historical_sessions_json IS NOT NULL
                """.formatted(values);
        Map<SecurityKey, List<DailyPrice>> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            SecurityKey key = new SecurityKey(rs.getString("symbol"), rs.getString("isin"));
            try {
                List<DailyPrice> prices = objectMapper.readValue(
                        rs.getString("historical_sessions_json"),
                        new TypeReference<List<DailyPrice>>() {});
                if (prices.size() > limit) prices = prices.subList(prices.size() - limit, prices.size());
                result.put(key, new ArrayList<>(prices));
            } catch (Exception ex) {
                throw new IllegalStateException("Could not read cached historical sessions for " + key.symbol(), ex);
            }
        }, args.toArray());
        return result;
    }

    @Transactional
    public void markLiveQuoteUnavailable(SecurityKey key) {
        this.jdbc.update("UPDATE stock_analysis_state\nSET passed_filters = FALSE, dirty = FALSE, updated_at = NOW()\nWHERE symbol = ? AND isin = ?\n", new Object[]{key.symbol(), key.isin()});
    }

    public List<SecurityKey> findDirtySecurities(int limit) {
        return this.jdbc.query("SELECT symbol, isin\nFROM stock_analysis_state\nWHERE dirty = TRUE\nORDER BY symbol, isin\nLIMIT ?\n", (rs, rowNum) -> new SecurityKey(rs.getString("symbol"), rs.getString("isin")), new Object[]{limit});
    }

    public List<DailyPrice> findLatestSessions(String symbol, String isin, int limit) {
        return this.jdbc.query("SELECT DISTINCT ON (trading_date)\n    trading_date, symbol, series, isin, instrument_type,\n    open, high, low, close, previous_close, volume, traded_value\nFROM daily_prices\nWHERE UPPER(TRIM(symbol)) = ?\n  AND UPPER(TRIM(COALESCE(isin, ''))) = ?\n  AND UPPER(TRIM(COALESCE(series, ''))) = 'EQ'\n  AND UPPER(TRIM(COALESCE(instrument_type, ''))) = 'STK'\n  AND UPPER(TRIM(COALESCE(isin, ''))) LIKE 'INE%'\n  AND close IS NOT NULL\n  AND close > 0\n  AND high IS NOT NULL\n  AND low IS NOT NULL\nORDER BY trading_date DESC,\n    CASE\n        WHEN traded_value IS NOT NULL AND traded_value > 0\n            THEN traded_value\n        ELSE close * GREATEST(volume, 0)\n    END DESC\nLIMIT ?\n", (rs, rowNum) -> this.mapDailyPrice(rs), new Object[]{symbol.toUpperCase(), isin.toUpperCase(), limit});
    }

    private DailyPrice mapDailyPrice(ResultSet rs) throws SQLException {
        return new DailyPrice((LocalDate)rs.getObject("trading_date", LocalDate.class), rs.getString("symbol"), rs.getString("series"), rs.getString("isin"), rs.getString("instrument_type"), rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getBigDecimal("previous_close"), rs.getLong("volume"), rs.getBigDecimal("traded_value"));
    }

    @Transactional
    public void saveProcessedState(
            SecurityKey key,
            LocalDate latestDate,
            StockRecommendation recommendation,
            boolean enoughHistory,
            boolean passedLiquidity,
            boolean passedFilters,
            List<DailyPrice> historicalSessions) throws Exception {
        String recommendationJson = recommendation == null ? null : objectMapper.writeValueAsString(recommendation);
        String historyJson = historicalSessions == null ? null : objectMapper.writeValueAsString(historicalSessions);
        jdbc.update("""
                UPDATE stock_analysis_state
                SET latest_processed_date = ?,
                    recommendation_json = CAST(? AS JSONB),
                    historical_sessions_json = CAST(? AS JSONB),
                    has_sufficient_history = ?,
                    passed_liquidity = ?,
                    passed_filters = ?,
                    dirty = FALSE,
                    updated_at = NOW()
                WHERE symbol = ? AND isin = ?
                """,
                latestDate, recommendationJson, historyJson,
                enoughHistory, passedLiquidity, passedFilters, key.symbol(), key.isin());
    }

    public List<StockRecommendation> findShortlistCandidates(LocalDate staleCutoff) {
        return this.jdbc.query("SELECT recommendation_json::text\nFROM stock_analysis_state\nWHERE passed_filters = TRUE\n  AND recommendation_json IS NOT NULL\n  AND latest_processed_date >= ?\n", (rs, rowNum) -> {
            try {
                return (StockRecommendation)this.objectMapper.readValue(rs.getString(1), StockRecommendation.class);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not read saved stock analysis state", ex);
            }
        }, new Object[]{staleCutoff});
    }

    public int countSecurities() {
        Integer count = (Integer)this.jdbc.queryForObject("SELECT COUNT(*) FROM stock_analysis_state", Integer.class);
        return count == null ? 0 : count;
    }

    public int countWithSufficientHistory() {
        Integer count = (Integer)this.jdbc.queryForObject("SELECT COUNT(*) FROM stock_analysis_state\nWHERE has_sufficient_history = TRUE\n", Integer.class);
        return count == null ? 0 : count;
    }

    public int countPassedLiquidity(LocalDate staleCutoff) {
        Integer count = (Integer)this.jdbc.queryForObject("SELECT COUNT(*) FROM stock_analysis_state\nWHERE passed_liquidity = TRUE\n  AND latest_processed_date >= ?\n", Integer.class, new Object[]{staleCutoff});
        return count == null ? 0 : count;
    }

    public LocalDate findLatestMarketDate() {
        return (LocalDate)this.jdbc.queryForObject("SELECT MAX(trading_date) FROM daily_prices", LocalDate.class);
    }

    public AnalysisSnapshot findCachedSnapshot() throws Exception {
        List<String> values = this.jdbc.query("SELECT snapshot_json::text FROM market_analysis_cache WHERE cache_id = ?", (rs, rowNum) -> rs.getString(1), new Object[]{1});
        return values.isEmpty() ? null : (AnalysisSnapshot)this.objectMapper.readValue((String)values.get(0), AnalysisSnapshot.class);
    }

    @Transactional
    public void saveCachedSnapshot(AnalysisSnapshot snapshot) throws Exception {
        String json = this.objectMapper.writeValueAsString(snapshot);
        this.jdbc.update("INSERT INTO market_analysis_cache\n    (cache_id, snapshot_json, updated_at)\nVALUES (?, CAST(? AS JSONB), NOW())\nON CONFLICT (cache_id)\nDO UPDATE SET\n    snapshot_json = EXCLUDED.snapshot_json,\n    updated_at = NOW()\n", new Object[]{1, json});
    }

    public static record SecurityKey(String symbol, String isin) {
    }
}
