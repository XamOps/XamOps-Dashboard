package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /**
     * UPDATED: This endpoint now returns a list of resource groups
     * instead of a flat list of resources.
     */
    @GetMapping("/resources")
    public ResponseEntity<List<DashboardData.ServiceGroupDto>> getAllResources() throws ExecutionException, InterruptedException {
        // Call the new service method that returns grouped data
        // Note: The type is now DashboardData.ServiceGroupDto
        List<DashboardData.ServiceGroupDto> resources = awsDataService.getAllResourcesGrouped().get();
        return ResponseEntity.ok(resources);
    }
}
