package com.minevest.fincrux;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class FincruxDailyUsageService {

    private static final int DAILY_CALL_LIMIT = 5;
    private final FincruxDailyUsageRepository usageRepository;

    public FincruxDailyUsageService(FincruxDailyUsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordCallOrThrow() {
        LocalDate today = LocalDate.now();
        FincruxDailyUsageEntity usage = usageRepository.findById(today)
                .orElseGet(() -> {
                    FincruxDailyUsageEntity fresh = new FincruxDailyUsageEntity();
                    fresh.setUsageDate(today);
                    fresh.setCallCount(0);
                    return fresh;
                });

        if (usage.getCallCount() >= DAILY_CALL_LIMIT) {
            throw new IllegalStateException(
                    "Fincrux daily limit reached (5 calls). Try again tomorrow."
            );
        }

        usage.setCallCount(usage.getCallCount() + 1);
        usageRepository.saveAndFlush(usage);
    }
}
