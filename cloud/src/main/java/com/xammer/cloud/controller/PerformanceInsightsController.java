package com.xammer.cloud.controller;

import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.WhatIfScenarioDto; // ADDED
import com.xammer.cloud.service.PerformanceInsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException; // ADDED

@RestController
@RequestMapping("/api/metrics/insights")
public class PerformanceInsightsController {

    private final PerformanceInsightsService performanceInsightsService;

    public PerformanceInsightsController(PerformanceInsightsService performanceInsightsService) {
        this.performanceInsightsService = performanceInsightsService;
    }

    @GetMapping
    public ResponseEntity<List<PerformanceInsightDto>> getInsights(
            @RequestParam String accountId,
            @RequestParam(required = false) String severity) {
        List<PerformanceInsightDto> insights = performanceInsightsService.getInsights(accountId, severity);
        return ResponseEntity.ok(insights);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getInsightsSummary(@RequestParam String accountId) {
        Map<String, Object> summary = performanceInsightsService.getInsightsSummary(accountId);
        return ResponseEntity.ok(summary);
    }

    // NEW ENDPOINT
    @GetMapping("/what-if")
    public ResponseEntity<WhatIfScenarioDto> getWhatIfScenario(
            @RequestParam String accountId,
            @RequestParam String resourceId,
            @RequestParam String targetInstanceType) throws ExecutionException, InterruptedException {
        WhatIfScenarioDto scenario = performanceInsightsService.getWhatIfScenario(accountId, resourceId, targetInstanceType).get();
        return ResponseEntity.ok(scenario);
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