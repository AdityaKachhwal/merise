package com.minevest.fundamentals;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "company_fundamentals")
public class CompanyFundamentalEntity {
    @Id
    @Column(length = 50)
    private String symbol;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(length = 100)
    private String sector;

    @Column(name = "market_cap")
    private BigDecimal marketCap;

    @Column(name = "pe_ratio")
    private BigDecimal peRatio;

    @Column(name = "pb_ratio")
    private BigDecimal pbRatio;

    private BigDecimal roe;

    @Column(name = "debt_to_equity")
    private BigDecimal debtToEquity;

    @Column(name = "revenue_growth_yoy")
    private BigDecimal revenueGrowthYoy;

    @Column(name = "profit_growth_yoy")
    private BigDecimal profitGrowthYoy;

    @Column(name = "dividend_yield")
    private BigDecimal dividendYield;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public CompanyFundamentalEntity() {}

    public CompanyFundamental toRecord() {
        return new CompanyFundamental(
                symbol, companyName, sector, marketCap, peRatio, pbRatio,
                roe, debtToEquity, revenueGrowthYoy, profitGrowthYoy, dividendYield, updatedAt
        );
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public BigDecimal getMarketCap() { return marketCap; }
    public void setMarketCap(BigDecimal marketCap) { this.marketCap = marketCap; }
    public BigDecimal getPeRatio() { return peRatio; }
    public void setPeRatio(BigDecimal peRatio) { this.peRatio = peRatio; }
    public BigDecimal getPbRatio() { return pbRatio; }
    public void setPbRatio(BigDecimal pbRatio) { this.pbRatio = pbRatio; }
    public BigDecimal getRoe() { return roe; }
    public void setRoe(BigDecimal roe) { this.roe = roe; }
    public BigDecimal getDebtToEquity() { return debtToEquity; }
    public void setDebtToEquity(BigDecimal debtToEquity) { this.debtToEquity = debtToEquity; }
    public BigDecimal getRevenueGrowthYoy() { return revenueGrowthYoy; }
    public void setRevenueGrowthYoy(BigDecimal revenueGrowthYoy) { this.revenueGrowthYoy = revenueGrowthYoy; }
    public BigDecimal getProfitGrowthYoy() { return profitGrowthYoy; }
    public void setProfitGrowthYoy(BigDecimal profitGrowthYoy) { this.profitGrowthYoy = profitGrowthYoy; }
    public BigDecimal getDividendYield() { return dividendYield; }
    public void setDividendYield(BigDecimal dividendYield) { this.dividendYield = dividendYield; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
