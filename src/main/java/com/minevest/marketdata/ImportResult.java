package com.minevest.marketdata;

public record ImportResult(
        String fileName,
        int rowsRead,
        int priceRowsImported,
        int rowsSkipped,
        String message
) {}
