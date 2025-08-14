package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DashboardLayout;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DashboardLayoutRepository;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.OptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardDataService dashboardDataService;
    // Removed unused OptimizationService field
    private final OptimizationService optimizationService;
    private final CloudListService cloudListService;
    private final AwsAccountService awsAccountService;
    private final CloudAccountRepository cloudAccountRepository;
    private final DashboardLayoutRepository dashboardLayoutRepository;

    public DashboardController(DashboardDataService dashboardDataService,
                               // Removed OptimizationService from constructor
                               OptimizationService optimizationService,
                               CloudListService cloudListService,
                               AwsAccountService awsAccountService,
                               CloudAccountRepository cloudAccountRepository,
                               DashboardLayoutRepository dashboardLayoutRepository) {
    this.dashboardDataService = dashboardDataService;
    this.optimizationService = optimizationService;
    this.cloudListService = cloudListService;
    this.awsAccountService = awsAccountService;
    this.cloudAccountRepository = cloudAccountRepository;
    this.dashboardLayoutRepository = dashboardLayoutRepository;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardData> getDashboardData(
            @RequestParam(required = false) boolean force,
            @RequestParam String accountId) throws ExecutionException, InterruptedException, java.io.IOException {

        if (force) {
            // Use the new dedicated service to clear caches
            awsAccountService.clearAllCaches();
        }

        DashboardData data = dashboardDataService.getDashboardData(accountId, force);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/waste")
    public CompletableFuture<ResponseEntity<List<DashboardData.WastedResource>>> getWastedResources(@RequestParam String accountId) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
    // Pass 'false' for forceRefresh to match the required method signature
    return cloudListService.getRegionStatusForAccount(account, false)
        .thenCompose(activeRegions -> optimizationService.getWastedResources(account, activeRegions, false))
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching wasted resources for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/dashboard/layout")
    public ResponseEntity<DashboardLayout> getDashboardLayout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return dashboardLayoutRepository.findById(username)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(new DashboardLayout(username, "[]"))); // Default to empty layout
    }

    @PostMapping("/dashboard/layout")
    public ResponseEntity<Void> saveDashboardLayout(@RequestBody String layoutConfig) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        DashboardLayout layout = new DashboardLayout(username, layoutConfig);
        dashboardLayoutRepository.save(layout);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler({ExecutionException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        logger.error("An asynchronous execution error occurred: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}