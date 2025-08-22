package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
public class CachedData {

    @Id
    private String cacheKey;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String jsonData;

    private Instant lastUpdated;

    // With the @Data annotation, you no longer need to manually write
    // getCacheKey(), setCacheKey(), getJsonData(), setJsonData(), etc.
    // Lombok handles it for you.
}