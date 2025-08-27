package com.xammer.cloud.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import java.time.LocalDateTime;

@Entity
public class CachedData {

    @Id
    private String cacheKey;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String jsonData;

    private LocalDateTime lastUpdated;

    // No-argument constructor for JPA
    public CachedData() {
    }

    // Constructor that was missing
    public CachedData(String cacheKey, String jsonData, LocalDateTime lastUpdated) {
        this.cacheKey = cacheKey;
        this.jsonData = jsonData;
        this.lastUpdated = lastUpdated;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public void setCacheKey(String cacheKey) {
        this.cacheKey = cacheKey;
    }

    public String getJsonData() {
        return jsonData;
    }

    public void setJsonData(String jsonData) {
        this.jsonData = jsonData;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}