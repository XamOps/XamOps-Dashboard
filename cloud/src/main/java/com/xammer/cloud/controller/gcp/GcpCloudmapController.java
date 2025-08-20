package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/cloudmap")
public class GcpCloudmapController {

    private final GcpDataService gcpDataService;

    public GcpCloudmapController(GcpDataService gcpDataService) {
        this.gcpDataService = gcpDataService;
    }

    @GetMapping("/vpcs")
    public CompletableFuture<ResponseEntity<List<GcpResourceDto>>> getVpcs(@RequestParam String accountId) {
        return gcpDataService.getVpcListForCloudmap(accountId)
                .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/graph")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getGraphData(
            @RequestParam String accountId,
            @RequestParam(required = false) String vpcId) {
        return gcpDataService.getVpcTopologyGraph(accountId, vpcId)
                .thenApply(ResponseEntity::ok);
    }
}