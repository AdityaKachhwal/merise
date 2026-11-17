package com.minevest.history;

import com.minevest.marketdata.NseHistoricalDataService;
import com.minevest.marketdata.NseSyncResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class HistoryController {
    private final AnalysisHistoryService historyService;
    private final MarketAnalysisService analysis;
    private final NseHistoricalDataService nseData;

    public HistoryController(AnalysisHistoryService historyService,
                             MarketAnalysisService analysis,
                             NseHistoricalDataService nseData) {
        this.historyService = historyService;
        this.analysis = analysis;
        this.nseData = nseData;
    }

    @PostMapping("/analysis/run")
    public AnalysisSnapshot run() throws Exception {
        NseSyncResult sync;
        String syncNote;
        try {
            sync = nseData.syncRecentSessions();
            syncNote = sync.message() + " New rows added: " + sync.rowsAdded()
                    + "; archives downloaded: " + sync.archivesDownloaded() + ".";
        } catch (Exception ex) {
            syncNote = "NSE update could not be completed (" + safeMessage(ex)
                    + "). Analysis continued using the PostgreSQL stored market data.";
        }

        AnalysisSnapshot calculated = analysis.run();
        AnalysisSnapshot snapshot = new AnalysisSnapshot(
                calculated.runId(), calculated.createdAt(), calculated.status(),
                calculated.importedPriceRows(), calculated.distinctSymbols(),
                calculated.note() + " NSE data update: " + syncNote,
                calculated.recommendations());

        return historyService.save(snapshot);
    }

    /** Diagnostic endpoint: runs only the incremental NSE sync, without stock analysis. */
    @PostMapping("/market-data/sync")
    public NseSyncResult syncMarketData() throws Exception {
        return nseData.syncRecentSessions();
    }

    @GetMapping("/history")
    public List<AnalysisSnapshot> history() {
        return historyService.findAll();
    }

    @GetMapping("/history/{runId}")
    public AnalysisSnapshot get(@PathVariable String runId) {
        return historyService.findByRunId(runId);
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
