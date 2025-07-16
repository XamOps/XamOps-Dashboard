package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final AwsDataService awsDataService;
    private final CloudAccountRepository cloudAccountRepository;

    public DashboardController(AwsDataService awsDataService, CloudAccountRepository cloudAccountRepository) {
        this.awsDataService = awsDataService;
        this.cloudAccountRepository = cloudAccountRepository;
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
        
        // **FIXED**: Fetch active regions first, then pass them to the service method.
        List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();
        List<DashboardData.WastedResource> wastedResources = awsDataService.getWastedResources(account, activeRegions).get();
        
        return ResponseEntity.ok(wastedResources);
    }

    @ExceptionHandler({ExecutionException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}
