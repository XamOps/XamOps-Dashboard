package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DashboardLayout;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DashboardLayoutRepository;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final AwsDataService awsDataService;
    private final CloudAccountRepository cloudAccountRepository;
    private final DashboardLayoutRepository dashboardLayoutRepository;

    public DashboardController(AwsDataService awsDataService, CloudAccountRepository cloudAccountRepository, DashboardLayoutRepository dashboardLayoutRepository) {
        this.awsDataService = awsDataService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardLayoutRepository = dashboardLayoutRepository;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardData> getDashboardData(
            @RequestParam(required = false) boolean force,
            @RequestParam String accountId) throws ExecutionException, InterruptedException {
        
        if (force) {
            awsDataService.clearAllCaches();
        }

        DashboardData data = awsDataService.getDashboardData(accountId);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/waste")
    public ResponseEntity<List<DashboardData.WastedResource>> getWastedResources(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        
        List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();
        List<DashboardData.WastedResource> wastedResources = awsDataService.getWastedResources(account, activeRegions).get();
        
        return ResponseEntity.ok(wastedResources);
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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}
