package com.minevest.marketdata;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class NseHistoricalDataService {
    private static final String ARCHIVE_URL =
            "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int INITIAL_BACKFILL_DAYS = 180;
    private static final int MAX_INCREMENTAL_LOOKBACK_DAYS = 10;
    private static final Duration NOT_PUBLISHED_COOLDOWN = Duration.ofHours(8);
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(10);

    private final DailyPriceRepository priceRepository;
    private final NseSyncAttemptRepository syncAttemptRepository;
    private final MarketDataService marketDataService;
    private final HttpClient httpClient;

    public NseHistoricalDataService(DailyPriceRepository priceRepository,
                                    NseSyncAttemptRepository syncAttemptRepository,
                                    MarketDataService marketDataService) {
        this.priceRepository = priceRepository;
        this.syncAttemptRepository = syncAttemptRepository;
        this.marketDataService = marketDataService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Uses the existing PostgreSQL history as its baseline. If no history exists, it performs
     * the initial 180-calendar-day backfill. Otherwise it only checks missing dates after
     * the latest stored trading date (bounded to the most recent 10 calendar days).
     */
    @Transactional
    public synchronized NseSyncResult syncRecentSessions() throws Exception {
        LocalDate today = LocalDate.now(MARKET_ZONE);
        Set<LocalDate> datesAlreadyStored = new HashSet<>(priceRepository.findDistinctTradingDates());

        LocalDate latestStoredDate = datesAlreadyStored.stream().max(Comparator.naturalOrder()).orElse(null);
        boolean initialBackfill = latestStoredDate == null;
        LocalDate fromDate = initialBackfill
                ? today.minusDays(INITIAL_BACKFILL_DAYS - 1L)
                : latestStoredDate.plusDays(1);

        if (!initialBackfill) {
            LocalDate earliestAllowed = today.minusDays(MAX_INCREMENTAL_LOOKBACK_DAYS - 1L);
            if (fromDate.isBefore(earliestAllowed)) fromDate = earliestAllowed;
        }

        List<NseSyncAttemptEntity> attemptEntities = syncAttemptRepository.findAllByOrderByDateAsc();
        Map<LocalDate, NseSyncAttempt> attemptsByDate = new HashMap<>();
        for (NseSyncAttemptEntity entity : attemptEntities) {
            NseSyncAttempt attempt = entity.toAttempt();
            attemptsByDate.put(attempt.date(), attempt);
        }

        int weekdaysChecked = 0;
        int datesAlreadyPresent = 0;
        int datesSkippedRecentlyChecked = 0;
        int archivesDownloaded = 0;
        int datesWithoutArchive = 0;
        int rowsParsed = 0;
        int rowsAdded = 0;
        List<DailyPrice> downloadedRows = new ArrayList<>();
        int consecutiveAccessFailures = 0;
        List<String> failedDates = new ArrayList<>();

        for (LocalDate date = fromDate; !date.isAfter(today); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() >= 6) continue;
            if (datesAlreadyStored.contains(date)) {
                datesAlreadyPresent++;
                continue;
            }

            NseSyncAttempt previousAttempt = attemptsByDate.get(date);
            if (isCoolingDown(previousAttempt)) {
                datesSkippedRecentlyChecked++;
                continue;
            }

            weekdaysChecked++;
            String dateText = FILE_DATE.format(date);
            URI uri = URI.create(ARCHIVE_URL.formatted(dateText));
            HttpResponse<byte[]> response;
            try {
                response = download(uri);
            } catch (Exception ex) {
                failedDates.add(date + " (" + safeMessage(ex) + ")");
                recordAttempt(attemptsByDate, date, "FAILURE");
                consecutiveAccessFailures++;
                if (consecutiveAccessFailures >= 2) {
                    failedDates.add("Stopped further downloads after two consecutive connection/access failures.");
                    break;
                }
                continue;
            }

            if (response.statusCode() == 404 || response.statusCode() == 410) {
                datesWithoutArchive++;
                recordAttempt(attemptsByDate, date, "NOT_PUBLISHED");
                consecutiveAccessFailures = 0;
                continue;
            }
            if (response.statusCode() != 200) {
                failedDates.add(date + " (NSE returned HTTP " + response.statusCode() + ")");
                recordAttempt(attemptsByDate, date, "FAILURE");
                consecutiveAccessFailures++;
                if (consecutiveAccessFailures >= 2) {
                    failedDates.add("Stopped further downloads after two consecutive NSE access failures.");
                    break;
                }
                continue;
            }

            try {
                List<DailyPrice> rows = parseArchive(response.body());
                if (rows.isEmpty()) throw new IllegalStateException("Archive contained no readable price rows; the CSV format may have changed.");
                LocalDate requestedDate = date;
                boolean containsRequestedDate = rows.stream().anyMatch(row -> requestedDate.equals(row.tradingDate()));
                if (!containsRequestedDate) throw new IllegalStateException("Archive did not contain the requested trading date.");

                rowsParsed += rows.size();
                downloadedRows.addAll(rows);
                archivesDownloaded++;
                datesAlreadyStored.add(date);
                recordAttempt(attemptsByDate, date, "DOWNLOADED");
                consecutiveAccessFailures = 0;
            } catch (Exception ex) {
                failedDates.add(date + " (" + safeMessage(ex) + ")");
                recordAttempt(attemptsByDate, date, "FAILURE");
                consecutiveAccessFailures++;
                if (consecutiveAccessFailures >= 2) {
                    failedDates.add("Stopped further downloads after two consecutive archive/format failures.");
                    break;
                }
            }
        }

        if (!downloadedRows.isEmpty()) {
            rowsAdded = marketDataService.savePrices(downloadedRows);
        }

        List<NseSyncAttempt> savedAttempts = attemptsByDate.values().stream()
                .sorted(Comparator.comparing(NseSyncAttempt::date))
                .toList();
        if (savedAttempts.size() > 60) {
            List<NseSyncAttempt> trimmed = savedAttempts.subList(savedAttempts.size() - 60, savedAttempts.size());
            Set<LocalDate> keepDates = new HashSet<>();
            for (NseSyncAttempt a : trimmed) keepDates.add(a.date());
            List<NseSyncAttemptEntity> toDelete = attemptEntities.stream()
                    .filter(e -> !keepDates.contains(e.getDate()))
                    .toList();
            if (!toDelete.isEmpty()) {
                syncAttemptRepository.deleteAll(toDelete);
            }
            savedAttempts = trimmed;
        }
        List<NseSyncAttemptEntity> entitiesToSave = savedAttempts.stream()
                .map(NseSyncAttemptEntity::new)
                .toList();
        syncAttemptRepository.saveAll(entitiesToSave);

        LocalDate newLatestDate = datesAlreadyStored.stream().max(Comparator.naturalOrder()).orElse(latestStoredDate);
        String mode = initialBackfill ? "INITIAL_180_DAY_BACKFILL" : "INCREMENTAL_UPDATE";
        String message;
        if (!failedDates.isEmpty() && archivesDownloaded == 0) {
            message = "NSE sync had access or archive errors. Existing PostgreSQL market data was retained and analysis can still run on it.";
        } else if (!failedDates.isEmpty()) {
            message = "NSE sync partially completed. Some dates could not be downloaded; see failedDates.";
        } else if (archivesDownloaded == 0 && !initialBackfill) {
            message = "PostgreSQL market history is up to date for the dates checked. No historical archives were re-downloaded.";
        } else {
            message = "NSE sync completed. Existing dates were reused; weekends and unpublished dates were skipped.";
        }

        return new NseSyncResult(mode, fromDate, today, newLatestDate, weekdaysChecked,
                datesAlreadyPresent, datesSkippedRecentlyChecked, archivesDownloaded,
                datesWithoutArchive, rowsParsed, rowsAdded, List.copyOf(failedDates), message);
    }

    @Transactional(readOnly = true)
    public List<NseSyncAttempt> syncAttempts() {
        return syncAttemptRepository.findAllByOrderByDateAsc().stream()
                .map(NseSyncAttemptEntity::toAttempt)
                .toList();
    }

    private boolean isCoolingDown(NseSyncAttempt attempt) {
        if (attempt == null || attempt.attemptedAt() == null) return false;
        Duration cooldown = "NOT_PUBLISHED".equals(attempt.outcome())
                ? NOT_PUBLISHED_COOLDOWN : FAILURE_COOLDOWN;
        return attempt.attemptedAt().plus(cooldown).isAfter(Instant.now());
    }

    private void recordAttempt(Map<LocalDate, NseSyncAttempt> attempts, LocalDate date, String outcome) {
        attempts.put(date, new NseSyncAttempt(date, Instant.now(), outcome));
    }

    private HttpResponse<byte[]> download(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/130.0 Safari/537.36")
                .header("Accept", "application/zip, application/octet-stream, */*")
                .header("Referer", "https://www.nseindia.com/all-reports")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private List<DailyPrice> parseArchive(byte[] archiveBytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".csv")) {
                    return CsvRowParser.parse(new InputStreamReader(zip, StandardCharsets.UTF_8));
                }
            }
        }
        throw new IllegalStateException("NSE response was not a ZIP archive containing a CSV file.");
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
