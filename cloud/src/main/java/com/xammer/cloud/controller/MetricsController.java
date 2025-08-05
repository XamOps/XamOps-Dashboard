package com.xammer.cloud.controller;

import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.service.MetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private static final Logger logger = LoggerFactory.getLogger(MetricsController.class);

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/ec2/{instanceId}")
    public CompletableFuture<ResponseEntity<Map<String, List<MetricDto>>>> getEc2Metrics(
            @RequestParam String accountId,
            @PathVariable String instanceId) {
        return metricsService.getEc2InstanceMetrics(accountId, instanceId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching EC2 metrics for instance {} in account {}", instanceId, accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyMap());
                });
    }

    @GetMapping("/rds/{instanceId}")
    public CompletableFuture<ResponseEntity<Map<String, List<MetricDto>>>> getRdsMetrics(
            @RequestParam String accountId,
            @PathVariable String instanceId) {
        return metricsService.getRdsInstanceMetrics(accountId, instanceId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching RDS metrics for instance {} in account {}", instanceId, accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyMap());
                });
    }

    @GetMapping("/s3/{bucketName}")
    public CompletableFuture<ResponseEntity<Map<String, List<MetricDto>>>> getS3Metrics(
            @RequestParam String accountId,
            @PathVariable String bucketName,
            @RequestParam String region) {
        return metricsService.getS3BucketMetrics(accountId, bucketName, region)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching S3 metrics for bucket {} in account {}", bucketName, accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyMap());
                });
    }

    @GetMapping("/lambda/{functionName}")
    public CompletableFuture<ResponseEntity<Map<String, List<MetricDto>>>> getLambdaMetrics(
            @RequestParam String accountId,
            @PathVariable String functionName,
            @RequestParam String region) {
        return metricsService.getLambdaFunctionMetrics(accountId, functionName, region)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching Lambda metrics for function {} in account {}", functionName, accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyMap());
                });
    }
}