package com.xammer.cloud.controller;

import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.WhatIfScenarioDto;
import com.xammer.cloud.service.PerformanceInsightsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/metrics/insights")
public class PerformanceInsightsController {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceInsightsController.class);

    private final PerformanceInsightsService performanceInsightsService;

    public PerformanceInsightsController(PerformanceInsightsService performanceInsightsService) {
        this.performanceInsightsService = performanceInsightsService;
    }

    @GetMapping
    public ResponseEntity<List<PerformanceInsightDto>> getInsights(
            @RequestParam String accountId,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        List<PerformanceInsightDto> insights = performanceInsightsService.getInsights(accountId, severity, forceRefresh);
        return ResponseEntity.ok(insights);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getInsightsSummary(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        Map<String, Object> summary = performanceInsightsService.getInsightsSummary(accountId, forceRefresh);
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/what-if")
    public CompletableFuture<ResponseEntity<WhatIfScenarioDto>> getWhatIfScenario(
            @RequestParam String accountId,
            @RequestParam String resourceId,
            @RequestParam String targetInstanceType,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return performanceInsightsService.getWhatIfScenario(accountId, resourceId, targetInstanceType, forceRefresh)
                .thenApply(result -> ResponseEntity.ok(result))
                .exceptionally(ex -> {
                    logger.error("Error generating what-if scenario for resource {}", resourceId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }

    @PostMapping("/{insightId}/archive")
    public ResponseEntity<Void> archiveInsight(@PathVariable String insightId) {
        performanceInsightsService.archiveInsight(insightId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/bulk-archive")
    public ResponseEntity<Void> bulkArchiveInsights(@RequestBody List<String> insightIds) {
        performanceInsightsService.bulkArchiveInsights(insightIds);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/export")
    public void exportInsights(
            @RequestParam String accountId,
            @RequestParam(required = false) String severity,
            HttpServletResponse response) {
        performanceInsightsService.exportInsightsToExcel(accountId, severity, response);
    }
}