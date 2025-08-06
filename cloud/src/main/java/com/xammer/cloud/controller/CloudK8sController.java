package com.xammer.cloud.controller;

import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.service.EksClusterUsageService;
import com.xammer.cloud.service.EksService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/k8s")
public class CloudK8sController {

    private final EksService eksService;
    private final EksClusterUsageService usageService;

    public CloudK8sController(EksService eksService, EksClusterUsageService usageService) {
        this.eksService = eksService;
        this.usageService = usageService;
    }

    // This endpoint now fetches usage data from CloudWatch via the refactored service
    @PostMapping("/clusters/usage")
    public CompletableFuture<ResponseEntity<ClusterUsageDto>> getClusterUsage(
        @RequestParam String accountId,
        @RequestParam String clusterName,
        @RequestParam String region) {
            
        return usageService.getClusterUsage(accountId, clusterName, region)
            .thenApply(ResponseEntity::ok);
    }
    
    // This endpoint remains to list available EKS clusters
    @GetMapping("/clusters")
    public CompletableFuture<ResponseEntity<List<K8sClusterInfo>>> getClusters(@RequestParam String accountId) {
        return eksService.getEksClusterInfo(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    // This endpoint remains to show basic node information
    @GetMapping("/clusters/{clusterName}/nodes")
    public CompletableFuture<ResponseEntity<List<K8sNodeInfo>>> getNodes(@RequestParam String accountId, @PathVariable String clusterName) {
        return eksService.getK8sNodes(accountId, clusterName)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }
}