package com.minevest.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LiveMarketQuote(String symbol, String series, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal previousClose, BigDecimal lastPrice, BigDecimal change, BigDecimal percentChange, long volume, LocalDate marketDate, Instant updatedAt, String latestTimestamp) {
}
