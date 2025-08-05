package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.OptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/rightsizing")
public class RightsizingController {

    private static final Logger logger = LoggerFactory.getLogger(RightsizingController.class);

    private final OptimizationService optimizationService;

    public RightsizingController(OptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<List<DashboardData.OptimizationRecommendation>>> getRecommendations(@RequestParam String accountId) {
        return optimizationService.getAllOptimizationRecommendations(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching optimization recommendations for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }
}