package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpMetricDto;
import com.xammer.cloud.service.gcp.GcpMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/gcp/performance")
public class GcpPerformanceController {

    private final GcpMetricsService gcpMetricsService;

    public GcpPerformanceController(GcpMetricsService gcpMetricsService) {
        this.gcpMetricsService = gcpMetricsService;
    }

    @GetMapping("/metrics")
    public ResponseEntity<List<GcpMetricDto>> getMetrics(
            @RequestParam String accountId,
            @RequestParam String resourceId) throws IOException {
        // Assuming resourceId is the instanceId for now
        List<GcpMetricDto> metrics = gcpMetricsService.getCpuUtilization(accountId, resourceId);
        return ResponseEntity.ok(metrics);
    }
}