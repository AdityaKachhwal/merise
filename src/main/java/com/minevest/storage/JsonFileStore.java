package com.minevest.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.minevest.marketdata.DailyPrice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonFileStore {
    private final Path directory;
    private final ObjectMapper mapper;

    public JsonFileStore(@Value("${minevest.storage.directory:./data}") String directory) throws IOException {
        this.directory = Path.of(directory);
        Files.createDirectories(this.directory);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public synchronized List<DailyPrice> readPrices() throws IOException {
        Path file = directory.resolve("prices.json");
        if (!Files.exists(file)) return new ArrayList<>();
        return mapper.readValue(file.toFile(), new TypeReference<>() {});
    }

    public synchronized int savePrices(List<DailyPrice> incoming) throws IOException {
        Map<String, DailyPrice> unique = new LinkedHashMap<>();
        for (DailyPrice p : readPrices()) unique.put(key(p), p);
        int before = unique.size();
        for (DailyPrice p : incoming) unique.put(key(p), p);
        mapper.writeValue(directory.resolve("prices.json").toFile(), unique.values());
        return unique.size() - before;
    }

    public synchronized void writeJson(String fileName, Object value) throws IOException {
        mapper.writeValue(directory.resolve(fileName).toFile(), value);
    }

    public synchronized <T> List<T> readJsonList(String fileName, TypeReference<List<T>> type) throws IOException {
        Path file = directory.resolve(fileName);
        if (!Files.exists(file)) return new ArrayList<>();
        return mapper.readValue(file.toFile(), type);
    }

    private String key(DailyPrice p) {
        return p.tradingDate() + "|" + p.symbol() + "|" + p.series() + "|" + p.isin();
    }
}
