package com.minevest.marketdata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CsvRowParser {
    private static final DateTimeFormatter LEGACY_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private CsvRowParser() {}

    static List<DailyPrice> parse(Reader source) throws IOException {
        List<DailyPrice> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return result;
            List<String> headers = splitCsv(headerLine).stream().map(String::trim).toList();
            boolean isUdiff = headers.contains("TckrSymb") && headers.contains("TradDt");
            boolean isLegacy = headers.contains("SYMBOL") && headers.contains("TIMESTAMP");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = splitCsv(line);
                try {
                    if (isUdiff) {
                        String symbol = get(headers, values, "TckrSymb");
                        String series = get(headers, values, "SctySrs");
                        String instrumentType = get(headers, values, "FinInstrmTp");
                        if (symbol.isBlank() || series.isBlank()) continue;
                        result.add(new DailyPrice(
                                LocalDate.parse(get(headers, values, "TradDt")),
                                symbol,
                                series,
                                get(headers, values, "ISIN"),
                                instrumentType,
                                decimal(get(headers, values, "OpnPric")),
                                decimal(get(headers, values, "HghPric")),
                                decimal(get(headers, values, "LwPric")),
                                decimal(get(headers, values, "ClsPric")),
                                decimal(get(headers, values, "PrvsClsgPric")),
                                longValue(get(headers, values, "TtlTradgVol")),
                                decimal(get(headers, values, "TtlTrfVal"))
                        ));
                    } else if (isLegacy) {
                        String symbol = get(headers, values, "SYMBOL");
                        String series = get(headers, values, "SERIES");
                        if (symbol.isBlank() || series.isBlank()) continue;
                        LocalDate date = LocalDate.parse(get(headers, values, "TIMESTAMP"), LEGACY_DATE);
                        String instrumentType = "EQ".equalsIgnoreCase(series) ? "STK" : series;
                        result.add(new DailyPrice(
                                date,
                                symbol,
                                series,
                                get(headers, values, "ISIN"),
                                instrumentType,
                                decimal(get(headers, values, "OPEN")),
                                decimal(get(headers, values, "HIGH")),
                                decimal(get(headers, values, "LOW")),
                                decimal(get(headers, values, "CLOSE")),
                                decimal(get(headers, values, "PREVCLOSE")),
                                longValue(get(headers, values, "TOTTRDQTY")),
                                decimal(get(headers, values, "TOTTRDVAL"))
                        ));
                    }
                } catch (RuntimeException ignored) {
                    // Skip malformed rows; importer reports aggregate skipped count.
                }
            }
        }
        return result;
    }

    private static String get(List<String> headers, List<String> values, String key) {
        int index = headers.indexOf(key);
        return index >= 0 && index < values.size() ? values.get(index).trim() : "";
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static long longValue(String value) {
        return value == null || value.isBlank() ? 0L : new BigDecimal(value).longValue();
    }

    private static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(c);
        }
        fields.add(field.toString());
        return fields;
    }
}
