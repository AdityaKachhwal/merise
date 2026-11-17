package com.minevest.marketdata;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MarketDataController {
    private final MarketDataService service;
    private final NseHistoricalDataService nseData;

    public MarketDataController(MarketDataService service, NseHistoricalDataService nseData) {
        this.service = service;
        this.nseData = nseData;
    }

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP", "application", "MineVest"); }

    @PostMapping(value = "/market-data/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importFile(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) throw new IllegalArgumentException("Uploaded file is empty");
        return service.importFile(file);
    }

    @GetMapping("/market-data/prices")
    public List<DailyPrice> prices(@RequestParam String symbol) {
        return service.prices(symbol);
    }

    @GetMapping("/market-data/sync-attempts")
    public List<NseSyncAttempt> syncAttempts() {
        return nseData.syncAttempts();
    }

    @GetMapping("/stocks")
    public Map<String, Object> stocks() {
        return Map.of("symbols", service.symbols(), "totalPriceRows", service.totalRows());
    }
}
