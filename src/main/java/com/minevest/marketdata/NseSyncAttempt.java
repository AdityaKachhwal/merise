package com.minevest.marketdata;

import java.time.Instant;
import java.time.LocalDate;

public record NseSyncAttempt(LocalDate date, Instant attemptedAt, String outcome) {}
