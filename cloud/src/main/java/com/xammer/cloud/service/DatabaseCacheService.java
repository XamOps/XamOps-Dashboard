package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.repository.CachedDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class DatabaseCacheService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseCacheService.class);

    @Autowired
    private CachedDataRepository cachedDataRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Retrieves a cached item for simple, non-generic types.
     */
    public <T> Optional<T> get(String key, Class<T> clazz) {
        return cachedDataRepository.findById(key).flatMap(cachedData -> {
            try {
                logger.info("--- LOADING FROM DATABASE CACHE (Class): {} ---", key);
                T value = objectMapper.readValue(cachedData.getJsonData(), clazz);
                return Optional.of(value);
            } catch (Exception e) {
                logger.error("Error deserializing cached data for key {}: {}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * NEW: Overloaded method to retrieve cached items for complex, generic types like List<T>.
     */
    public <T> Optional<T> get(String key, TypeReference<T> typeReference) {
        return cachedDataRepository.findById(key).flatMap(cachedData -> {
            try {
                logger.info("--- LOADING FROM DATABASE CACHE (TypeReference): {} ---", key);
                T value = objectMapper.readValue(cachedData.getJsonData(), typeReference);
                return Optional.of(value);
            } catch (Exception e) {
                logger.error("Error deserializing cached generic data for key {}: {}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    public <T> void put(String key, T value) {
        try {
            String jsonData = objectMapper.writeValueAsString(value);
            CachedData cachedData = new CachedData();
            cachedData.setCacheKey(key);
            cachedData.setJsonData(jsonData);
            cachedData.setLastUpdated(Instant.now());
            cachedDataRepository.save(cachedData);
            logger.info("--- SAVED TO DATABASE CACHE: {} ---", key);
        } catch (Exception e) {
            logger.error("Error serializing data for cache key {}: {}", key, e.getMessage());
        }
    }
    
    public void evict(String key) {
        cachedDataRepository.deleteById(key);
        logger.info("--- EVICTED FROM DATABASE CACHE: {} ---", key);
    }

    public void evictAll() {
        cachedDataRepository.deleteAll();
        logger.info("--- CLEARED ALL DATABASE CACHE ---");
    }
}