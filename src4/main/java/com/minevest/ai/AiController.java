
package com.minevest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "http://localhost:5173")
public class AiController {

    private final GeminiAiService geminiAiService;

    public AiController(GeminiAiService geminiAiService) {
        this.geminiAiService = geminiAiService;
    }

    @PostMapping("/portfolio-review")
    public JsonNode reviewPortfolio(@RequestBody JsonNode portfolio) {
        return geminiAiService.reviewPortfolio(portfolio);
    }
}
