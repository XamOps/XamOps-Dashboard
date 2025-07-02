package com.xammer.cloud.controller;

import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam; // Import this
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.ec2.model.Vpc; // Import this

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cloudmap")
public class CloudmapController {

    private final AwsDataService awsDataService;

    public CloudmapController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    // ADD THIS NEW ENDPOINT
    @GetMapping("/vpcs")
    public ResponseEntity<List<Map<String, String>>> getVpcs() throws ExecutionException, InterruptedException {
        List<Vpc> vpcs = awsDataService.getVpcList().get();
        // Send a simplified list of VPCs to the frontend
        List<Map<String, String>> vpcList = vpcs.stream()
            .map(vpc -> Map.of(
                "id", vpc.vpcId(),
                "name", awsDataService.getTagName(vpc.tags(), vpc.vpcId())
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(vpcList);
    }

    // UPDATE THIS ENDPOINT
    @GetMapping("/graph-data")
    public ResponseEntity<List<Map<String, Object>>> getGraphData(@RequestParam String vpcId) throws ExecutionException, InterruptedException {
        List<Map<String, Object>> graphData = awsDataService.getGraphData(vpcId).get();
        return ResponseEntity.ok(graphData);
    }

    @ExceptionHandler({ExecutionException.class, InterruptedException.class, RuntimeException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to generate graph data.", "message", e.getMessage()));
    }
}