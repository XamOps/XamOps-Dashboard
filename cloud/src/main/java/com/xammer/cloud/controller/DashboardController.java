package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final AwsDataService awsDataService;

    public DashboardController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardData> getDashboardData(@RequestParam(required = false) boolean force) throws ExecutionException, InterruptedException {
        if (force) {
            awsDataService.clearAllCaches();
        }
        DashboardData data = awsDataService.getDashboardData();
        return ResponseEntity.ok(data);
    }

    @GetMapping("/waste")
    public ResponseEntity<List<DashboardData.WastedResource>> getWastedResources() throws ExecutionException, InterruptedException {
        List<DashboardData.WastedResource> wastedResources = awsDataService.getWastedResources().get();
        return ResponseEntity.ok(wastedResources);
    }

    @ExceptionHandler({ExecutionException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}