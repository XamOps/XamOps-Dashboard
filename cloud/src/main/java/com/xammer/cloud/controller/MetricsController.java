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
}
