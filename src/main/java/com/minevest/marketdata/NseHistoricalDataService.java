package com.minevest.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class NseHistoricalDataService {
    private static final Logger log = LoggerFactory.getLogger(NseHistoricalDataService.class);

    // UDiFF archive (valid from Jan 2024 onwards)
    private static final String UDIFF_ARCHIVE_URL =
            "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip";
    // Legacy archive (valid prior to July 2024, covers 5-year historical range)
    private static final String LEGACY_ARCHIVE_URL =
            "https://nsearchives.nseindia.com/content/historical/EQUITIES/%d/%s/cm%sbhav.csv.zip";

    private static final LocalDate UDIFF_START_DATE = LocalDate.of(2024, 1, 2);
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter LEGACY_FILE_DATE = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.ENGLISH);
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
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Incremental sync: checks missing dates after latest stored date (or 180-day backfill if empty).
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

        String mode = initialBackfill ? "INITIAL_180_DAY_BACKFILL" : "INCREMENTAL_UPDATE";
        return syncDateRange(fromDate, today, mode, true, 250L);
    }

    /**
     * Bulk historical backfill (e.g., 5 years = 1825 days). Automatically routes pre-2024 dates to
     * legacy NSE archives and 2024+ dates to UDiFF archives, committing in batches with polite pacing.
     */
    public synchronized NseSyncResult syncHistoricalYears(int years) throws Exception {
        int safeYears = Math.max(1, Math.min(years, 10));
        LocalDate today = LocalDate.now(MARKET_ZONE);
        LocalDate fromDate = today.minusYears(safeYears);
        return syncDateRange(fromDate, today, "BULK_" + safeYears + "_YEAR_BACKFILL", false, 600L);
    }

    private NseSyncResult syncDateRange(LocalDate fromDate,
                                        LocalDate toDate,
                                        String mode,
                                        boolean respectCooldown,
                                        long politeDelayMs) throws Exception {
        Set<LocalDate> datesAlreadyStored = new HashSet<>(priceRepository.findDistinctTradingDates());
        LocalDate latestStoredDate = datesAlreadyStored.stream().max(Comparator.naturalOrder()).orElse(null);

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
        List<DailyPrice> batchRows = new ArrayList<>();
        int consecutiveAccessFailures = 0;
        List<String> failedDates = new ArrayList<>();

        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() >= 6) continue;
            if (datesAlreadyStored.contains(date)) {
                datesAlreadyPresent++;
                continue;
            }

            NseSyncAttempt previousAttempt = attemptsByDate.get(date);
            if (respectCooldown && isCoolingDown(previousAttempt)) {
                datesSkippedRecentlyChecked++;
                continue;
            }
            // Skip known historical NSE holidays already confirmed as NOT_PUBLISHED during bulk scans
            if (!respectCooldown && previousAttempt != null
                    && "NOT_PUBLISHED".equals(previousAttempt.outcome())
                    && date.isBefore(toDate.minusDays(7))) {
                datesWithoutArchive++;
                continue;
            }

            weekdaysChecked++;
            URI uri = buildArchiveUri(date);
            HttpResponse<byte[]> response;
            try {
                response = download(uri);
            } catch (Exception ex) {
                failedDates.add(date + " (" + safeMessage(ex) + ")");
                recordAttempt(attemptsByDate, date, "FAILURE");
                consecutiveAccessFailures++;
                if (consecutiveAccessFailures >= 4) {
                    failedDates.add("Stopped further downloads after four consecutive connection/access failures.");
                    break;
                }
                sleepQuietly(politeDelayMs);
                continue;
            }

            if (response.statusCode() == 404 || response.statusCode() == 410) {
                datesWithoutArchive++;
                recordAttempt(attemptsByDate, date, "NOT_PUBLISHED");
                consecutiveAccessFailures = 0;
                sleepQuietly(Math.min(politeDelayMs, 250L));
                continue;
            }
            if (response.statusCode() != 200) {
                failedDates.add(date + " (NSE returned HTTP " + response.statusCode() + ")");
                recordAttempt(attemptsByDate, date, "FAILURE");
                consecutiveAccessFailures++;
                if (consecutiveAccessFailures >= 4) {
                    failedDates.add("Stopped further downloads after four consecutive NSE access failures.");
                    break;
                }
                sleepQuietly(politeDelayMs);
                continue;
            }

            try {
                List<DailyPrice> rows = parseArchive(response.body());
                if (rows.isEmpty()) {
                    throw new IllegalStateException("Archive contained no readable price rows.");
                }
                LocalDate requestedDate = date;
                boolean containsRequestedDate = rows.stream().anyMatch(row -> requestedDate.equals(row.tradingDate()));
                if (!containsRequestedDate) {
                    // NSE occasionally serves prior session's file on non-trading holidays
                    datesWithoutArchive++;
                    recordAttempt(attemptsByDate, date, "NOT_PUBLISHED");
                    consecutiveAccessFailures = 0;
                    sleepQuietly(Math.min(politeDelayMs, 250L));
                    continue;
                }

                rowsParsed += rows.size();
                batchRows.addAll(rows);
                archivesDownloaded++;
                datesAlreadyStored.add(date);
                recordAttempt(attemptsByDate, date, "DOWNLOADED");
                consecutiveAccessFailures = 0;

                // Flush every 15 archives to keep memory footprint low during 5-year backfill
                if (archivesDownloaded % 15 == 0) {
                    rowsAdded += marketDataService.savePrices(batchRows);
                    batchRows.clear();
                    log.info("Bulk sync progress: {} archives downloaded ({} rows added so far)...",
                            archivesDownloaded, rowsAdded);
                }
            } catch (Exception ex) {
                failedDates.add(date + " (" + safeMessage(ex) + ")");
                recordAttempt(attemptsByDate, date, "FAILURE");
                consecutiveAccessFailures++;
                if (consecutiveAccessFailures >= 4) {
                    failedDates.add("Stopped further downloads after four consecutive archive/format failures.");
                    break;
                }
            }

            sleepQuietly(politeDelayMs);
        }

        if (!batchRows.isEmpty()) {
            rowsAdded += marketDataService.savePrices(batchRows);
        }

        // Persist sync attempts (keep up to 2000 for multi-year backfill tracking)
        List<NseSyncAttempt> savedAttempts = attemptsByDate.values().stream()
                .sorted(Comparator.comparing(NseSyncAttempt::date))
                .toList();
        if (savedAttempts.size() > 2000) {
            List<NseSyncAttempt> trimmed = savedAttempts.subList(savedAttempts.size() - 2000, savedAttempts.size());
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
        syncAttemptRepository.saveAll(savedAttempts.stream().map(NseSyncAttemptEntity::new).toList());

        LocalDate newLatestDate = datesAlreadyStored.stream().max(Comparator.naturalOrder()).orElse(latestStoredDate);
        String message;
        if (!failedDates.isEmpty() && archivesDownloaded == 0) {
            message = "NSE sync had access or archive errors. Existing PostgreSQL market data was retained.";
        } else if (!failedDates.isEmpty()) {
            message = "NSE sync partially completed (" + archivesDownloaded + " archives downloaded). Re-run to resume remaining dates.";
        } else if (archivesDownloaded == 0) {
            message = "PostgreSQL market history is already up to date for the requested range.";
        } else {
            message = "NSE sync completed (" + archivesDownloaded + " sessions downloaded, " + rowsAdded + " new rows added).";
        }

        return new NseSyncResult(mode, fromDate, toDate, newLatestDate, weekdaysChecked,
                datesAlreadyPresent, datesSkippedRecentlyChecked, archivesDownloaded,
                datesWithoutArchive, rowsParsed, rowsAdded, List.copyOf(failedDates), message);
    }

    private URI buildArchiveUri(LocalDate date) {
        if (!date.isBefore(UDIFF_START_DATE)) {
            return URI.create(UDIFF_ARCHIVE_URL.formatted(FILE_DATE.format(date)));
        }
        String monthUpper = date.getMonth().name().substring(0, 3).toUpperCase(Locale.ENGLISH);
        String ddMonYyyy = LEGACY_FILE_DATE.format(date).toUpperCase(Locale.ENGLISH);
        return URI.create(LEGACY_ARCHIVE_URL.formatted(date.getYear(), monthUpper, ddMonYyyy));
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
                .timeout(Duration.ofSeconds(10))
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

    private static void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
