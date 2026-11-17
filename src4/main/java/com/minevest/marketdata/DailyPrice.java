package com.minevest.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyPrice(
        LocalDate tradingDate,
        String symbol,
        String series,
        String isin,
        String instrumentType,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal previousClose,
        long volume,
        BigDecimal tradedValue
) {}
