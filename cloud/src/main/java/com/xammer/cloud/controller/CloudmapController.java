package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.service.CloudMapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/cloudmap")
public class CloudmapController {

    private static final Logger logger = LoggerFactory.getLogger(CloudmapController.class);

    private final CloudMapService cloudMapService;

    public CloudmapController(CloudMapService cloudMapService) {
        this.cloudMapService = cloudMapService;
    }

    @GetMapping("/vpcs")
    public CompletableFuture<ResponseEntity<List<ResourceDto>>> getVpcs(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return cloudMapService.getVpcListForCloudmap(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Failed to get VPC list for cloudmap for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }

    @GetMapping("/graph")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getGraphData(
            @RequestParam String accountId,
            @RequestParam(required = false) String vpcId,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return cloudMapService.getGraphData(accountId, vpcId, region, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Failed to get graph data for account {} and VPC {}", accountId, vpcId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }
}