package com.minevest.marketdata;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class MarketDataService {
    private final DailyPriceRepository priceRepository;

    public MarketDataService(DailyPriceRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    @Transactional
    public ImportResult importFile(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        String name = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        InputStream csvStream;
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes));
            ZipEntry entry;
            csvStream = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    csvStream = zip;
                    break;
                }
            }
            if (csvStream == null) throw new IllegalArgumentException("ZIP archive does not contain a CSV file");
            List<DailyPrice> rows = CsvRowParser.parse(new InputStreamReader(csvStream, StandardCharsets.UTF_8));
            int added = savePrices(rows);
            return new ImportResult(name, rows.size(), added, rows.size() - added,
                    "Imported and merged rows into PostgreSQL. Duplicate date/symbol/series/ISIN records are not added twice.");
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("Please upload an NSE .zip archive or .csv file");
        }
        List<DailyPrice> rows = CsvRowParser.parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        int added = savePrices(rows);
        return new ImportResult(name, rows.size(), added, rows.size() - added,
                "Imported and merged rows into PostgreSQL. Duplicate date/symbol/series/ISIN records are not added twice.");
    }

    @Transactional
    public int savePrices(List<DailyPrice> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }

        // Deduplicate incoming batch by natural key first
        Map<String, DailyPrice> deduplicated = new LinkedHashMap<>();
        for (DailyPrice p : incoming) {
            if (p == null || p.tradingDate() == null || p.symbol() == null || p.series() == null) {
                continue;
            }
            deduplicated.put(naturalKey(p.tradingDate(), p.symbol(), p.series(), p.isin()), p);
        }
        if (deduplicated.isEmpty()) {
            return 0;
        }

        Set<LocalDate> targetDates = deduplicated.values().stream()
                .map(DailyPrice::tradingDate)
                .collect(Collectors.toSet());

        Set<String> existingKeys = new HashSet<>();
        for (DailyPriceEntity existing : priceRepository.findByTradingDateIn(targetDates)) {
            existingKeys.add(naturalKey(
                    existing.getTradingDate(),
                    existing.getSymbol(),
                    existing.getSeries(),
                    existing.getIsin()
            ));
        }

        List<DailyPriceEntity> toInsert = new ArrayList<>();
        for (Map.Entry<String, DailyPrice> entry : deduplicated.entrySet()) {
            if (!existingKeys.contains(entry.getKey())) {
                toInsert.add(new DailyPriceEntity(entry.getValue()));
            }
        }

        if (!toInsert.isEmpty()) {
            priceRepository.saveAll(toInsert);
        }
        return toInsert.size();
    }

    @Transactional(readOnly = true)
    public List<DailyPrice> prices(String symbol) {
        return priceRepository.findBySymbolIgnoreCaseOrderByTradingDateAsc(symbol)
                .stream()
                .map(DailyPriceEntity::toDailyPrice)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> symbols() {
        return priceRepository.findDistinctSymbolsOrderBySymbolAsc();
    }

    @Transactional(readOnly = true)
    public List<DailyPrice> allPrices() {
        return priceRepository.findAllByOrderByTradingDateAsc()
                .stream()
                .map(DailyPriceEntity::toDailyPrice)
                .toList();
    }

    @Transactional(readOnly = true)
    public long totalRows() {
        return priceRepository.count();
    }

    private static String naturalKey(LocalDate date, String symbol, String series, String isin) {
        return date + "|" + symbol + "|" + series + "|" + (isin == null ? "" : isin);
    }
}
