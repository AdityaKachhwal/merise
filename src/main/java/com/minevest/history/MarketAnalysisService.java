package com.minevest.history;

import com.minevest.marketdata.DailyPrice;
import com.minevest.marketdata.MarketDataService;
import com.minevest.marketdata.LiveMarketQuote;
import com.minevest.marketdata.NseMcpLiveQuoteService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;

@Service
public class MarketAnalysisService {

    private static final int MIN_SESSIONS = 61;
    private static final int PROCESS_BATCH_SIZE = 100;
    private static final double MIN_AVERAGE_DAILY_VALUE = 10_000_000.0;
    private static final double MAX_DAILY_VOLATILITY = 5.5;

    private final StockAnalysisStateStore stateStore;
    private final MarketDataService marketData;
    private final NseMcpLiveQuoteService liveQuoteService;

    public MarketAnalysisService(
            StockAnalysisStateStore stateStore,
            MarketDataService marketData,
            NseMcpLiveQuoteService liveQuoteService) {
        this.stateStore = stateStore;
        this.marketData = marketData;
        this.liveQuoteService = liveQuoteService;
    }

    public synchronized AnalysisSnapshot run() throws Exception {

        stateStore.seedEligibleSecuritiesIfEmpty();

        LocalDate latestMarketDate = stateStore.findLatestMarketDate();
        if (latestMarketDate == null) {
            AnalysisSnapshot cached = stateStore.findCachedSnapshot();
            if (cached != null) return cached;
            AnalysisSnapshot empty = AnalysisSnapshot.create(
                    Math.toIntExact(marketData.totalRows()),
                    stateStore.countSecurities(),
                    List.of(),
                    "No NSE market data is available for analysis yet.");
            stateStore.saveCachedSnapshot(empty);
            return empty;
        }

        LocalDate staleCutoff = latestMarketDate.minusDays(4);

        // First process newly imported historical data. This remains incremental.
        while (true) {
            List<StockAnalysisStateStore.SecurityKey> dirty =
                    stateStore.findDirtySecurities(PROCESS_BATCH_SIZE);
            if (dirty.isEmpty()) break;
            for (StockAnalysisStateStore.SecurityKey key : dirty) {
                processSecurity(key, latestMarketDate, staleCutoff);
            }
        }

        // Refresh current NSE quotes, but reuse each security's persisted 61-session
        // historical window. Only dirty securities read daily_prices again.
        Map<String, LiveMarketQuote> quotes = liveQuoteService.fetchAllEquityQuotes();
        List<StockAnalysisStateStore.SecurityKey> securities =
                stateStore.findSecuritiesWithHistory();

        int liveQuotesApplied = 0;
        int missingLiveQuotes = 0;
        String latestQuoteTimestamp = null;

        for (int offset = 0; offset < securities.size(); offset += PROCESS_BATCH_SIZE) {
            List<StockAnalysisStateStore.SecurityKey> batch = securities.subList(
                    offset, Math.min(offset + PROCESS_BATCH_SIZE, securities.size()));
            Map<StockAnalysisStateStore.SecurityKey, List<DailyPrice>> histories =
                    stateStore.findLatestSessionsForBatch(batch, MIN_SESSIONS);

            for (StockAnalysisStateStore.SecurityKey key : batch) {
                LiveMarketQuote quote = quotes.get(key.symbol().toUpperCase(Locale.ROOT));
                List<DailyPrice> newestFirst = histories.get(key);

                if (quote == null || newestFirst == null || newestFirst.size() < MIN_SESSIONS) {
                    stateStore.markLiveQuoteUnavailable(key);
                    missingLiveQuotes++;
                    continue;
                }

                // Cached sessions are persisted oldest-first; do not reverse them.
                List<DailyPrice> history = uniqueSorted(new ArrayList<>(newestFirst));

                LocalDate historicalLatest = history.get(history.size() - 1).tradingDate();
                if (quote.marketDate() == null || quote.marketDate().isBefore(historicalLatest)) {
                    // Never replace newer stored EOD data with an older cached quote.
                    stateStore.markLiveQuoteUnavailable(key);
                    missingLiveQuotes++;
                    continue;
                }

                List<DailyPrice> currentPrices = withCurrentQuote(history, quote);
                if (currentPrices.size() < MIN_SESSIONS) {
                    stateStore.markLiveQuoteUnavailable(key);
                    missingLiveQuotes++;
                    continue;
                }

                StockRecommendation candidate = evaluate(key.symbol(), currentPrices, true);
                boolean passedLiquidity = candidate.averageDailyTradedValue20() != null
                        && candidate.averageDailyTradedValue20().doubleValue() >= MIN_AVERAGE_DAILY_VALUE;
                boolean passedFilters = passesCategoryFilters(candidate, passedLiquidity);

                stateStore.saveProcessedState(
                        key, quote.marketDate(), candidate, true, passedLiquidity, passedFilters, history);
                liveQuotesApplied++;
                if (quote.latestTimestamp() != null) {
                    latestQuoteTimestamp = quote.latestTimestamp();
                }
            }
        }

        List<StockRecommendation> candidates = stateStore.findShortlistCandidates(staleCutoff);
        Comparator<StockRecommendation> scoreOrder =
                Comparator.comparingInt(StockRecommendation::researchScore).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                StockRecommendation::return20d).reversed())
                        .thenComparing(StockRecommendation::symbol);
        List<StockRecommendation> top = new ArrayList<>();
        for (String direction : List.of("POSITIVE", "NEGATIVE", "CHANGING")) {
            candidates.stream()
                    .filter(candidate -> direction.equals(candidate.momentumDirection()))
                    .sorted(scoreOrder)
                    .limit(5)
                    .forEach(top::add);
        }
        int enoughHistory = stateStore.countWithSufficientHistory();
        int passedLiquidity = stateStore.countPassedLiquidity(staleCutoff);
        int securityCount = stateStore.countSecurities();

        String note = String.format(
                Locale.US,
                "Analysis refreshed with NSE current market quotes (delayed approximately 1–5 minutes) "
                        + "and the latest 61 historical sessions. Historical data through %s. "
                        + "Live quotes applied to %d securities; %d securities had no usable current quote "
                        + "and were excluded from the current shortlist. Latest quote timestamp: %s. "
                        + "%d securities had sufficient history; %d passed the liquidity check. "
                        + "Scores combine momentum (35), trend (25), volume (10), risk (20) and liquidity (10). "
                        + "Current intraday price is used as a provisional latest observation; "
                        + "intraday volume is excluded from completed-session volume/liquidity averages. "
                        + "Raw bhavcopy prices are not adjusted for splits, bonuses or dividends.",
                latestMarketDate,
                liveQuotesApplied,
                missingLiveQuotes,
                latestQuoteTimestamp == null ? "not supplied" : latestQuoteTimestamp,
                enoughHistory,
                passedLiquidity);

        AnalysisSnapshot cached = stateStore.findCachedSnapshot();
        // Avoid duplicate history entries when a quote refresh returns identical
        // recommendations. A changed current price changes the recommendation snapshot.
        if (cached != null && cached.recommendations().equals(top)) {
            return cached;
        }

        AnalysisSnapshot snapshot = AnalysisSnapshot.create(
                Math.toIntExact(marketData.totalRows()), securityCount, top, note);
        stateStore.saveCachedSnapshot(snapshot);
        return snapshot;
    }

    private List<DailyPrice> withCurrentQuote(
            List<DailyPrice> history, LiveMarketQuote quote) {
        List<DailyPrice> result = new ArrayList<>(history);
        DailyPrice previous = result.get(result.size() - 1);

        BigDecimal open = quote.open() == null ? previous.open() : quote.open();
        BigDecimal high = quote.high() == null ? quote.lastPrice() : quote.high();
        BigDecimal low = quote.low() == null ? quote.lastPrice() : quote.low();
        BigDecimal previousClose = quote.previousClose() == null
                ? previous.close() : quote.previousClose();
        long volume = quote.volume() > 0 ? quote.volume() : previous.volume();

        DailyPrice liveBar = new DailyPrice(
                quote.marketDate(), previous.symbol(), previous.series(), previous.isin(),
                previous.instrumentType(), open, high, low, quote.lastPrice(),
                previousClose, volume, null);

        if (quote.marketDate().equals(previous.tradingDate())) {
            result.set(result.size() - 1, liveBar);
        } else {
            result.add(liveBar);
        }
        return result;
    }

    private void processSecurity(
            StockAnalysisStateStore.SecurityKey key,
            LocalDate latestMarketDate,
            LocalDate staleCutoff) throws Exception {

        List<DailyPrice> prices = new ArrayList<>(
                stateStore.findLatestSessions(key.symbol(), key.isin(), MIN_SESSIONS)
        );

        // SQL returns newest first. Indicators expect oldest first.
        Collections.reverse(prices);
        prices = uniqueSorted(prices);

        LocalDate latestDate = prices.isEmpty()
                ? null
                : prices.get(prices.size() - 1).tradingDate();

        if (prices.size() < MIN_SESSIONS) {
            stateStore.saveProcessedState(
                    key, latestDate, null, false, false, false, prices);
            return;
        }

        DailyPrice latest = prices.get(prices.size() - 1);

        // Retain the original stale-security exclusion.
        if (latestMarketDate != null && latest.tradingDate().isBefore(staleCutoff)) {
            stateStore.saveProcessedState(
                    key, latestDate, null, true, false, false, prices);
            return;
        }

        StockRecommendation candidate = evaluate(latest.symbol(), prices, false);

        if (candidate == null) {
            stateStore.saveProcessedState(
                    key, latestDate, null, true, false, false, prices);
            return;
        }

        boolean passedLiquidity =
                candidate.averageDailyTradedValue20() != null
                        && candidate.averageDailyTradedValue20().doubleValue()
                        >= MIN_AVERAGE_DAILY_VALUE;

        boolean passedFilters = passesCategoryFilters(candidate, passedLiquidity);

        // Persist even when the stock fails liquidity/shortlist filters.
        // That way every eligible security has a reusable calculated state.
        stateStore.saveProcessedState(
                key,
                latestDate,
                candidate,
                true,
                passedLiquidity,
                passedFilters,
                prices
        );
    }

    public void updateCachedSnapshot(AnalysisSnapshot snapshot) throws Exception {
        stateStore.saveCachedSnapshot(snapshot);
    }

    private boolean isOrdinaryEquity(DailyPrice p) {
        return p != null
                && p.tradingDate() != null
                && p.symbol() != null
                && !p.symbol().isBlank()
                && "STK".equalsIgnoreCase(p.instrumentType())
                && "EQ".equalsIgnoreCase(p.series())
                && p.isin() != null
                && p.isin().startsWith("INE")
                && p.close() != null
                && p.close().signum() > 0
                && p.high() != null
                && p.low() != null;
    }

    private List<DailyPrice> uniqueSorted(List<DailyPrice> input) {

        Map<LocalDate, DailyPrice> byDate = new TreeMap<>();

        for (DailyPrice p : input) {

            DailyPrice old = byDate.get(p.tradingDate());

            if (old == null || tradedValue(p) > tradedValue(old)) {
                byDate.put(p.tradingDate(), p);
            }
        }

        return new ArrayList<>(byDate.values());
    }

    private StockRecommendation evaluate(String symbol, List<DailyPrice> p, boolean liveBar) {

        int n = p.size();

        BigDecimal close = p.get(n - 1).close();

        double r5 = pct(close, p.get(n - 6).close());
        double r20 = pct(close, p.get(n - 21).close());
        double r60 = pct(close, p.get(n - 61).close());

        BigDecimal sma20 = averageClose(p, n - 20, n);
        BigDecimal sma50 = averageClose(p, n - 50, n);

        int completedEnd = liveBar ? n - 1 : n;
        double avgRecentVol = averageVolume(p, completedEnd - 5, completedEnd);
        double avgBaseVol = averageVolume(p, completedEnd - 25, completedEnd - 5);

        double volumeStrength = avgBaseVol > 0
                ? avgRecentVol / avgBaseVol
                : 0;

        double volatility = dailyVolatility(
                p, completedEnd - 21, completedEnd);

        double peak60 = p.subList(n - 60, n)
                .stream()
                .mapToDouble(x -> x.high().doubleValue())
                .max()
                .orElse(close.doubleValue());

        double drawdown = peak60 > 0
                ? (close.doubleValue() / peak60 - 1) * 100
                : 0;

        double rsi = rsi14(p);
        double atr = atr14Percent(p);

        double averageValue = averageTradedValue(p, completedEnd - 20, completedEnd);

        double closeVsSma20 = pct(close, sma20);
        double sma20VsSma50 = pct(sma20, sma50);

        String direction = classifyMomentum(r5, r20, closeVsSma20, sma20VsSma50);
        boolean negative = "NEGATIVE".equals(direction);
        boolean changing = "CHANGING".equals(direction);

        int momentumScore = (int) Math.round(negative
                ? clamp(5 - r5 * 0.5, 0, 10)
                    + clamp(7.5 - r20 * 0.4, 0, 15)
                    + clamp(5 - r60 * 0.25, 0, 10)
                : changing
                    ? clamp(5 + Math.abs(r5) * 0.25, 0, 10)
                        + clamp(7.5 + Math.abs(r20) * 0.15, 0, 15)
                        + clamp(5 + Math.abs(r60) * 0.05, 0, 10)
                    : clamp(5 + r5 * 0.5, 0, 10)
                        + clamp(7.5 + r20 * 0.4, 0, 15)
                        + clamp(5 + r60 * 0.25, 0, 10));

        int trendScore = (int) Math.round(negative
                ? clamp(7.5 - closeVsSma20 * 1.5, 0, 15)
                    + clamp(5 - sma20VsSma50 * 1.5, 0, 10)
                : changing
                    ? clamp(7.5 + Math.abs(closeVsSma20) * 0.5, 0, 15)
                        + clamp(5 + Math.abs(sma20VsSma50) * 0.5, 0, 10)
                    : clamp(7.5 + closeVsSma20 * 1.5, 0, 15)
                        + clamp(5 + sma20VsSma50 * 1.5, 0, 10));

        int volumeScore = (int) Math.round(
                clamp(volumeStrength * 5, 0, 10)
        );

        double riskPoints = clamp(12 - volatility * 2, 0, 12)
                + clamp(8 + drawdown * 0.4, 0, 8);

        if (rsi > 75) {
            riskPoints -= Math.min(6, (rsi - 75) * 0.4);
        }

        if (rsi < 40) {
            riskPoints -= Math.min(6, (40 - rsi) * 0.4);
        }

        int riskScore = (int) Math.round(
                clamp(riskPoints, 0, 20)
        );

        int liquidityScore = (int) Math.round(
                clamp(
                        10 * Math.log10(1 + averageValue / MIN_AVERAGE_DAILY_VALUE)
                                / Math.log10(11),
                        0,
                        10
                )
        );

        int score = (int) Math.round(
                clamp(
                        momentumScore
                                + trendScore
                                + volumeScore
                                + riskScore
                                + liquidityScore,
                        0,
                        100
                )
        );

        String trend = "NEGATIVE".equals(direction)
                ? (sma20.compareTo(sma50) < 0 ? "Downtrend" : "Weakening")
                : "CHANGING".equals(direction)
                    ? "Transitioning"
                    : close.compareTo(sma20) > 0 && sma20.compareTo(sma50) > 0
                        ? "Uptrend" : close.compareTo(sma20) > 0 ? "Improving" : "Mixed";

        String setup;
        if ("NEGATIVE".equals(direction)) {
            setup = r5 < 0 && r20 < -5 && volumeStrength >= 1.2
                    ? "Momentum breakdown"
                    : closeVsSma20 < 0 && sma20VsSma50 < 0
                        ? "Downtrend continuation" : "Weakening price structure";
        } else if ("CHANGING".equals(direction)) {
            setup = r5 > 0 && r20 < 0 ? "Early recovery"
                    : r5 < 0 && r20 > 0 ? "Momentum weakening"
                    : "Mixed / range-bound momentum";
        } else {
            setup = r5 > 0 && r20 > 5 && volumeStrength >= 1.2
                    ? "Momentum breakout"
                    : closeVsSma20 > 0 && sma20VsSma50 > 0
                        ? "Trend continuation" : "Early recovery";
        }

        String reason = String.format(
                Locale.US,
                "%s momentum. 5-day return %+.1f%%, 20-day return %+.1f%% and 60-day return %+.1f%%; "
                        + "price is %+.1f%% versus SMA20. Volume is %.2fx its recent baseline and RSI14 is %.1f. "
                        + "Signal: %s.",
                direction.toLowerCase(Locale.ROOT),
                r5, r20, r60, closeVsSma20, volumeStrength, rsi, setup
        );

        String risk = String.format(
                Locale.US,
                "20-day daily volatility %.1f%%; ATR14 %.1f%% of price; "
                        + "60-session drawdown %+.1f%%. "
                        + "Average daily traded value is ₹%.2f crore. "
                        + "RSI above 75 can indicate an extended move. "
                        + "Price-only signals can reverse quickly.",
                volatility,
                atr,
                drawdown,
                averageValue / 10_000_000.0
        );

        return new StockRecommendation(
                symbol,
                close,
                score,
                round(r5),
                round(r20),
                round(r60),
                sma20,
                sma50,
                round(volumeStrength),
                round(volatility),
                round(drawdown),
                trend,
                reason,
                risk,
                round(rsi),
                round(atr),
                moneyValue(averageValue),
                momentumScore,
                trendScore,
                volumeScore,
                riskScore,
                liquidityScore,
                setup,
                direction
        );
    }

    private static String classifyMomentum(
            double return5d, double return20d, double closeVsSma20, double sma20VsSma50) {
        if (return20d >= 2.0 && closeVsSma20 > 0) return "POSITIVE";
        if (return20d <= -2.0 && closeVsSma20 < 0) return "NEGATIVE";
        return "CHANGING";
    }

    private static boolean passesCategoryFilters(
            StockRecommendation candidate, boolean passedLiquidity) {
        if (!passedLiquidity || candidate.volatility20d() > 8.0
                || candidate.researchScore() < 45) return false;

        return switch (candidate.momentumDirection()) {
            case "POSITIVE" -> candidate.return20d() >= 1.0
                    && candidate.latestClose().compareTo(candidate.sma20()) > 0
                    && candidate.drawdown60d() >= -25.0
                    && candidate.rsi14() <= 85.0
                    && candidate.researchScore() >= 62;
            case "NEGATIVE" -> candidate.return20d() <= -1.0
                    && candidate.latestClose().compareTo(candidate.sma20()) < 0
                    && candidate.researchScore() >= 50;
            case "CHANGING" -> candidate.researchScore() >= 50;
            default -> false;
        };
    }

    private static BigDecimal averageClose(
            List<DailyPrice> p,
            int from,
            int to) {

        return p.subList(from, to)
                .stream()
                .map(DailyPrice::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(
                        BigDecimal.valueOf(to - from),
                        4,
                        RoundingMode.HALF_UP
                );
    }

    private static double averageVolume(
            List<DailyPrice> p,
            int from,
            int to) {

        return p.subList(from, to)
                .stream()
                .mapToLong(DailyPrice::volume)
                .average()
                .orElse(0);
    }

    private static double averageTradedValue(
            List<DailyPrice> p,
            int from,
            int to) {

        return p.subList(from, to)
                .stream()
                .mapToDouble(MarketAnalysisService::tradedValue)
                .average()
                .orElse(0);
    }

    private static double tradedValue(DailyPrice p) {

        if (p.tradedValue() != null && p.tradedValue().signum() > 0) {
            return p.tradedValue().doubleValue();
        }

        return p.close().doubleValue() * Math.max(0, p.volume());
    }

    private static double dailyVolatility(
            List<DailyPrice> p,
            int from,
            int to) {

        List<Double> returns = new ArrayList<>();

        for (int i = from + 1; i < to; i++) {
            returns.add(
                    pct(p.get(i).close(), p.get(i - 1).close())
            );
        }

        if (returns.size() < 2) {
            return 99;
        }

        double mean = returns.stream()
                .mapToDouble(x -> x)
                .average()
                .orElse(0);

        double variance = returns.stream()
                .mapToDouble(x -> Math.pow(x - mean, 2))
                .sum() / (returns.size() - 1);

        return Math.sqrt(variance);
    }

    private static double rsi14(List<DailyPrice> p) {

        double gains = 0;
        double losses = 0;

        int n = p.size();

        for (int i = n - 14; i < n; i++) {

            double change = p.get(i).close().doubleValue()
                    - p.get(i - 1).close().doubleValue();

            if (change > 0) {
                gains += change;
            } else {
                losses -= change;
            }
        }

        if (losses == 0) {
            return gains == 0 ? 50 : 100;
        }

        double rs = (gains / 14.0) / (losses / 14.0);

        return 100 - (100 / (1 + rs));
    }

    private static double atr14Percent(List<DailyPrice> p) {

        double total = 0;
        int n = p.size();

        for (int i = n - 14; i < n; i++) {

            DailyPrice current = p.get(i);

            double high = current.high().doubleValue();
            double low = current.low().doubleValue();
            double previousClose = p.get(i - 1).close().doubleValue();

            total += Math.max(
                    high - low,
                    Math.max(
                            Math.abs(high - previousClose),
                            Math.abs(low - previousClose)
                    )
            );
        }

        double average = total / 14.0;
        double latestClose = p.get(n - 1).close().doubleValue();

        return latestClose > 0
                ? average / latestClose * 100
                : 0;
    }

    private static double pct(BigDecimal now, BigDecimal then) {

        if (then == null || then.signum() == 0) {
            return 0;
        }

        return (now.doubleValue() / then.doubleValue() - 1) * 100;
    }

    private static double clamp(
            double value,
            double min,
            double max) {

        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal moneyValue(double value) {

        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static double round(double value) {

        return Math.round(value * 100.0) / 100.0;
    }
}
        String reason = String.format(
                Locale.US,
                "%s momentum. 5-day return %+.1f%%, 20-day return %+.1f%% and 60-day return %+.1f%%; "
                        + "price is %+.1f%% versus SMA20. Volume is %.2fx its recent baseline and RSI14 is %.1f. "
                        + "Signal: %s.",
                direction.toLowerCase(Locale.ROOT),
                r5, r20, r60, closeVsSma20, volumeStrength, rsi, setup
        );

        String risk = String.format(
                Locale.US,
                "20-day daily volatility %.1f%%; ATR14 %.1f%% of price; "
                        + "60-session drawdown %+.1f%%. "
                        + "Average daily traded value is ₹%.2f crore. "
                        + "RSI above 75 can indicate an extended move. "
                        + "Price-only signals can reverse quickly.",
                volatility,
                atr,
                drawdown,
                averageValue / 10_000_000.0
        );

        return new StockRecommendation(
                symbol,
                close,
                score,
                round(r5),
                round(r20),
                round(r60),
                sma20,
                sma50,
                round(volumeStrength),
                round(volatility),
                round(drawdown),
                trend,
                reason,
                risk,
                round(rsi),
                round(atr),
                moneyValue(averageValue),
                momentumScore,
                trendScore,
                volumeScore,
                riskScore,
                liquidityScore,
                setup
        );
    }

    private static BigDecimal averageClose(
            List<DailyPrice> p,
            int from,
            int to) {

        return p.subList(from, to)
                .stream()
                .map(DailyPrice::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(
                        BigDecimal.valueOf(to - from),
                        4,
                        RoundingMode.HALF_UP
                );
    }

    private static double averageVolume(
            List<DailyPrice> p,
            int from,
            int to) {

        return p.subList(from, to)
                .stream()
                .mapToLong(DailyPrice::volume)
                .average()
                .orElse(0);
    }

    private static double averageTradedValue(
            List<DailyPrice> p,
            int from,
            int to) {

        return p.subList(from, to)
                .stream()
                .mapToDouble(MarketAnalysisService::tradedValue)
                .average()
                .orElse(0);
    }

    private static double tradedValue(DailyPrice p) {

        if (p.tradedValue() != null && p.tradedValue().signum() > 0) {
            return p.tradedValue().doubleValue();
        }

        return p.close().doubleValue() * Math.max(0, p.volume());
    }

    private static double dailyVolatility(
            List<DailyPrice> p,
            int from,
            int to) {

        List<Double> returns = new ArrayList<>();

        for (int i = from + 1; i < to; i++) {
            returns.add(
                    pct(p.get(i).close(), p.get(i - 1).close())
            );
        }

        if (returns.size() < 2) {
            return 99;
        }

        double mean = returns.stream()
                .mapToDouble(x -> x)
                .average()
                .orElse(0);

        double variance = returns.stream()
                .mapToDouble(x -> Math.pow(x - mean, 2))
                .sum() / (returns.size() - 1);

        return Math.sqrt(variance);
    }

    private static double rsi14(List<DailyPrice> p) {

        double gains = 0;
        double losses = 0;

        int n = p.size();

        for (int i = n - 14; i < n; i++) {

            double change = p.get(i).close().doubleValue()
                    - p.get(i - 1).close().doubleValue();

            if (change > 0) {
                gains += change;
            } else {
                losses -= change;
            }
        }

        if (losses == 0) {
            return gains == 0 ? 50 : 100;
        }

        double rs = (gains / 14.0) / (losses / 14.0);

        return 100 - (100 / (1 + rs));
    }

    private static double atr14Percent(List<DailyPrice> p) {

        double total = 0;
        int n = p.size();

        for (int i = n - 14; i < n; i++) {

            DailyPrice current = p.get(i);

            double high = current.high().doubleValue();
            double low = current.low().doubleValue();
            double previousClose = p.get(i - 1).close().doubleValue();

            total += Math.max(
                    high - low,
                    Math.max(
                            Math.abs(high - previousClose),
                            Math.abs(low - previousClose)
                    )
            );
        }

        double average = total / 14.0;
        double latestClose = p.get(n - 1).close().doubleValue();

        return latestClose > 0
                ? average / latestClose * 100
                : 0;
    }

    private static double pct(BigDecimal now, BigDecimal then) {

        if (then == null || then.signum() == 0) {
            return 0;
        }

        return (now.doubleValue() / then.doubleValue() - 1) * 100;
    }

    private static double clamp(
            double value,
            double min,
            double max) {

        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal moneyValue(double value) {

        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static double round(double value) {

        return Math.round(value * 100.0) / 100.0;
    }
}
