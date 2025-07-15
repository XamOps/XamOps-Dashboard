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
@RequestMapping("/api/cloudlist")
public class CloudlistController {

    private final AwsDataService awsDataService;

    public CloudlistController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/resources")
    public ResponseEntity<List<DashboardData.ServiceGroupDto>> getAllResources(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        // FIXED: Pass the accountId to the getAllResourcesGrouped method
        List<DashboardData.ServiceGroupDto> resources = awsDataService.getAllResourcesGrouped(accountId).get();
        return ResponseEntity.ok(resources);
    }
}
