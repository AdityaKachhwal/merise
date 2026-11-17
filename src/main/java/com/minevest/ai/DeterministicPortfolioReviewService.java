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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "minevest.ai.provider", havingValue = "deterministic", matchIfMissing = true)
public class DeterministicPortfolioReviewService implements PortfolioReviewProvider {

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
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode holdingsOut = objectMapper.createArrayNode();
        ArrayNode portfolioRisks = objectMapper.createArrayNode();
        ArrayNode alternatives = objectMapper.createArrayNode();

        JsonNode holdingsNode = portfolio != null && portfolio.has("holdings")
                ? portfolio.get("holdings")
                : objectMapper.createArrayNode();

        int assessedCount = 0;
        int exitOrReviewCount = 0;

        if (holdingsNode.isArray()) {
            for (JsonNode item : holdingsNode) {
                String symbol = textOrDefault(item, "symbol", "").trim().toUpperCase(Locale.ROOT);
                if (symbol.isBlank()) continue;
                assessedCount++;

                double avgBuyPrice = numberOrZero(item, "averagePrice", "buyPrice", "avgPrice");
                double suppliedCurrentPrice = numberOrZero(item, "currentPrice", "ltp", "close");

                ObjectNode assessment = evaluateSingleHolding(symbol, avgBuyPrice, suppliedCurrentPrice);
                holdingsOut.add(assessment);

                String action = assessment.path("action").asText("REVIEW");
                if (!"HOLD".equals(action)) {
                    exitOrReviewCount++;
                }
            }
        }

        root.put("summary", String.format(Locale.US,
                "Deterministic multi-factor assessment completed for %d holding(s) using PostgreSQL price history, fundamentals, and news catalysts. %d holding(s) flagged for REVIEW or EXIT.",
                assessedCount, exitOrReviewCount));
        root.set("holdings", holdingsOut);
        root.set("portfolioRisks", portfolioRisks);
        root.set("alternatives", alternatives);
        root.put("disclaimer", "Deterministic multi-factor analysis based on stored NSE market data, fundamentals, and news feeds. Verify corporate filings before investing.");
        return root;
    }

    private ObjectNode evaluateSingleHolding(String symbol, double avgBuyPrice, double suppliedCurrentPrice) {
        ObjectNode node = objectMapper.createObjectNode();
        List<DailyPrice> prices = marketDataService.prices(symbol);
        Optional<CompanyFundamental> fundamentalOpt = intelligenceService.getFundamental(symbol);
        List<StockNews> recentNews = intelligenceService.recentNews(symbol, 14);

        List<String> growthDrivers = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> dataGaps = new ArrayList<>();

        // 1. Technical Score (0 - 40)
        int technicalScore = 20;
        double latestClose = suppliedCurrentPrice;
        double return20d = 0.0;
        double drawdown60d = 0.0;
        String trendText = "Insufficient price history";

        if (!prices.isEmpty()) {
            DailyPrice last = prices.get(prices.size() - 1);
            latestClose = last.close().doubleValue();
            int n = prices.size();

            if (n >= 21) {
                double close20Ago = prices.get(n - 21).close().doubleValue();
                return20d = close20Ago > 0 ? ((latestClose / close20Ago) - 1.0) * 100.0 : 0.0;
                double sma20 = prices.subList(n - 20, n).stream()
                        .mapToDouble(p -> p.close().doubleValue()).average().orElse(latestClose);

                if (latestClose > sma20 && return20d > 2.0) {
                    technicalScore += 12;
                    trendText = String.format(Locale.US, "Uptrend (+%.1f%% over 20d, trading above SMA20 ₹%.2f)", return20d, sma20);
                    growthDrivers.add(String.format(Locale.US, "Positive 20-day price momentum (%+.1f%%) above 20-DMA", return20d));
                } else if (latestClose < sma20 && return20d < -3.0) {
                    technicalScore -= 12;
                    trendText = String.format(Locale.US, "Downtrend (%.1f%% over 20d, below SMA20 ₹%.2f)", return20d, sma20);
                    risks.add(String.format(Locale.US, "Negative intermediate trend (%.1f%% in 20 sessions) below SMA20", return20d));
                } else {
                    trendText = String.format(Locale.US, "Consolidating (%+.1f%% over 20d near SMA20 ₹%.2f)", return20d, sma20);
                }
            } else {
                dataGaps.add("Less than 21 trading sessions stored in PostgreSQL; run 5-year backfill for full trend indicators.");
            }

            if (n >= 60) {
                double peak60 = prices.subList(n - 60, n).stream()
                        .mapToDouble(p -> p.high().doubleValue()).max().orElse(latestClose);
                drawdown60d = peak60 > 0 ? ((latestClose / peak60) - 1.0) * 100.0 : 0.0;
                if (drawdown60d < -20.0) {
                    technicalScore -= 8;
                    risks.add(String.format(Locale.US, "Steep 60-session drawdown of %.1f%% from recent peak", drawdown60d));
                }
            }
        } else {
            dataGaps.add("No NSE daily price history found in PostgreSQL for " + symbol + ".");
        }
        technicalScore = Math.max(0, Math.min(40, technicalScore));

        // 2. Fundamental Score (0 - 40)
        int fundamentalScore = 20;
        String businessOverview;
        String businessQuality;
        String valuationView;
        String fundamentalStatus;

        if (fundamentalOpt.isPresent()) {
            CompanyFundamental f = fundamentalOpt.get();
            fundamentalStatus = "CONNECTED";
            businessOverview = String.format("%s operates in the %s sector%s.",
                    f.companyName() != null ? f.companyName() : symbol,
                    f.sector() != null ? f.sector() : "Indian equity",
                    f.marketCap() != null ? " (Market Cap: ₹" + f.marketCap().setScale(0, RoundingMode.HALF_UP) + " Cr)" : "");

            List<String> qualityNotes = new ArrayList<>();
            if (f.roe() != null) {
                if (f.roe().doubleValue() >= 15.0) {
                    fundamentalScore += 8;
                    qualityNotes.add("Strong ROE of " + f.roe() + "%");
                    growthDrivers.add("Healthy return on equity (ROE: " + f.roe() + "%)");
                } else if (f.roe().doubleValue() < 8.0) {
                    fundamentalScore -= 6;
                    risks.add("Low return on equity (ROE: " + f.roe() + "%)");
                }
            }
            if (f.debtToEquity() != null) {
                if (f.debtToEquity().doubleValue() <= 0.5) {
                    fundamentalScore += 6;
                    qualityNotes.add("Conservative leverage (D/E: " + f.debtToEquity() + ")");
                } else if (f.debtToEquity().doubleValue() > 1.5) {
                    fundamentalScore -= 8;
                    risks.add("Elevated debt-to-equity ratio (" + f.debtToEquity() + ")");
                }
            }
            if (f.revenueGrowthYoy() != null && f.revenueGrowthYoy().doubleValue() > 10.0) {
                fundamentalScore += 6;
                growthDrivers.add("YoY revenue growth of " + f.revenueGrowthYoy() + "%");
            }
            businessQuality = qualityNotes.isEmpty()
                    ? "Fundamental profile recorded; " + trendText
                    : String.join("; ", qualityNotes) + ". Technical posture: " + trendText;

            if (f.peRatio() != null) {
                double pe = f.peRatio().doubleValue();
                if (pe > 0 && pe <= 25.0) {
                    valuationView = "Reasonable valuation at P/E " + f.peRatio() + "x";
                } else if (pe > 50.0) {
                    fundamentalScore -= 4;
                    valuationView = "Richly valued at P/E " + f.peRatio() + "x";
                    risks.add("High valuation multiple (P/E " + f.peRatio() + "x) leaves limited margin of safety");
                } else {
                    valuationView = "P/E multiple at " + f.peRatio() + "x";
                }
            } else {
                valuationView = "P/E ratio not yet populated in company_fundamentals";
                dataGaps.add("P/E and P/B valuation ratios missing in PostgreSQL.");
            }
        } else {
            fundamentalStatus = "NOT_POPULATED";
            businessOverview = symbol + " (NSE listed equity). Technical posture: " + trendText + ".";
            businessQuality = "Evaluated on PostgreSQL price action (" + prices.size() + " sessions). Add row to company_fundamentals for ROE/debt scoring.";
            valuationView = "Fundamental ratios not populated yet (POST /api/fundamentals to enrich)";
            dataGaps.add("Quarterly fundamentals (ROE, Debt/Equity, P/E, Revenue Growth) not yet ingested for " + symbol + ".");
        }
        fundamentalScore = Math.max(0, Math.min(40, fundamentalScore));

        // 3. News & Catalyst Score (0 - 20)
        int newsScore = 10;
        long negativeNewsCount = recentNews.stream().filter(n -> "NEGATIVE".equalsIgnoreCase(n.sentimentTag())).count();
        long positiveNewsCount = recentNews.stream().filter(n -> "POSITIVE".equalsIgnoreCase(n.sentimentTag())).count();

        if (negativeNewsCount > 0) {
            newsScore -= (int) Math.min(10, negativeNewsCount * 5);
            recentNews.stream()
                    .filter(n -> "NEGATIVE".equalsIgnoreCase(n.sentimentTag()))
                    .limit(2)
                    .forEach(n -> risks.add("Recent negative news catalyst: " + n.headline()));
        }
        if (positiveNewsCount > 0) {
            newsScore += (int) Math.min(10, positiveNewsCount * 4);
            recentNews.stream()
                    .filter(n -> "POSITIVE".equalsIgnoreCase(n.sentimentTag()))
                    .limit(2)
                    .forEach(n -> growthDrivers.add("Recent positive catalyst: " + n.headline()));
        }
        if (recentNews.isEmpty()) {
            dataGaps.add("No recent news headlines stored in stock_news for the last 14 days.");
        }
        newsScore = Math.max(0, Math.min(20, newsScore));

        int totalScore = technicalScore + fundamentalScore + newsScore;

        // 4. Holding Position & Action Decision
        String holdingAnalysis;
        double pnlPct = 0.0;
        if (avgBuyPrice > 0 && latestClose > 0) {
            pnlPct = ((latestClose / avgBuyPrice) - 1.0) * 100.0;
            holdingAnalysis = String.format(Locale.US,
                    "Buy price ₹%.2f vs latest close ₹%.2f (%+.2f%% unrealized P&L). Multi-factor score: %d/100 (Tech %d/40, Fund %d/40, News %d/20).",
                    avgBuyPrice, latestClose, pnlPct, totalScore, technicalScore, fundamentalScore, newsScore);
        } else {
            holdingAnalysis = String.format(Locale.US,
                    "Latest close ₹%.2f. Multi-factor score: %d/100 (Tech %d/40, Fund %d/40, News %d/20).",
                    latestClose, totalScore, technicalScore, fundamentalScore, newsScore);
        }

        String outlook;
        String action;
        if (totalScore >= 65 && negativeNewsCount == 0) {
            outlook = "POSITIVE";
            action = "HOLD";
        } else if (totalScore < 42 || negativeNewsCount >= 2 || (avgBuyPrice > 0 && pnlPct < -15.0 && return20d < -5.0)) {
            outlook = "NEGATIVE";
            action = "EXIT";
        } else {
            outlook = prices.isEmpty() && fundamentalOpt.isEmpty() ? "INSUFFICIENT_DATA" : "NEUTRAL";
            action = "REVIEW";
        }

        String confidence = (!prices.isEmpty() && fundamentalOpt.isPresent()) ? "High"
                : (!prices.isEmpty() || fundamentalOpt.isPresent()) ? "Medium" : "Low";

        if (growthDrivers.isEmpty()) {
            growthDrivers.add("Awaiting stronger momentum or fundamental catalyst confirmation.");
        }
        if (risks.isEmpty()) {
            risks.add("Standard equity market volatility and sector rotation risk.");
        }

        node.put("symbol", symbol);
        node.put("businessOverview", businessOverview);
        node.put("businessQuality", businessQuality);
        node.set("growthDrivers", toArrayNode(growthDrivers));
        node.put("valuationView", valuationView);
        node.put("fundamentalDataStatus", fundamentalStatus);
        node.put("stockOutlook", outlook);
        node.put("action", action);
        node.put("confidence", confidence);
        node.put("reasoning", String.format(Locale.US,
                "Composite multi-factor score is %d/100 (Technical: %d/40, Fundamentals: %d/40, News: %d/20). %s.",
                totalScore, technicalScore, fundamentalScore, newsScore, trendText));
        node.set("risks", toArrayNode(risks));
        node.set("dataGaps", toArrayNode(dataGaps));
        node.put("holdingAnalysis", holdingAnalysis);
        return node;
    }

    private ArrayNode toArrayNode(List<String> items) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (String item : items) arr.add(item);
        return arr;
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        if (node != null && node.hasNonNull(field)) {
            return node.get(field).asText(fallback);
        }
        return fallback;
    }

    private static double numberOrZero(JsonNode node, String... candidateFields) {
        if (node == null) return 0.0;
        for (String field : candidateFields) {
            if (node.hasNonNull(field)) {
                return node.get(field).asDouble(0.0);
            }
        }
        return 0.0;
    }
}
