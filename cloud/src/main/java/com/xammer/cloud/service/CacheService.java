package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    @Autowired
    private CacheManager cacheManager;

    // A list of all caches to be cleared periodically or on demand
    private static final String[] CACHE_NAMES = {
            "dashboardData", "cloudlistResources", "groupedCloudlistResources", "wastedResources",
            "regionStatus", "inventory", "cloudwatchStatus", "securityInsights", "ec2Recs",
            "costAnomalies", "ebsRecs", "lambdaRecs", "reservationAnalysis", "reservationPurchaseRecs",
            "billingSummary", "iamResources", "costHistory", "allRecommendations", "securityFindings",
            "serviceQuotas", "reservationPageData", "reservationInventory", "historicalReservationData",
            "reservationModificationRecs", "eksClusters", "k8sNodes", "k8sNamespaces", "k8sDeployments",
            "k8sPods", "finopsReport", "costByTag", "budgets", "taggingCompliance", "costByRegion"
    };

    /**
     * Evicts all known caches used in the dashboard and FinOps sections.
     */
    public void evictAllCaches() {
        logger.info("Starting eviction of all application caches...");
        for (String cacheName : CACHE_NAMES) {
            try {
                Objects.requireNonNull(cacheManager.getCache(cacheName)).clear();
                logger.debug("Successfully evicted cache: {}", cacheName);
            } catch (NullPointerException e) {
                logger.warn("Cache '{}' not found, skipping eviction.", cacheName);
            } catch (Exception e) {
                logger.error("Error while evicting cache: {}", cacheName, e);
            }
        }
        logger.info("Completed eviction of all application caches.");
    }

    /**
     * Evicts only the caches related to the FinOps report.
     * @param accountId The account ID to clear the cache for.
     */
    public void evictFinOpsReportCache(String accountId) {
        logger.info("Evicting FinOps-related caches for account ID: {}", accountId);
        try {
            Objects.requireNonNull(cacheManager.getCache("finopsReport")).evict(accountId);
            Objects.requireNonNull(cacheManager.getCache("budgets")).evict(accountId);
            logger.debug("Successfully evicted FinOps caches for account: {}", accountId);
        } catch (NullPointerException e) {
            logger.warn("FinOps cache not found for account '{}', skipping eviction.", accountId);
        } catch (Exception e) {
            logger.error("Error while evicting FinOps cache for account: {}", accountId, e);
        }
    }

    /**
     * Scheduled task to automatically clear all caches every 2 hours.
     * The fixed rate is specified in milliseconds (2 hours = 7,200,000 ms).
     */
    // @Scheduled(fixedRate = 7200000)
    public void scheduledCacheEviction() {
        logger.info("Executing scheduled cache eviction...");
        evictAllCaches();
    }
}