
package com.minevest.fundamentals;

import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api")
public class CompanyIntelligenceController {

    private final CompanyIntelligenceService service;
    private final StockNewsRepository newsRepository;

    public CompanyIntelligenceController(
            CompanyIntelligenceService service,
            StockNewsRepository newsRepository
    ) {
        this.service = service;
        this.newsRepository = newsRepository;
    }

    @PostMapping("/fundamentals")
    public CompanyFundamental saveFundamental(
            @RequestBody CompanyFundamental fundamental
    ) {
        return service.upsertFundamental(fundamental);
    }

    @GetMapping("/fundamentals/{symbol}")
    public CompanyFundamental getFundamental(
            @PathVariable String symbol
    ) {
        return service.getFundamental(symbol)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "No fundamentals found for symbol: " + symbol
                        )
                );
    }

    @PostMapping("/news")
    public StockNews addNews(@RequestBody StockNews news) {
        return service.addNews(news);
    }

    @GetMapping("/news/{symbol}")
    public List<StockNews> getNews(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "30") int days
    ) {
        return service.recentNews(symbol, days);
    }

    @GetMapping("/news/market")
    public List<StockNews> getMarketNews() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        return newsRepository
                .findBySymbolIgnoreCaseAndPublishedAtAfterOrderByPublishedAtDesc(
                        "MARKET",
                        cutoff
                )
                .stream()
                .limit(5)
                .map(StockNewsEntity::toRecord)
                .toList();
    }
}
