package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudGuardService;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.FinOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/cloudguard")
public class AlertsApiController {

    private static final Logger logger = LoggerFactory.getLogger(AlertsApiController.class);

    private final CloudGuardService cloudGuardService;
    private final CloudListService cloudListService;
    private final FinOpsService finOpsService;
    private final CloudAccountRepository cloudAccountRepository;

    public AlertsApiController(CloudGuardService cloudGuardService, CloudListService cloudListService, FinOpsService finOpsService, CloudAccountRepository cloudAccountRepository) {
        this.cloudGuardService = cloudGuardService;
        this.cloudListService = cloudListService;
        this.finOpsService = finOpsService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping("/alerts")
    public CompletableFuture<ResponseEntity<List<AlertDto>>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) { 
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        return cloudListService.getRegionStatusForAccount(account, forceRefresh) 
            .thenCompose(activeRegions -> {
                CompletableFuture<List<AlertDto>> quotaAlertsFuture = cloudGuardService.getServiceQuotaInfo(account, activeRegions, forceRefresh)
                    .thenApply(quotas -> quotas.stream()
                        .map(q -> new AlertDto(
                                q.getQuotaName(),
                                q.getServiceName(),
                                q.getQuotaName(),
                                "Service Quota Usage",
                                q.getStatus(),
                                q.getUsage(),
                                q.getLimit(),
                                "QUOTA"
                        ))
                        .collect(Collectors.toList()))
                    .exceptionally(ex -> {
                        logger.error("Failed to fetch quota alerts for account {}", accountId, ex);
                        return Collections.emptyList();
                    });

                CompletableFuture<List<AlertDto>> anomalyAlertsFuture = finOpsService.getCostAnomalies(account, forceRefresh)
                    .thenApply(anomalies -> anomalies.stream()
                        .map(a -> new AlertDto(
                                a.getAnomalyId(),
                                a.getService(),
                                "Cost Anomaly Detected",
                                "Unexpected spend of $" + String.format("%.2f", a.getUnexpectedSpend()),
                                "CRITICAL",
                                a.getUnexpectedSpend(),
                                0,
                                "ANOMALY"
                        ))
                        .collect(Collectors.toList()))
                    .exceptionally(ex -> {
                        logger.error("Failed to fetch cost anomaly alerts for account {}", accountId, ex);
                        return Collections.emptyList();
                    });

                return CompletableFuture.allOf(quotaAlertsFuture, anomalyAlertsFuture)
                    .thenApply(v -> {
                        List<AlertDto> quotaAlerts = quotaAlertsFuture.join();
                        List<AlertDto> anomalyAlerts = anomalyAlertsFuture.join();
                        List<AlertDto> combinedAlerts = Stream.concat(quotaAlerts.stream(), anomalyAlerts.stream())
                                .collect(Collectors.toList());
                        return ResponseEntity.ok(combinedAlerts);
                    });
            })
            .exceptionally(ex -> {
                logger.error("Error fetching region status or combining alerts for account {}", accountId, ex);
                return ResponseEntity.status(500).body(Collections.emptyList());
            });
    }
}