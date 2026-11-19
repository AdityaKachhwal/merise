
package com.minevest.fundamentals;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class MarketNewsFeedService {

    private static final String FEED_URL =
            "https://news.google.com/rss/search?q="
                    + "(NSE+OR+BSE+OR+Nifty+OR+Sensex)+India+stock+market"
                    + "&hl=en-IN&gl=IN&ceid=IN:en";

    private final StockNewsRepository repository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MarketNewsFeedService(StockNewsRepository repository) {
        this.repository = repository;
    }

    // Refresh the cached feed every 15 minutes.
    @Scheduled(
            fixedDelay = 900000,
            initialDelay = 10000
    )
    public void refreshMarketNews() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FEED_URL))
                    .header("User-Agent", "MineVest/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() != 200) {
                return;
            }

            try (InputStream stream = response.body()) {
                DocumentBuilderFactory factory =
                        DocumentBuilderFactory.newInstance();

                factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                );
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities",
                        false
                );
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities",
                        false
                );
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

                var document = factory.newDocumentBuilder().parse(stream);
                NodeList items = document.getElementsByTagName("item");

                for (int i = 0; i < Math.min(items.getLength(), 30); i++) {
                    Element item = (Element) items.item(i);

                    String headline = value(item, "title");
                    String url = value(item, "link");
                    String source = value(item, "source");
                    String published = value(item, "pubDate");

                    if (headline.isBlank() || url.isBlank()) {
                        continue;
                    }

                    // Do not insert the same article more than once.
                    if (repository.existsByUrl(url)) {
                        continue;
                    }

                    Instant publishedAt;
                    try {
                        publishedAt = ZonedDateTime.parse(
                                published,
                                DateTimeFormatter.RFC_1123_DATE_TIME
                        ).toInstant();
                    } catch (Exception ignored) {
                        publishedAt = Instant.now();
                    }

                    StockNewsEntity news = new StockNewsEntity();
                    news.setSymbol("MARKET");
                    news.setHeadline(headline);
                    news.setSource(source.isBlank() ? "Google News" : source);
                    news.setUrl(url);
                    news.setPublishedAt(publishedAt);
                    news.setSentimentTag("NEUTRAL");

                    repository.save(news);
                }
            }
        } catch (Exception exception) {
            // Keep the last successfully cached feed if the provider is unavailable.
            System.err.println(
                    "Market news refresh failed: " + exception.getMessage()
            );
        }
    }

    private String value(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }
}
