
package com.minevest.fundamentals;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface StockNewsRepository
        extends JpaRepository<StockNewsEntity, Long> {

    List<StockNewsEntity> findBySymbolIgnoreCaseOrderByPublishedAtDesc(
            String symbol
    );

    List<StockNewsEntity>
    findBySymbolIgnoreCaseAndPublishedAtAfterOrderByPublishedAtDesc(
            String symbol,
            Instant after
    );

    boolean existsByUrl(String url);
}
