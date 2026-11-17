package com.minevest.history;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stock_recommendations")
public class StockRecommendationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private AnalysisRunEntity analysisRun;

    @Column(name = "recommendation_order", nullable = false)
    private int recommendationOrder;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(name = "latest_close")
    private BigDecimal latestClose;

    @Column(name = "research_score", nullable = false)
    private int researchScore;

    @Column(name = "return_5d", nullable = false)
    private double return5d;

    @Column(name = "return_20d", nullable = false)
    private double return20d;

    @Column(name = "return_60d", nullable = false)
    private double return60d;

    @Column(name = "sma_20")
    private BigDecimal sma20;

    @Column(name = "sma_50")
    private BigDecimal sma50;

    @Column(name = "volume_strength", nullable = false)
    private double volumeStrength;

    @Column(name = "volatility_20d", nullable = false)
    private double volatility20d;

    @Column(name = "drawdown_60d", nullable = false)
    private double drawdown60d;

    @Column(length = 100)
    private String trend;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String risk;

    @Column(name = "rsi_14", nullable = false)
    private double rsi14;

    @Column(name = "atr_14_percent", nullable = false)
    private double atr14Percent;

    @Column(name = "average_daily_traded_value_20")
    private BigDecimal averageDailyTradedValue20;

    @Column(name = "momentum_score", nullable = false)
    private int momentumScore;

    @Column(name = "trend_score", nullable = false)
    private int trendScore;

    @Column(name = "volume_score", nullable = false)
    private int volumeScore;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "liquidity_score", nullable = false)
    private int liquidityScore;

    @Column(length = 100)
    private String setup;

    protected StockRecommendationEntity() {}

    public StockRecommendationEntity(AnalysisRunEntity analysisRun, int order, StockRecommendation rec) {
        this.analysisRun = analysisRun;
        this.recommendationOrder = order;
        this.symbol = rec.symbol();
        this.latestClose = rec.latestClose();
        this.researchScore = rec.researchScore();
        this.return5d = rec.return5d();
        this.return20d = rec.return20d();
        this.return60d = rec.return60d();
        this.sma20 = rec.sma20();
        this.sma50 = rec.sma50();
        this.volumeStrength = rec.volumeStrength();
        this.volatility20d = rec.volatility20d();
        this.drawdown60d = rec.drawdown60d();
        this.trend = rec.trend();
        this.reason = rec.reason();
        this.risk = rec.risk();
        this.rsi14 = rec.rsi14();
        this.atr14Percent = rec.atr14Percent();
        this.averageDailyTradedValue20 = rec.averageDailyTradedValue20();
        this.momentumScore = rec.momentumScore();
        this.trendScore = rec.trendScore();
        this.volumeScore = rec.volumeScore();
        this.riskScore = rec.riskScore();
        this.liquidityScore = rec.liquidityScore();
        this.setup = rec.setup();
    }

    public StockRecommendation toRecommendation() {
        return new StockRecommendation(
                symbol,
                latestClose,
                researchScore,
                return5d,
                return20d,
                return60d,
                sma20,
                sma50,
                volumeStrength,
                volatility20d,
                drawdown60d,
                trend,
                reason,
                risk,
                rsi14,
                atr14Percent,
                averageDailyTradedValue20,
                momentumScore,
                trendScore,
                volumeScore,
                riskScore,
                liquidityScore,
                setup
        );
    }

    public int getRecommendationOrder() { return recommendationOrder; }
}
