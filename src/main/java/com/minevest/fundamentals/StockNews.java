package com.minevest.fundamentals;

import java.time.Instant;

public record StockNews(
        Long id,
        String symbol,
        String headline,
        String summary,
        String source,
        String url,
        Instant publishedAt,
        String sentimentTag
) {}
