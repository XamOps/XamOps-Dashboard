package com.xammer.cloud.controller;

import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;

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
    public ResponseEntity<Map<String, List<Datapoint>>> getEc2Metrics(@PathVariable String instanceId) {
        Map<String, List<Datapoint>> metrics = awsDataService.getEc2InstanceMetrics(instanceId);
        return ResponseEntity.ok(metrics);
    }
}
