package com.minevest.fincrux;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface FincruxDailyUsageRepository
        extends JpaRepository<FincruxDailyUsageEntity, LocalDate> {
}
