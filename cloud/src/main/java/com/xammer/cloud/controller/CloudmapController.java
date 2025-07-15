package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.service.AwsDataService;
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

    private final AwsDataService awsDataService;

    public CloudmapController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/vpcs")
    public CompletableFuture<List<ResourceDto>> getVpcs(@RequestParam String accountId) {
        // This method will now return a list of ResourceDto which includes the region.
        return awsDataService.getVpcListForCloudmap(accountId);
    }

    @GetMapping("/graph")
    public CompletableFuture<List<Map<String, Object>>> getGraphData(
            @RequestParam String accountId,
            @RequestParam(required = false) String vpcId,
            @RequestParam(required = false) String region) {
        // The region parameter is now used to fetch resources from the correct location.
        return awsDataService.getGraphData(accountId, vpcId, region);
    }
}
