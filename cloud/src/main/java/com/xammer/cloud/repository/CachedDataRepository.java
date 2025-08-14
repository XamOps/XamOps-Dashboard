// Create a new file: src/main/java/com/xammer/cloud/repository/CachedDataRepository.java

package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CachedData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CachedDataRepository extends JpaRepository<CachedData, String> {
}