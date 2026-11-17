package com.minevest.fundamentals;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CompanyIntelligenceController {
    private final CompanyIntelligenceService service;

    public CompanyIntelligenceController(CompanyIntelligenceService service) {
        this.service = service;
    }

    @PostMapping("/fundamentals")
    public CompanyFundamental saveFundamental(@RequestBody CompanyFundamental fundamental) {
        return service.upsertFundamental(fundamental);
    }

    @GetMapping("/fundamentals/{symbol}")
    public CompanyFundamental getFundamental(@PathVariable String symbol) {
        return service.getFundamental(symbol)
                .orElseThrow(() -> new IllegalArgumentException("No fundamentals found for symbol: " + symbol));
    }

    @PostMapping("/news")
    public StockNews addNews(@RequestBody StockNews news) {
        return service.addNews(news);
    }

    @GetMapping("/news/{symbol}")
    public List<StockNews> getNews(@PathVariable String symbol,
                                   @RequestParam(defaultValue = "30") int days) {
        return service.recentNews(symbol, days);
    }
}
