package com.minevest.history;

import com.minevest.marketdata.DailyPrice;
import com.minevest.marketdata.MarketDataService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class MarketAnalysisService {
    private static final int MIN_SESSIONS = 61;
    private static final double MIN_AVERAGE_DAILY_VALUE = 10_000_000.0; // ₹1 crore
    private static final double MAX_DAILY_VOLATILITY = 5.5;

    private final MarketDataService marketData;

    public MarketAnalysisService(MarketDataService marketData) {
        this.marketData = marketData;
    }

    public AnalysisSnapshot run() throws Exception {
        List<DailyPrice> all = marketData.allPrices();
        LocalDate latestMarketDate = all.stream()
                .map(DailyPrice::tradingDate)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // Group by symbol + ISIN so a symbol reuse or security change cannot mix histories.
        Map<String, List<DailyPrice>> bySecurity = new HashMap<>();
        for (DailyPrice price : all) {
            if (!isOrdinaryEquity(price)) continue;
            String key = price.symbol().toUpperCase(Locale.ROOT) + "|" + price.isin();
            bySecurity.computeIfAbsent(key, ignored -> new ArrayList<>()).add(price);
        }

        List<StockRecommendation> candidates = new ArrayList<>();
        int enoughHistory = 0;
        int passedLiquidity = 0;
        for (List<DailyPrice> rawPrices : bySecurity.values()) {
            List<DailyPrice> prices = uniqueSorted(rawPrices);
            if (prices.size() < MIN_SESSIONS) continue;
            enoughHistory++;

            DailyPrice latest = prices.get(prices.size() - 1);
            // Ignore securities whose last available price is stale relative to the dataset.
            if (latestMarketDate != null && latest.tradingDate().isBefore(latestMarketDate.minusDays(4))) continue;

            StockRecommendation candidate = evaluate(latest.symbol(), prices);
            if (candidate == null || candidate.averageDailyTradedValue20().doubleValue() < MIN_AVERAGE_DAILY_VALUE) continue;
            passedLiquidity++;

            // Swing-trading shortlist: positive intermediate momentum, improving trend and controlled risk.
            if (candidate.return20d() < 1.0
                    || candidate.latestClose().compareTo(candidate.sma20()) <= 0
                    || candidate.volatility20d() > MAX_DAILY_VOLATILITY
                    || candidate.drawdown60d() < -25.0
                    || candidate.rsi14() > 85.0
                    || candidate.researchScore() < 62) {
                continue;
            }
            candidates.add(candidate);
        }

        candidates.sort(Comparator.comparingInt(StockRecommendation::researchScore).reversed()
                .thenComparing(Comparator.comparingDouble(StockRecommendation::return20d).reversed())
                .thenComparing(StockRecommendation::symbol));
        List<StockRecommendation> top = candidates.stream().limit(5).toList();

        String marketDateText = latestMarketDate == null ? "not available" : latestMarketDate.toString();
        String note;
        if (top.isEmpty()) {
            note = String.format(Locale.US,
                    "Screening completed using local NSE data through %s. %d securities had at least %d sessions; %d also met the ₹1 crore average daily traded-value check. None passed all swing-trend, momentum and risk filters. No stock is forced into the shortlist. Raw bhavcopy prices are not adjusted for splits, bonuses or dividends.",
                    marketDateText, enoughHistory, MIN_SESSIONS, passedLiquidity);
        } else {
            note = String.format(Locale.US,
                    "Screening completed using locally stored NSE data through %s. %d securities had sufficient history and %d passed the liquidity check. Scores combine momentum (35), trend (25), volume (10), risk (20) and liquidity (10). Raw bhavcopy prices are not adjusted for splits, bonuses or dividends. Company fundamentals, news and sector/market-relative strength are not included yet.",
                    marketDateText, enoughHistory, passedLiquidity);
        }
        return AnalysisSnapshot.create(all.size(), bySecurity.size(), top, note);
    }

    private boolean isOrdinaryEquity(DailyPrice p) {
        return p != null
                && p.tradingDate() != null
                && p.symbol() != null && !p.symbol().isBlank()
                && "STK".equalsIgnoreCase(p.instrumentType())
                && "EQ".equalsIgnoreCase(p.series())
                && p.isin() != null && p.isin().startsWith("INE")
                && p.close() != null && p.close().signum() > 0
                && p.high() != null && p.low() != null;
    }

    private List<DailyPrice> uniqueSorted(List<DailyPrice> input) {
        Map<LocalDate, DailyPrice> byDate = new TreeMap<>();
        for (DailyPrice p : input) {
            DailyPrice old = byDate.get(p.tradingDate());
            if (old == null || tradedValue(p) > tradedValue(old)) byDate.put(p.tradingDate(), p);
        }
        return new ArrayList<>(byDate.values());
    }

    private StockRecommendation evaluate(String symbol, List<DailyPrice> p) {
        int n = p.size();
        BigDecimal close = p.get(n - 1).close();
        double r5 = pct(close, p.get(n - 6).close());
        double r20 = pct(close, p.get(n - 21).close());
        double r60 = pct(close, p.get(n - 61).close());
        BigDecimal sma20 = averageClose(p, n - 20, n);
        BigDecimal sma50 = averageClose(p, n - 50, n);
        double avgRecentVol = averageVolume(p, n - 5, n);
        double avgBaseVol = averageVolume(p, n - 25, n - 5);
        double volumeStrength = avgBaseVol > 0 ? avgRecentVol / avgBaseVol : 0;
        double volatility = dailyVolatility(p, n - 21, n);
        double peak60 = p.subList(n - 60, n).stream().mapToDouble(x -> x.high().doubleValue()).max().orElse(close.doubleValue());
        double drawdown = peak60 > 0 ? (close.doubleValue() / peak60 - 1) * 100 : 0;
        double rsi = rsi14(p);
        double atr = atr14Percent(p);
        double averageValue = averageTradedValue(p, n - 20, n);

        double closeVsSma20 = pct(close, sma20);
        double sma20VsSma50 = pct(sma20, sma50);

        int momentumScore = (int) Math.round(
                clamp(5 + r5 * 0.5, 0, 10)
                + clamp(7.5 + r20 * 0.4, 0, 15)
                + clamp(5 + r60 * 0.25, 0, 10));
        int trendScore = (int) Math.round(
                clamp(7.5 + closeVsSma20 * 1.5, 0, 15)
                + clamp(5 + sma20VsSma50 * 1.5, 0, 10));
        int volumeScore = (int) Math.round(clamp(volumeStrength * 5, 0, 10));
        double riskPoints = clamp(12 - volatility * 2, 0, 12)
                + clamp(8 + drawdown * 0.4, 0, 8);
        if (rsi > 75) riskPoints -= Math.min(6, (rsi - 75) * 0.4);
        if (rsi < 40) riskPoints -= Math.min(6, (40 - rsi) * 0.4);
        int riskScore = (int) Math.round(clamp(riskPoints, 0, 20));
        int liquidityScore = (int) Math.round(clamp(
                10 * Math.log10(1 + averageValue / MIN_AVERAGE_DAILY_VALUE) / Math.log10(11), 0, 10));

        int score = (int) Math.round(clamp(
                momentumScore + trendScore + volumeScore + riskScore + liquidityScore, 0, 100));

        String trend = close.compareTo(sma20) > 0 && sma20.compareTo(sma50) > 0 ? "Uptrend"
                : close.compareTo(sma20) > 0 ? "Improving" : "Weak / below trend";
        String setup = r5 > 0 && r20 > 5 && volumeStrength >= 1.2 ? "Momentum breakout"
                : closeVsSma20 > 0 && sma20VsSma50 > 0 ? "Trend continuation" : "Early recovery";
        String reason = String.format(Locale.US,
                "20-day return %+.1f%% and 60-day return %+.1f%%; price is %+.1f%% versus SMA20. Volume is %.2fx its recent baseline and RSI14 is %.1f. Setup: %s.",
                r20, r60, closeVsSma20, volumeStrength, rsi, setup);
        String risk = String.format(Locale.US,
                "20-day daily volatility %.1f%%; ATR14 %.1f%% of price; 60-session drawdown %+.1f%%. Average daily traded value is ₹%.2f crore. RSI above 75 can indicate an extended move. Price-only signals can reverse quickly.",
                volatility, atr, drawdown, averageValue / 10_000_000.0);

        return new StockRecommendation(symbol, close, score, round(r5), round(r20), round(r60),
                sma20, sma50, round(volumeStrength), round(volatility), round(drawdown), trend,
                reason, risk, round(rsi), round(atr), moneyValue(averageValue), momentumScore,
                trendScore, volumeScore, riskScore, liquidityScore, setup);
    }

    private static BigDecimal averageClose(List<DailyPrice> p, int from, int to) {
        return p.subList(from, to).stream().map(DailyPrice::close).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(to - from), 4, RoundingMode.HALF_UP);
    }

    private static double averageVolume(List<DailyPrice> p, int from, int to) {
        return p.subList(from, to).stream().mapToLong(DailyPrice::volume).average().orElse(0);
    }

    private static double averageTradedValue(List<DailyPrice> p, int from, int to) {
        return p.subList(from, to).stream().mapToDouble(MarketAnalysisService::tradedValue).average().orElse(0);
    }

    private static double tradedValue(DailyPrice p) {
        if (p.tradedValue() != null && p.tradedValue().signum() > 0) return p.tradedValue().doubleValue();
        return p.close().doubleValue() * Math.max(0, p.volume());
    }

    private static double dailyVolatility(List<DailyPrice> p, int from, int to) {
        List<Double> returns = new ArrayList<>();
        for (int i = from + 1; i < to; i++) returns.add(pct(p.get(i).close(), p.get(i - 1).close()));
        if (returns.size() < 2) return 99;
        double mean = returns.stream().mapToDouble(x -> x).average().orElse(0);
        double variance = returns.stream().mapToDouble(x -> Math.pow(x - mean, 2)).sum() / (returns.size() - 1);
        return Math.sqrt(variance);
    }

    private static double rsi14(List<DailyPrice> p) {
        double gains = 0;
        double losses = 0;
        int n = p.size();
        for (int i = n - 14; i < n; i++) {
            double change = p.get(i).close().doubleValue() - p.get(i - 1).close().doubleValue();
            if (change > 0) gains += change;
            else losses -= change;
        }
        if (losses == 0) return gains == 0 ? 50 : 100;
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
            total += Math.max(high - low, Math.max(Math.abs(high - previousClose), Math.abs(low - previousClose)));
        }
        double average = total / 14.0;
        double latestClose = p.get(n - 1).close().doubleValue();
        return latestClose > 0 ? average / latestClose * 100 : 0;
    }

    private static double pct(BigDecimal now, BigDecimal then) {
        if (then == null || then.signum() == 0) return 0;
        return (now.doubleValue() / then.doubleValue() - 1) * 100;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal moneyValue(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
