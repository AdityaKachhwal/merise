package com.minevest.fundamentals;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "stock_news")
public class StockNewsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String symbol;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String headline;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 100)
    private String source;

    @Column(columnDefinition = "TEXT")
    private String url;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "sentiment_tag", nullable = false, length = 20)
    private String sentimentTag = "NEUTRAL";

    public StockNewsEntity() {}

    public StockNews toRecord() {
        return new StockNews(id, symbol, headline, summary, source, url, publishedAt, sentimentTag);
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getSentimentTag() { return sentimentTag; }
    public void setSentimentTag(String sentimentTag) { this.sentimentTag = sentimentTag; }
}
