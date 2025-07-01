package com.xammer.cloud.controller;

import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/cloudmap")
public class CloudmapController {

    private final AwsDataService awsDataService;

    public CloudmapController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/graph-data")
    public ResponseEntity<List<Map<String, Object>>> getGraphData() throws ExecutionException, InterruptedException {
        List<Map<String, Object>> graphData = awsDataService.getGraphData().get();
        return ResponseEntity.ok(graphData);
    }

    // ADD THIS EXCEPTION HANDLER
    @ExceptionHandler({ExecutionException.class, InterruptedException.class, RuntimeException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to generate graph data.", "message", e.getMessage()));
    }
}