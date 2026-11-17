package com.minevest.fundamentals;

import java.math.BigDecimal;
import java.time.Instant;

public record CompanyFundamental(
        String symbol,
        String companyName,
        String sector,
        BigDecimal marketCap,
        BigDecimal peRatio,
        BigDecimal pbRatio,
        BigDecimal roe,
        BigDecimal debtToEquity,
        BigDecimal revenueGrowthYoy,
        BigDecimal profitGrowthYoy,
        BigDecimal dividendYield,
        Instant updatedAt
) {}
