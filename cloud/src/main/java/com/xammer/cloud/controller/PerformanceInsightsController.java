package com.xammer.cloud.controller;

import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.service.PerformanceInsightsService;

import ch.qos.logback.classic.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

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

    @GetMapping("/alb-performance")
    public ResponseEntity<Map<String, Object>> getALBPerformanceMetrics(
            @RequestParam String accountId,
            @RequestParam(required = false) String region) {
        
        try {
            Map<String, Object> performanceData = performanceInsightsService.getALBPerformanceMetrics(accountId, region);
            return ResponseEntity.ok(performanceData);
        } catch (Exception e) {
            Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(PerformanceInsightsController.class);
            logger.error("Failed to fetch ALB performance metrics for account: {} in region: {}", accountId, region, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to fetch ALB performance metrics"));
        }
    }
    
}
