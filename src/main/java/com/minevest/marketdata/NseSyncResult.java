package com.minevest.marketdata;

import java.time.LocalDate;
import java.util.List;

public record NseSyncResult(
        String mode,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate latestStoredDate,
        int weekdaysChecked,
        int datesAlreadyPresent,
        int datesSkippedRecentlyChecked,
        int archivesDownloaded,
        int datesWithoutArchive,
        int rowsParsed,
        int rowsAdded,
        List<String> failedDates,
        String message
) {}
