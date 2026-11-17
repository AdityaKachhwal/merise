package com.minevest.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pluggable strategy interface for portfolio and holding intelligence.
 * Default active implementation is {@link DeterministicPortfolioReviewService} (PostgreSQL + Quant rules).
 * Can be swapped back to {@link GeminiAiService} (or any future LLM provider) via Spring configuration:
 * minevest.ai.provider=deterministic | gemini
 */
public interface PortfolioReviewProvider {
    JsonNode reviewPortfolio(JsonNode portfolio);
}
