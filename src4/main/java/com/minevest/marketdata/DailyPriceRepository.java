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
}
