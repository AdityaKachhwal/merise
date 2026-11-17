package com.minevest.marketdata;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "daily_prices")
public class DailyPriceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, length = 20)
    private String series;

    @Column(nullable = false, length = 20)
    private String isin = "";

    @Column(name = "instrument_type")
    private String instrumentType;

    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;

    @Column(name = "previous_close")
    private BigDecimal previousClose;

    private long volume;

    @Column(name = "traded_value")
    private BigDecimal tradedValue;

    protected DailyPriceEntity() {}

    public DailyPriceEntity(DailyPrice p) {
        this.tradingDate = p.tradingDate();
        this.symbol = p.symbol();
        this.series = p.series();
        this.isin = p.isin() == null ? "" : p.isin();
        this.instrumentType = p.instrumentType();
        this.open = p.open();
        this.high = p.high();
        this.low = p.low();
        this.close = p.close();
        this.previousClose = p.previousClose();
        this.volume = p.volume();
        this.tradedValue = p.tradedValue();
    }

    public DailyPrice toDailyPrice() {
        return new DailyPrice(
                tradingDate, symbol, series, isin, instrumentType,
                open, high, low, close, previousClose, volume, tradedValue
        );
    }

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public String getSymbol() { return symbol; }
    public String getSeries() { return series; }
    public String getIsin() { return isin; }
}
