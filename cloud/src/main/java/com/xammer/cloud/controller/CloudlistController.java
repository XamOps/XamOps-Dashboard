package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDetailDto;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.ResourceDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/cloudlist")
public class CloudlistController {

    private static final Logger logger = LoggerFactory.getLogger(CloudlistController.class);

    private final CloudListService cloudListService;
    private final ResourceDetailService resourceDetailService;

    public CloudlistController(CloudListService cloudListService, ResourceDetailService resourceDetailService) {
        this.cloudListService = cloudListService;
        this.resourceDetailService = resourceDetailService;
    }

    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<List<DashboardData.ServiceGroupDto>>> getAllResources(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return cloudListService.getAllResourcesGrouped(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching grouped resources for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/resource/{service}/{resourceId}")
    public CompletableFuture<ResponseEntity<ResourceDetailDto>> getResourceDetails(
            @RequestParam String accountId,
            @PathVariable String service,
            @PathVariable String resourceId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String decodedService = service.replace("%20", " ");
        return resourceDetailService.getResourceDetails(accountId, decodedService, resourceId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Failed to get details for resource {}/{} in account {}", service, resourceId, accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }
}