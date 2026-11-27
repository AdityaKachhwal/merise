package com.minevest.fincrux;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/company-research")
public class FincruxResearchController {

    private final FincruxResearchService researchService;

    public FincruxResearchController(FincruxResearchService researchService) {
        this.researchService = researchService;
    }

    @GetMapping("/{symbol}")
    public Map<String, Object> getCachedResearch(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "consolidated") String statementType
    ) {
        return researchService.getCached(symbol, statementType);
    }

    @PostMapping("/{symbol}/refresh")
    public Map<String, Object> refreshResearch(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "consolidated") String statementType
    ) {
        return researchService.refresh(symbol, statementType);
    }
}
