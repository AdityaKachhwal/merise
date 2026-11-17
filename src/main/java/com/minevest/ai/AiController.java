package com.minevest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "http://localhost:5173")
public class AiController {

    private final PortfolioReviewProvider reviewProvider;

    public AiController(PortfolioReviewProvider reviewProvider) {
        this.reviewProvider = reviewProvider;
    }

    @PostMapping("/portfolio-review")
    public JsonNode reviewPortfolio(@RequestBody JsonNode portfolio) {
        return reviewProvider.reviewPortfolio(portfolio);
    }
}
