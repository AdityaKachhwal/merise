package com.minevest.fincrux;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "fincrux_financial_snapshots")
@IdClass(FincruxSnapshotEntity.SnapshotId.class)
public class FincruxSnapshotEntity {

    @Id
    @Column(length = 50, nullable = false)
    private String symbol;

    @Id
    @Column(name = "statement_type", length = 20, nullable = false)
    private String statementType;

    @Column(name = "company_name", length = 250)
    private String companyName;

    @Column(name = "provider_updated_at", length = 100)
    private String providerUpdatedAt;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    public FincruxSnapshotEntity() {}

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getStatementType() { return statementType; }
    public void setStatementType(String statementType) { this.statementType = statementType; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getProviderUpdatedAt() { return providerUpdatedAt; }
    public void setProviderUpdatedAt(String providerUpdatedAt) { this.providerUpdatedAt = providerUpdatedAt; }
    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public static class SnapshotId implements Serializable {
        private String symbol;
        private String statementType;

        public SnapshotId() {}
        public SnapshotId(String symbol, String statementType) {
            this.symbol = symbol;
            this.statementType = statementType;
        }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public String getStatementType() { return statementType; }
        public void setStatementType(String statementType) { this.statementType = statementType; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SnapshotId that)) return false;
            return Objects.equals(symbol, that.symbol)
                    && Objects.equals(statementType, that.statementType);
        }

        @Override
        public int hashCode() { return Objects.hash(symbol, statementType); }
    }
}
