package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

// CHANGED: This is now a RestController serving JSON data.
@RestController
@RequestMapping("/api") // All endpoints are now prefixed with /api
public class DashboardController {

    private final AwsDataService awsDataService;

    public DashboardController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    // This endpoint now fetches the main dashboard data.
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardData> getDashboardData() throws ExecutionException, InterruptedException {
        DashboardData data = awsDataService.getDashboardData();
        return ResponseEntity.ok(data);
    }

    // This endpoint now specifically fetches wasted resources data.
    @GetMapping("/waste")
    public ResponseEntity<List<DashboardData.WastedResource>> getWastedResources() throws ExecutionException, InterruptedException {
        // FIXED: Added .get() to resolve the CompletableFuture and get the list.
        List<DashboardData.WastedResource> wastedResources = awsDataService.getWastedResources().get();
        return ResponseEntity.ok(wastedResources);
    }

    // A simple exception handler to return a clean error response.
    @ExceptionHandler({ExecutionException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}
