package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/rightsizing")
public class RightsizingController {

    private final AwsDataService awsDataService;

    public RightsizingController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<DashboardData.OptimizationRecommendation>> getRecommendations(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        List<DashboardData.OptimizationRecommendation> recommendations = awsDataService.getAllOptimizationRecommendations(accountId).get();
        return ResponseEntity.ok(recommendations);
    }
}
