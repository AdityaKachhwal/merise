package com.minevest.marketdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface DailyPriceRepository
        extends JpaRepository<DailyPriceEntity, Long> {

    boolean existsByTradingDateAndSymbolAndSeriesAndIsin(
            LocalDate tradingDate, String symbol, String series, String isin);

    List<DailyPriceEntity> findBySymbolIgnoreCaseOrderByTradingDateAsc(String symbol);

    List<DailyPriceEntity> findAllByOrderByTradingDateAsc();

    List<DailyPriceEntity> findAllByOrderByTradingDateDesc();

    List<DailyPriceEntity> findByTradingDateBetween(LocalDate from, LocalDate to);

    List<DailyPriceEntity> findByTradingDateIn(Collection<LocalDate> tradingDates);

    long countByTradingDate(LocalDate date);

    @Query("SELECT DISTINCT d.symbol FROM DailyPriceEntity d ORDER BY d.symbol ASC")
    List<String> findDistinctSymbolsOrderBySymbolAsc();

    @Query("SELECT DISTINCT d.tradingDate FROM DailyPriceEntity d WHERE d.tradingDate IS NOT NULL")
    List<LocalDate> findDistinctTradingDates();
    @Query("""
    SELECT DISTINCT d.symbol, d.isin
    FROM DailyPriceEntity d
    WHERE d.instrumentType = 'STK'
      AND d.series = 'EQ'
      AND d.isin LIKE 'INE%'
      AND d.symbol IS NOT NULL
      AND TRIM(d.symbol) <> ''
      AND d.close IS NOT NULL
      AND d.close > 0
      AND d.high IS NOT NULL
      AND d.low IS NOT NULL
    ORDER BY d.symbol, d.isin
    """)
    List<Object[]> findEligibleSecurities();

    @Query("SELECT MAX(d.tradingDate) FROM DailyPriceEntity d")
    LocalDate findLatestTradingDate();

    List<DailyPriceEntity>
    findBySymbolIgnoreCaseAndIsinAndSeriesIgnoreCaseAndInstrumentTypeIgnoreCaseOrderByTradingDateAsc(
            String symbol, String isin, String series, String instrumentType
    );
}
