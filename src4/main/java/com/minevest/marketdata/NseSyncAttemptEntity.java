package com.minevest.marketdata;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "nse_sync_attempts")
public class NseSyncAttemptEntity {
    @Id
    @Column(name = "sync_date")
    private LocalDate date;

    @Column(name = "attempted_at")
    private Instant attemptedAt;

    @Column(nullable = false, length = 30)
    private String outcome;

    protected NseSyncAttemptEntity() {}

    public NseSyncAttemptEntity(NseSyncAttempt attempt) {
        this.date = attempt.date();
        this.attemptedAt = attempt.attemptedAt();
        this.outcome = attempt.outcome();
    }

    public NseSyncAttempt toAttempt() {
        return new NseSyncAttempt(date, attemptedAt, outcome);
    }

    public LocalDate getDate() { return date; }
}
