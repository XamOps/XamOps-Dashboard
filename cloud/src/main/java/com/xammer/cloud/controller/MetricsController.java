package com.xammer.cloud.controller;

import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final AwsDataService awsDataService;

    public MetricsController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/ec2/{instanceId}")
    public ResponseEntity<Map<String, List<MetricDto>>> getEc2Metrics(@RequestParam String accountId, @PathVariable String instanceId) {
        Map<String, List<MetricDto>> metrics = awsDataService.getEc2InstanceMetrics(accountId, instanceId);
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/rds/{instanceId}")
    public ResponseEntity<Map<String, List<MetricDto>>> getRdsMetrics(@RequestParam String accountId, @PathVariable String instanceId) {
        Map<String, List<MetricDto>> metrics = awsDataService.getRdsInstanceMetrics(accountId, instanceId);
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/s3/{bucketName}")
    public ResponseEntity<Map<String, List<MetricDto>>> getS3Metrics(
            @RequestParam String accountId,
            @PathVariable String bucketName,
            @RequestParam String region) {
        Map<String, List<MetricDto>> metrics = awsDataService.getS3BucketMetrics(accountId, bucketName, region);
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/lambda/{functionName}")
    public ResponseEntity<Map<String, List<MetricDto>>> getLambdaMetrics(
            @RequestParam String accountId,
            @PathVariable String functionName,
            @RequestParam String region) {
        Map<String, List<MetricDto>> metrics = awsDataService.getLambdaFunctionMetrics(accountId, functionName, region);
        return ResponseEntity.ok(metrics);
    }
}
