package com.minevest.fundamentals;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class CompanyIntelligenceService {
    private static final Set<String> NEGATIVE_KEYWORDS = Set.of(
            "fraud", "penalty", "sebi", "default", "downgrade", "loss", "resignation",
            "investigation", "raid", "lawsuit", "insolvency", "nclt", "ban", "recall"
    );
    private static final Set<String> POSITIVE_KEYWORDS = Set.of(
            "profit surge", "record revenue", "order win", "dividend", "buyback", "upgrade",
            "expansion", "approval", "partnership", "beat estimates", "debt free"
    );

    private final CompanyFundamentalRepository fundamentalRepository;
    private final StockNewsRepository newsRepository;

    public CompanyIntelligenceService(CompanyFundamentalRepository fundamentalRepository,
                                      StockNewsRepository newsRepository) {
        this.fundamentalRepository = fundamentalRepository;
        this.newsRepository = newsRepository;
    }

    @Transactional
    public CompanyFundamental upsertFundamental(CompanyFundamental input) {
        if (input == null || input.symbol() == null || input.symbol().isBlank()) {
            throw new IllegalArgumentException("Symbol is required");
        }
        String sym = input.symbol().trim().toUpperCase(Locale.ROOT);
        CompanyFundamentalEntity entity = fundamentalRepository.findById(sym)
                .orElseGet(CompanyFundamentalEntity::new);
        entity.setSymbol(sym);
        entity.setCompanyName(input.companyName());
        entity.setSector(input.sector());
        entity.setMarketCap(input.marketCap());
        entity.setPeRatio(input.peRatio());
        entity.setPbRatio(input.pbRatio());
        entity.setRoe(input.roe());
        entity.setDebtToEquity(input.debtToEquity());
        entity.setRevenueGrowthYoy(input.revenueGrowthYoy());
        entity.setProfitGrowthYoy(input.profitGrowthYoy());
        entity.setDividendYield(input.dividendYield());
        entity.setUpdatedAt(Instant.now());
        return fundamentalRepository.save(entity).toRecord();
    }

    @Transactional(readOnly = true)
    public Optional<CompanyFundamental> getFundamental(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return fundamentalRepository.findById(symbol.trim().toUpperCase(Locale.ROOT))
                .map(CompanyFundamentalEntity::toRecord);
    }

    @Transactional
    public StockNews addNews(StockNews input) {
        if (input == null || input.symbol() == null || input.symbol().isBlank()
                || input.headline() == null || input.headline().isBlank()) {
            throw new IllegalArgumentException("Symbol and headline are required");
        }
        StockNewsEntity entity = new StockNewsEntity();
        entity.setSymbol(input.symbol().trim().toUpperCase(Locale.ROOT));
        entity.setHeadline(input.headline().trim());
        entity.setSummary(input.summary());
        entity.setSource(input.source() == null ? "Manual / Feed" : input.source());
        entity.setUrl(input.url());
        entity.setPublishedAt(input.publishedAt() != null ? input.publishedAt() : Instant.now());
        entity.setSentimentTag(
                input.sentimentTag() != null && !input.sentimentTag().isBlank()
                        ? input.sentimentTag().trim().toUpperCase(Locale.ROOT)
                        : classifyHeadline(input.headline())
        );
        return newsRepository.save(entity).toRecord();
    }

    @Transactional(readOnly = true)
    public List<StockNews> recentNews(String symbol, int days) {
        if (symbol == null || symbol.isBlank()) return List.of();
        Instant cutoff = Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS);
        return newsRepository.findBySymbolIgnoreCaseAndPublishedAtAfterOrderByPublishedAtDesc(
                        symbol.trim(), cutoff)
                .stream()
                .map(StockNewsEntity::toRecord)
                .toList();
    }

    public static String classifyHeadline(String headline) {
        if (headline == null || headline.isBlank()) return "NEUTRAL";
        String lower = headline.toLowerCase(Locale.ROOT);
        for (String neg : NEGATIVE_KEYWORDS) {
            if (lower.contains(neg)) return "NEGATIVE";
        }
        for (String pos : POSITIVE_KEYWORDS) {
            if (lower.contains(pos)) return "POSITIVE";
        }
        return "NEUTRAL";
    }
}
