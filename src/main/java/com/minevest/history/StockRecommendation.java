package com.minevest.history;

import java.math.BigDecimal;

public record StockRecommendation(
        String symbol,
        BigDecimal latestClose,
        int researchScore,
        double return5d,
        double return20d,
        double return60d,
        BigDecimal sma20,
        BigDecimal sma50,
        double volumeStrength,
        double volatility20d,
        double drawdown60d,
        String trend,
        String reason,
        String risk,
        double rsi14,
        double atr14Percent,
        BigDecimal averageDailyTradedValue20,
        int momentumScore,
        int trendScore,
        int volumeScore,
        int riskScore,
        int liquidityScore,
        String setup,
        String momentumDirection
) {}
