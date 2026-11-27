
package com.minevest.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class NseMcpLiveQuoteService {
    private static final String ENDPOINT = "https://mcp.nseindia.in/cmmkt/mcp";
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final String PREFIXES = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private final ObjectMapper mapper;
    private final HttpClient http;
    private String sessionId;
    private long requestId;

    public NseMcpLiveQuoteService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
    }

    public synchronized Map<String, LiveMarketQuote> fetchAllEquityQuotes() throws Exception {
        this.initializeIfRequired();
        Map<String, LiveMarketQuote> quotes = new LinkedHashMap();

        for(int i = 0; i < "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".length(); ++i) {
            String prefix = String.valueOf("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".charAt(i));
            JsonNode payload = this.callTool("cm_get_equity_stocks", this.mapper.createObjectNode().put("limit", 500).put("symbolFilter", prefix));
            JsonNode stocks = this.findArray(payload, "stocks");
            if (stocks == null) {
                throw new IllegalStateException("NSE live quote response did not contain a stocks array for prefix " + prefix);
            }

            for(JsonNode row : stocks) {
                LiveMarketQuote quote = this.toQuote(row, payload);
                if (quote != null && "EQ".equalsIgnoreCase(quote.series())) {
                    quotes.put(quote.symbol().toUpperCase(Locale.ROOT), quote);
                }
            }

            if (i < "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".length() - 1) {
                Thread.sleep(250L);
            }
        }

        if (quotes.isEmpty()) {
            throw new IllegalStateException("NSE returned no live equity quotes. No refreshed analysis was generated.");
        } else {
            return quotes;
        }
    }

    private void initializeIfRequired() throws Exception {
        if (this.sessionId == null) {
            ObjectNode params = this.mapper.createObjectNode();
            params.put("protocolVersion", "2025-03-26");
            params.set("capabilities", this.mapper.createObjectNode());
            params.set("clientInfo", this.mapper.createObjectNode().put("name", "minevest").put("version", "1.0.0"));
            JsonNode response = this.post("initialize", params, true);
            if (response.has("error")) {
                throw new IllegalStateException("NSE MCP initialization failed: " + String.valueOf(response.path("error")));
            } else {
                this.postNotification("notifications/initialized");
            }
        }
    }

    private JsonNode callTool(String name, ObjectNode arguments) throws Exception {
        ObjectNode params = this.mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);

        JsonNode response;
        try {
            response = this.post("tools/call", params, false);
        } catch (IOException var12) {
            if (this.sessionId == null || !var12.getMessage().contains("HTTP 404") && !var12.getMessage().contains("HTTP 410")) {
                throw var12;
            }

            this.sessionId = null;
            this.initializeIfRequired();
            response = this.post("tools/call", params, false);
        }

        if (response.has("error")) {
            throw new IllegalStateException("NSE MCP tool call failed: " + String.valueOf(response.path("error")));
        } else {
            JsonNode result = response.path("result");
            if (result.path("isError").asBoolean(false)) {
                throw new IllegalStateException("NSE returned an error for " + name + ": " + String.valueOf(result.path("content")));
            } else {
                JsonNode structured = result.path("structuredContent");
                if (!structured.isObject() && !structured.isArray()) {
                    JsonNode content = result.path("content");
                    if (content.isArray()) {
                        for(JsonNode item : content) {
                            String text = item.path("text").asText((String)null);
                            if (text != null && !text.isBlank()) {
                                JsonNode parsed = this.mapper.readTree(text);
                                if (parsed.has("error")) {
                                    throw new IllegalStateException("NSE live quote error: " + parsed.path("error").asText());
                                }

                                return parsed;
                            }
                        }
                    }

                    throw new IllegalStateException("NSE returned an empty response for " + name);
                } else {
                    return structured;
                }
            }
        }
    }

    private JsonNode post(String method, JsonNode params, boolean initializing) throws Exception {
        ObjectNode request = this.mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        long id = ++this.requestId;
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://mcp.nseindia.in/cmmkt/mcp")).timeout(Duration.ofSeconds(20L)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("User-Agent", "MineVest/1.0 (personal educational project)").POST(BodyPublishers.ofString(this.mapper.writeValueAsString(request)));
        if (!initializing) {
            builder.header("MCP-Protocol-Version", "2025-03-26");
            if (this.sessionId != null) {
                builder.header("Mcp-Session-Id", this.sessionId);
            }
        }

        HttpResponse<String> response = this.http.send(builder.build(), BodyHandlers.ofString());
        if (initializing) {
            this.sessionId = (String)response.headers().firstValue("Mcp-Session-Id").orElse(null);
        }

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return this.extractJsonRpc((String)response.body(), id);
        } else {
            int var10002 = response.statusCode();
            throw new IOException("NSE MCP returned HTTP " + var10002 + ": " + (String)response.body());
        }
    }

    private void postNotification(String method) throws Exception {
        ObjectNode request = this.mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://mcp.nseindia.in/cmmkt/mcp")).timeout(Duration.ofSeconds(10L)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-03-26").POST(BodyPublishers.ofString(this.mapper.writeValueAsString(request)));
        if (this.sessionId != null) {
            builder.header("Mcp-Session-Id", this.sessionId);
        }

        HttpResponse<String> response = this.http.send(builder.build(), BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("NSE MCP initialization notification failed with HTTP " + response.statusCode());
        }
    }

    private JsonNode extractJsonRpc(String body, long expectedId) throws IOException {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode direct = this.mapper.readTree(body);
                if (direct != null && direct.has("id")) {
                    return direct;
                }
            } catch (Exception var11) {
            }

            for(String line : body.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("data:")) {
                    String data = trimmed.substring(5).trim();
                    if (!data.isBlank() && !"[DONE]".equals(data)) {
                        JsonNode node = this.mapper.readTree(data);
                        if (!node.has("id") || node.path("id").asLong(-1L) == expectedId) {
                            return node;
                        }
                    }
                }
            }

            throw new IOException("Could not find the JSON-RPC response in NSE's MCP response.");
        } else {
            throw new IOException("NSE MCP returned an empty HTTP response.");
        }
    }

    private LiveMarketQuote toQuote(JsonNode row, JsonNode parent) {
        String symbol = this.text(row, "symbol", "ticker");
        String series = this.text(row, "series");
        BigDecimal ltp = this.decimal(row, "lastTradedPrice", "ltp", "lastPrice", "close");
        if (symbol != null && ltp != null && ltp.signum() > 0) {
            String latestTimestamp = this.text(row, "latestTimestamp", "timestamp");
            String updatedAtText = this.text(row, "updatedAt");
            if (updatedAtText == null) {
                updatedAtText = this.text(parent, "updatedAt");
            }

            Instant updatedAt = this.parseInstant(updatedAtText);
            LocalDate marketDate = this.parseMarketDate(latestTimestamp, updatedAtText);
            return marketDate == null ? null : new LiveMarketQuote(symbol.trim().toUpperCase(Locale.ROOT), series == null ? "" : series, this.decimal(row, "openPrice", "open"), this.decimal(row, "highPrice", "high"), this.decimal(row, "lowPrice", "low"), this.decimal(row, "preClosePrice", "prevClose", "previousClose"), ltp, this.decimal(row, "change", "netChange"), this.decimal(row, "perChange", "percentChange", "changePercent"), this.longValue(row, "volume", "totalTradedVolume"), marketDate, updatedAt, latestTimestamp == null ? updatedAtText : latestTimestamp);
        } else {
            return null;
        }
    }

    private JsonNode findArray(JsonNode node, String field) {
        if (node != null && !node.isMissingNode() && !node.isNull()) {
            JsonNode candidate = node.path(field);
            if (candidate.isArray()) {
                return candidate;
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

                while(fields.hasNext()) {
                    JsonNode found = this.findArray((JsonNode)((Map.Entry)fields.next()).getValue(), field);
                    if (found != null) {
                        return found;
                    }
                }

                return null;
            }
        } else {
            return null;
        }
    }

    private String text(JsonNode node, String... fields) {
        for(String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }

        return null;
    }

    private BigDecimal decimal(JsonNode node, String... fields) {
        for(String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.decimalValue();
            }

            if (value.isTextual()) {
                try {
                    return new BigDecimal(value.asText().replace(",", "").trim());
                } catch (Exception var9) {
                }
            }
        }

        return null;
    }

    private long longValue(JsonNode node, String... fields) {
        BigDecimal value = this.decimal(node, fields);
        return value == null ? 0L : value.longValue();
    }

    private Instant parseInstant(String value) {
        if (value == null) {
            return null;
        } else {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException var5) {
                try {
                    return OffsetDateTime.parse(value).toInstant();
                } catch (DateTimeParseException var4) {
                    try {
                        return LocalDateTime.parse(value).atZone(INDIA).toInstant();
                    } catch (DateTimeParseException var3) {
                        return null;
                    }
                }
            }
        }
    }

    private LocalDate parseMarketDate(String latestTimestamp, String updatedAt) {
        if (latestTimestamp != null) {
            for(DateTimeFormatter formatter : List.of(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US), DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.US), DateTimeFormatter.ISO_LOCAL_DATE_TIME)) {
                try {
                    return LocalDateTime.parse(latestTimestamp, formatter).toLocalDate();
                } catch (DateTimeParseException var7) {
                }
            }

            try {
                return OffsetDateTime.parse(latestTimestamp).toLocalDate();
            } catch (DateTimeParseException var6) {
            }
        }

        Instant instant = this.parseInstant(updatedAt);
        return instant == null ? null : instant.atZone(INDIA).toLocalDate();
    }
}
