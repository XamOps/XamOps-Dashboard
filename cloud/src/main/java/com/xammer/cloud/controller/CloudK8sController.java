package com.xammer.cloud.controller;

import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.service.EksService; // Use the new EKS service
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/k8s")
public class CloudK8sController {

    private static final Logger logger = LoggerFactory.getLogger(CloudK8sController.class);

    // Dependency is now on the new, focused EksService
    private final EksService eksService;

    // The constructor now correctly injects EksService
    public CloudK8sController(EksService eksService) {
        this.eksService = eksService;
    }

    @GetMapping("/clusters")
    public CompletableFuture<ResponseEntity<List<K8sClusterInfo>>> getEksClusters(@RequestParam String accountId) {
        return eksService.getEksClusterInfo(accountId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching EKS clusters for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/nodes")
    public CompletableFuture<ResponseEntity<List<K8sNodeInfo>>> getNodes(@RequestParam String accountId, @RequestParam String clusterName) {
        return eksService.getK8sNodes(accountId, clusterName)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching nodes for cluster {} in account {}", clusterName, accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/namespaces")
    public CompletableFuture<ResponseEntity<List<String>>> getNamespaces(@RequestParam String accountId, @RequestParam String clusterName) {
        return eksService.getK8sNamespaces(accountId, clusterName)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching namespaces for cluster {} in account {}", clusterName, accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/deployments")
    public CompletableFuture<ResponseEntity<List<K8sDeploymentInfo>>> getDeployments(
            @RequestParam String accountId,
            @RequestParam String clusterName,
            @RequestParam String namespace) {
        return eksService.getK8sDeployments(accountId, clusterName, namespace)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching deployments in namespace {} for cluster {}", namespace, clusterName, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/pods")
    public CompletableFuture<ResponseEntity<List<K8sPodInfo>>> getPods(
            @RequestParam String accountId,
            @RequestParam String clusterName,
            @RequestParam String namespace) {
        return eksService.getK8sPods(accountId, clusterName, namespace)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching pods in namespace {} for cluster {}", namespace, clusterName, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }
}