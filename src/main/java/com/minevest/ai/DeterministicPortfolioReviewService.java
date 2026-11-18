package com.minevest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minevest.fundamentals.CompanyFundamental;
import com.minevest.fundamentals.CompanyIntelligenceService;
import com.minevest.fundamentals.StockNews;
import com.minevest.marketdata.DailyPrice;
import com.minevest.marketdata.MarketDataService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "minevest.ai.provider", havingValue = "deterministic", matchIfMissing = true)
public class DeterministicPortfolioReviewService implements PortfolioReviewProvider {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private final ObjectMapper objectMapper;
    private final MarketDataService marketDataService;
    private final CompanyIntelligenceService intelligenceService;

    public DeterministicPortfolioReviewService(ObjectMapper objectMapper,
                                               MarketDataService marketDataService,
                                               CompanyIntelligenceService intelligenceService) {
        this.objectMapper = objectMapper;
        this.marketDataService = marketDataService;
        this.intelligenceService = intelligenceService;
    }

    @Override
    public JsonNode reviewPortfolio(JsonNode portfolio) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = objectMapper.createArrayNode();

        JsonNode holdings = portfolio != null && portfolio.has("holdings")
                ? portfolio.get("holdings") : objectMapper.createArrayNode();

        int count = 0;
        int reviewCount = 0;
        int exitCount = 0;

        if (holdings.isArray()) {
            for (JsonNode holding : holdings) {
                String symbol = text(holding, "symbol", "").trim().toUpperCase(Locale.ROOT);
                if (symbol.isBlank()) continue;

                count++;
                double averagePrice = number(holding, "averagePrice", "buyPrice", "avgPrice");
                double suppliedCurrentPrice = number(holding, "currentPrice", "ltp", "close");

                ObjectNode result = evaluateHolding(symbol, averagePrice, suppliedCurrentPrice);
                results.add(result);

                String action = result.path("action").asText("REVIEW");
                if ("EXIT".equals(action)) exitCount++;
                if ("REVIEW".equals(action)) reviewCount++;
            }
        }

        response.put("summary", String.format(Locale.US,
                "MineVest assessed %d holding(s): %d marked REVIEW and %d marked EXIT. Unrealised profit or loss is not an exit criterion.",
                count, reviewCount, exitCount));
        response.set("holdings", results);
        response.put("engine", "MINEVEST_DETERMINISTIC");
        response.put("disclaimer", "Research support only. Recommendations depend on the quality and freshness of available data and are not personalised financial advice.");
        return response;
    }

    private ObjectNode evaluateHolding(String symbol, double averagePrice, double suppliedCurrentPrice) {
        ObjectNode out = objectMapper.createObjectNode();

        List<DailyPrice> prices = new ArrayList<>(marketDataService.prices(symbol));
        prices.sort(Comparator.comparing(DailyPrice::tradingDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Optional<CompanyFundamental> fundamental = intelligenceService.getFundamental(symbol);
        List<StockNews> news = intelligenceService.recentNews(symbol, 14);

        boolean etf = isEtf(symbol, prices);
        String assetType = etf ? "ETF" : "EQUITY";

        List<String> drivers = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        List<String> monitoring = new ArrayList<>();

        double lastEodClose = prices.isEmpty() ? 0.0 : value(prices.get(prices.size() - 1).close());
        // The supplied portfolio price takes precedence. Do not silently replace it with an older EOD close.
        double assessedPrice = suppliedCurrentPrice > 0 ? suppliedCurrentPrice : lastEodClose;
        String priceSource = suppliedCurrentPrice > 0
                ? "PORTFOLIO_SUPPLIED_PRICE"
                : prices.isEmpty() ? "UNAVAILABLE" : "LAST_STORED_EOD_CLOSE";

        LocalDate latestTradingDate = prices.isEmpty() ? null : prices.get(prices.size() - 1).tradingDate();
        long priceAgeDays = latestTradingDate == null ? Long.MAX_VALUE
                : Math.max(0, ChronoUnit.DAYS.between(latestTradingDate, LocalDate.now(MARKET_ZONE)));
        String priceFreshness = latestTradingDate == null ? "UNAVAILABLE"
                : priceAgeDays <= 5 ? "FRESH" : priceAgeDays <= 10 ? "AGING" : "STALE";

        // Technical analysis: completed historical daily candles are used for SMA and history.
        Integer technicalScore = null;
        double return20d = 0.0;
        double drawdown60d = 0.0;
        boolean has20d = prices.size() >= 21 && assessedPrice > 0;
        boolean has60d = prices.size() >= 60 && assessedPrice > 0;
        boolean downtrend = false;
        boolean uptrend = false;
        String trend = "Insufficient price history";

        if (has20d) {
            int n = prices.size();
            double close20Ago = value(prices.get(n - 21).close());
            return20d = close20Ago > 0 ? (assessedPrice / close20Ago - 1.0) * 100.0 : 0.0;
            double sma20 = prices.subList(n - 20, n).stream()
                    .mapToDouble(p -> value(p.close())).filter(v -> v > 0).average().orElse(0.0);

            technicalScore = 20;
            if (sma20 > 0 && assessedPrice > sma20 && return20d > 2.0) {
                technicalScore += 12;
                uptrend = true;
                trend = String.format(Locale.US, "Uptrend: %+.1f%% over 20 sessions; assessed price is above SMA20 ₹%.2f", return20d, sma20);
                drivers.add(String.format(Locale.US, "Positive 20-session price momentum (%+.1f%%)", return20d));
            } else if (sma20 > 0 && assessedPrice < sma20 && return20d < -3.0) {
                technicalScore -= 12;
                downtrend = true;
                trend = String.format(Locale.US, "Downtrend: %.1f%% over 20 sessions; assessed price is below SMA20 ₹%.2f", return20d, sma20);
                risks.add(String.format(Locale.US, "Weak 20-session price trend (%+.1f%%)", return20d));
            } else {
                trend = String.format(Locale.US, "Consolidating: %+.1f%% over 20 sessions; no strong trend confirmation", return20d);
            }

            if (has60d) {
                double peak = prices.subList(n - 60, n).stream()
                        .mapToDouble(p -> value(p.high())).filter(v -> v > 0).max().orElse(assessedPrice);
                drawdown60d = peak > 0 ? (assessedPrice / peak - 1.0) * 100.0 : 0.0;
                if (drawdown60d <= -20.0) {
                    technicalScore -= 8;
                    risks.add(String.format(Locale.US, "Assessed price is %.1f%% below the 60-session high", Math.abs(drawdown60d)));
                }
            }
            technicalScore = Math.max(0, Math.min(40, technicalScore));
        } else if (prices.isEmpty()) {
            gaps.add("No stored daily price history is available.");
        } else {
            gaps.add("At least 21 trading sessions are required for a 20-session trend assessment.");
        }

        // Financial metrics are scored only when actually present. Missing values earn no points.
        Integer fundamentalScore = null;
        int fundamentalMax = 0;
        int fundamentalMetrics = 0;
        String fundamentalStatus;
        String businessOverview;
        String businessQuality;
        String valuation;

        if (etf) {
            fundamentalStatus = "NOT_APPLICABLE_ETF";
            businessOverview = symbol + " is assessed as an exchange-traded fund, not an operating company.";
            businessQuality = "Company ROE, debt-to-equity and revenue-growth metrics are not applicable to an ETF.";
            valuation = "ETF-specific NAV premium/discount, expense ratio, tracking difference, underlying asset and liquidity data are not connected yet.";
            gaps.add("ETF-specific data is missing: underlying benchmark/asset, NAV premium or discount, expense ratio, tracking difference and liquidity.");
            monitoring.add("Monitor the underlying asset trend, ETF tracking difference, expense ratio and liquidity.");
        } else if (fundamental.isPresent()) {
            CompanyFundamental f = fundamental.get();
            businessOverview = safe(f.companyName(), symbol) + " operates in the " + safe(f.sector(), "unclassified") + " sector.";
            List<String> quality = new ArrayList<>();
            int score = 0;

            if (f.roe() != null) {
                fundamentalMetrics++;
                score += scoreRoe(f.roe().doubleValue());
                if (f.roe().doubleValue() >= 15) quality.add("ROE " + f.roe() + "% is relatively strong");
                if (f.roe().doubleValue() < 8) risks.add("Low ROE: " + f.roe() + "%");
            }
            if (f.debtToEquity() != null) {
                fundamentalMetrics++;
                score += scoreDebt(f.debtToEquity().doubleValue());
                if (f.debtToEquity().doubleValue() <= 0.5) quality.add("conservative debt-to-equity of " + f.debtToEquity());
                if (f.debtToEquity().doubleValue() > 1.5) risks.add("Elevated debt-to-equity: " + f.debtToEquity());
            }
            if (f.revenueGrowthYoy() != null) {
                fundamentalMetrics++;
                score += scoreGrowth(f.revenueGrowthYoy().doubleValue());
                if (f.revenueGrowthYoy().doubleValue() > 10) drivers.add("Revenue growth: " + f.revenueGrowthYoy() + "% YoY");
                if (f.revenueGrowthYoy().doubleValue() < 0) risks.add("Revenue declined " + f.revenueGrowthYoy().abs() + "% YoY");
            }
            if (f.profitGrowthYoy() != null) {
                fundamentalMetrics++;
                score += scoreGrowth(f.profitGrowthYoy().doubleValue());
                if (f.profitGrowthYoy().doubleValue() > 10) drivers.add("Profit growth: " + f.profitGrowthYoy() + "% YoY");
                if (f.profitGrowthYoy().doubleValue() < 0) risks.add("Profit declined " + f.profitGrowthYoy().abs() + "% YoY");
            }

            fundamentalMax = fundamentalMetrics * 10;
            if (fundamentalMetrics > 0) fundamentalScore = score;
            fundamentalStatus = fundamentalMetrics == 4 ? "POPULATED"
                    : fundamentalMetrics > 0 ? "PARTIAL" : "NOT_POPULATED";
            businessQuality = quality.isEmpty()
                    ? "Financial data is present but does not yet establish business quality."
                    : String.join("; ", quality) + ".";

            List<String> valuationParts = new ArrayList<>();
            if (f.peRatio() != null) valuationParts.add("P/E " + f.peRatio() + "x");
            if (f.pbRatio() != null) valuationParts.add("P/B " + f.pbRatio() + "x");
            valuation = valuationParts.isEmpty()
                    ? "Valuation ratios are not populated."
                    : String.join(", ", valuationParts) + ". Sector-peer comparison is required before classifying valuation as cheap or expensive.";
            if (fundamentalMetrics < 4) gaps.add("Fundamental coverage is partial; ROE, debt-to-equity, revenue growth and profit growth are not all available.");
            if (f.peRatio() == null || f.pbRatio() == null) gaps.add("One or more valuation ratios (P/E, P/B) are missing.");

            Instant updatedAt = f.updatedAt();
            if (updatedAt != null && ChronoUnit.DAYS.between(updatedAt.atZone(MARKET_ZONE).toLocalDate(), LocalDate.now(MARKET_ZONE)) > 180) {
                gaps.add("Stored fundamental snapshot is older than 180 days; refresh it before relying on the assessment.");
            }
        } else {
            fundamentalStatus = "NOT_POPULATED";
            businessOverview = symbol + " is an NSE-listed equity; verified company profile data is not stored yet.";
            businessQuality = "Business quality cannot be assessed reliably until verified financial information is available.";
            valuation = "Valuation cannot be assessed until financial ratios and sector-peer information are available.";
            gaps.add("Company financial information (ROE, debt-to-equity, revenue growth and profit growth) is not populated.");
        }

        // News score is absent when there are no stored articles; it is never assigned a neutral default.
        Integer newsScore = null;
        long negativeNews = news.stream().filter(n -> "NEGATIVE".equalsIgnoreCase(n.sentimentTag())).count();
        long positiveNews = news.stream().filter(n -> "POSITIVE".equalsIgnoreCase(n.sentimentTag())).count();
        if (!news.isEmpty()) {
            int score = 10 + (int) Math.min(10, positiveNews * 3) - (int) Math.min(10, negativeNews * 4);
            newsScore = Math.max(0, Math.min(20, score));
            news.stream().filter(n -> "NEGATIVE".equalsIgnoreCase(n.sentimentTag())).limit(3)
                    .forEach(n -> risks.add("News flagged negative; verify source and filing: " + n.headline()));
            news.stream().filter(n -> "POSITIVE".equalsIgnoreCase(n.sentimentTag())).limit(3)
                    .forEach(n -> drivers.add("News flagged positive; verify source and filing: " + n.headline()));
        } else {
            gaps.add("No news items are stored for the last 14 days; news impact is unknown, not neutral.");
        }

        double pnlPct = averagePrice > 0 && assessedPrice > 0
                ? (assessedPrice / averagePrice - 1.0) * 100.0 : 0.0;
        String holdingAnalysis = averagePrice > 0 && assessedPrice > 0
                ? String.format(Locale.US,
                "Average buy price ₹%.2f vs assessed price ₹%.2f (%+.2f%% unrealised P&L). Price source: %s. Latest stored session: %s. P&L is context only and is not used to trigger EXIT.",
                averagePrice, assessedPrice, pnlPct, priceSource,
                latestTradingDate == null ? "unavailable" : latestTradingDate.toString())
                : "Holding P&L cannot be calculated because average buy price or assessed price is unavailable.";

        // Conservative decision rules. Holding loss is deliberately excluded.
        boolean severeFundamentalDeterioration = false;
        if (!etf && fundamental.isPresent()) {
            CompanyFundamental f = fundamental.get();
            severeFundamentalDeterioration = f.roe() != null && f.profitGrowthYoy() != null
                    && f.debtToEquity() != null
                    && f.roe().doubleValue() < 0
                    && f.profitGrowthYoy().doubleValue() <= -25
                    && f.debtToEquity().doubleValue() >= 2.0;
        }
        boolean severePriceBreakdown = has60d && drawdown60d <= -35.0 && return20d <= -15.0;

        String action;
        String decision;
        if (etf) {
            if (downtrend || negativeNews > 0 || "STALE".equals(priceFreshness)) {
                action = "REVIEW";
                decision = "Available signals require monitoring. A price loss or temporary correction alone is not an exit reason; verify the underlying asset outlook and ETF tracking quality.";
            } else if (has20d) {
                action = "HOLD";
                decision = "No verified structural reason to exit is present in the connected data. Monitor the underlying asset, tracking difference, expense ratio and liquidity.";
            } else {
                action = "REVIEW";
                decision = "Insufficient price history or ETF-specific data. Missing information is not a sell signal.";
            }
        } else if (severeFundamentalDeterioration && severePriceBreakdown && negativeNews >= 2) {
            action = "EXIT";
            decision = "Multiple independent severe warning signals are present: negative ROE, sharp profit contraction, high leverage, major price breakdown and repeated negative news. Verify the latest primary filings before acting.";
        } else if (downtrend || negativeNews > 0 || severeFundamentalDeterioration || "STALE".equals(priceFreshness)) {
            action = "REVIEW";
            decision = "One or more warning signals or data-quality limitations require review. The holding's unrealised loss is not an exit trigger.";
        } else if (has20d && (uptrend || (fundamentalScore != null && fundamentalMax > 0 && fundamentalScore >= fundamentalMax * 0.7))) {
            action = "HOLD";
            decision = "Available evidence does not show a deterioration severe enough to justify an exit. Continue monitoring the investment thesis and new disclosures.";
        } else {
            action = "REVIEW";
            decision = "Evidence is mixed or incomplete. Review again when verified market, financial and event information is available.";
        }

        String outlook = prices.isEmpty() ? "INSUFFICIENT_DATA"
                : "EXIT".equals(action) || downtrend ? "NEGATIVE"
                  : "HOLD".equals(action) && uptrend ? "POSITIVE" : "NEUTRAL";

        int availableScore = (technicalScore == null ? 0 : technicalScore)
                + (fundamentalScore == null ? 0 : fundamentalScore)
                + (newsScore == null ? 0 : newsScore);
        int availableMax = (technicalScore == null ? 0 : 40)
                + fundamentalMax + (newsScore == null ? 0 : 20);
        boolean complete = !etf && technicalScore != null && fundamentalMetrics == 4 && newsScore != null;
        Integer overallScore = complete ? availableScore : null;
        String scoreStatus = complete ? "COMPLETE" : availableScore > 0 ? "PARTIAL" : "NOT_AVAILABLE";

        int covered = (technicalScore != null ? 1 : 0)
                + (etf || fundamentalMetrics == 4 ? 1 : 0)
                + (newsScore != null ? 1 : 0);
        String confidence = covered == 3 && "FRESH".equals(priceFreshness) ? "High"
                : covered >= 2 ? "Medium" : "Low";

        if (drivers.isEmpty()) drivers.add("No verified positive catalyst was identified in the available data.");
        if (risks.isEmpty()) risks.add("No specific risk flag was identified in available inputs; this does not mean the investment is risk-free.");
        monitoring.add("Reassess after new market data, financial results or material announcements.");

        out.put("symbol", symbol);
        out.put("assetType", assetType);
        out.put("businessOverview", businessOverview);
        out.put("businessQuality", businessQuality);
        out.set("growthDrivers", array(drivers));
        out.put("valuationView", valuation);
        out.put("fundamentalDataStatus", fundamentalStatus);
        out.put("stockOutlook", outlook);
        out.put("action", action);
        out.put("decisionRationale", decision);
        out.put("confidence", confidence);
        out.put("reasoning", decision + " Technical posture: " + trend + ".");
        out.set("risks", array(risks));
        out.set("dataGaps", array(gaps));
        out.set("monitoringPoints", array(monitoring));
        out.put("holdingAnalysis", holdingAnalysis);

        out.put("technicalScore", technicalScore == null ? null : technicalScore);
        out.put("technicalScoreMax", technicalScore == null ? 0 : 40);
        out.put("fundamentalScore", fundamentalScore == null ? null : fundamentalScore);
        out.put("fundamentalScoreMax", fundamentalMax);
        out.put("fundamentalMetricsAvailable", fundamentalMetrics);
        out.put("newsScore", newsScore == null ? null : newsScore);
        out.put("newsScoreMax", newsScore == null ? 0 : 20);
        out.put("availableScore", availableScore);
        out.put("availableScoreMax", availableMax);
        out.put("overallScore", overallScore == null ? null : overallScore);
        out.put("scoreStatus", scoreStatus);
        out.put("priceFreshness", priceFreshness);
        out.put("latestTradingDate", latestTradingDate == null ? null : latestTradingDate.toString());
        out.put("assessedPrice", assessedPrice > 0 ? assessedPrice : null);
        out.put("priceSource", priceSource);
        out.put("return20dPct", has20d ? return20d : null);
        out.put("drawdown60dPct", has60d ? drawdown60d : null);
        out.put("unrealisedReturnPct", averagePrice > 0 && assessedPrice > 0 ? pnlPct : null);
        out.put("positiveNewsCount", positiveNews);
        out.put("negativeNewsCount", negativeNews);
        return out;
    }

    private boolean isEtf(String symbol, List<DailyPrice> prices) {
        if (symbol.endsWith("BEES") || symbol.contains("ETF")) return true;
        return prices.stream().anyMatch(p ->
                (p.instrumentType() != null && p.instrumentType().toUpperCase(Locale.ROOT).contains("ETF"))
                        || (p.series() != null && p.series().toUpperCase(Locale.ROOT).contains("ETF")));
    }

    private int scoreRoe(double value) {
        if (value >= 18) return 10;
        if (value >= 12) return 8;
        if (value >= 8) return 6;
        if (value >= 0) return 3;
        return 0;
    }

    private int scoreDebt(double value) {
        if (value <= 0.3) return 10;
        if (value <= 0.7) return 8;
        if (value <= 1.5) return 5;
        if (value <= 2.5) return 2;
        return 0;
    }

    private int scoreGrowth(double value) {
        if (value >= 20) return 10;
        if (value >= 10) return 8;
        if (value >= 0) return 5;
        if (value >= -10) return 2;
        return 0;
    }

    private ArrayNode array(List<String> values) {
        ArrayNode result = objectMapper.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static double value(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText(fallback) : fallback;
    }

    private static double number(JsonNode node, String... fields) {
        if (node == null) return 0.0;
        for (String field : fields) {
            if (node.hasNonNull(field)) return node.get(field).asDouble(0.0);
        }
        return 0.0;
    }
}
