package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EksAutomationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/eks")
public class EksController {

    private final EksAutomationService eksAutomationService;
    private final CloudAccountRepository cloudAccountRepository;

    public EksController(EksAutomationService eksAutomationService, CloudAccountRepository cloudAccountRepository) {
        this.eksAutomationService = eksAutomationService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @PostMapping("/{clusterName}/install-opencost")
    public ResponseEntity<Map<String, String>> installOpenCost(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        boolean success = eksAutomationService.installOpenCost(account, clusterName, region);
        if (success) {
            return ResponseEntity.ok(Map.of("message", "OpenCost installation initiated successfully."));
        } else {
            return ResponseEntity.status(500).body(Map.of("message", "Failed to initiate OpenCost installation."));
        }
    }

    /**
     * NEW ENDPOINT: Triggers the Container Insights agent installation.
     */
    @PostMapping("/{clusterName}/enable-monitoring")
    public ResponseEntity<Map<String, String>> enableMonitoring(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        boolean success = eksAutomationService.enableContainerInsights(account, clusterName, region);
        if (success) {
            return ResponseEntity.ok(Map.of("message", "Container Insights enabled successfully. Metrics will appear shortly."));
        } else {
            return ResponseEntity.status(500).body(Map.of("message", "Failed to enable Container Insights. Check server logs."));
        }
    }
}