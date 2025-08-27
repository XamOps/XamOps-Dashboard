package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.repository.CachedDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class DatabaseCacheService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseCacheService.class);

    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    public DatabaseCacheService(CachedDataRepository cachedDataRepository, ObjectMapper objectMapper) {
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public <T> Optional<T> get(String key, Class<T> clazz) {
        logger.info("--- LOADING FROM DATABASE CACHE: {} ---", key);
        return cachedDataRepository.findById(key)
                .map(cachedData -> {
                    try {
                        return objectMapper.readValue(cachedData.getJsonData(), clazz);
                    } catch (JsonProcessingException e) {
                        logger.error("Error deserializing cached data for key {}: {}", key, e.getMessage());
                        return null;
                    }
                });
    }
    
    @Transactional(readOnly = true)
    public <T> Optional<T> get(String key, TypeReference<T> typeReference) {
        logger.info("--- LOADING FROM DATABASE CACHE (TypeReference): {} ---", key);
        return cachedDataRepository.findById(key)
                .map(cachedData -> {
                    try {
                        return objectMapper.readValue(cachedData.getJsonData(), typeReference);
                    } catch (JsonProcessingException e) {
                        logger.error("Error deserializing cached data for key {}: {}", key, e.getMessage());
                        return null;
                    }
                });
    }

    @Transactional
    public <T> void put(String key, T value) {
        try {
            String jsonData = objectMapper.writeValueAsString(value);
            CachedData cachedData = new CachedData(key, jsonData, LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("--- SAVED TO DATABASE CACHE: {} ---", key);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing data for caching for key {}: {}", key, e.getMessage());
        }
    }

    @Transactional
    public void evict(String key) {
        if (cachedDataRepository.existsById(key)) {
            cachedDataRepository.deleteById(key);
            logger.info("--- EVICTED FROM DATABASE CACHE: {} ---", key);
        } else {
            logger.warn("--- CACHE EVICTION SKIPPED: KEY '{}' NOT FOUND ---", key);
        }
    }
}