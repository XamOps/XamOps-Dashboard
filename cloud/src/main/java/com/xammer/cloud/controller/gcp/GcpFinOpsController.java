package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import com.xammer.cloud.service.gcp.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/finops")
@Slf4j
public class GcpFinOpsController {

    private final GcpCostService gcpCostService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpDataService gcpDataService;
    private final GcpBudgetService gcpBudgetService;

    public GcpFinOpsController(GcpCostService gcpCostService, GcpOptimizationService gcpOptimizationService, GcpDataService gcpDataService, GcpBudgetService gcpBudgetService) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpDataService = gcpDataService;
        this.gcpBudgetService = gcpBudgetService;
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<GcpFinOpsReportDto>> getFinOpsReport(@RequestParam String accountId) {
        log.info("Starting GCP FinOps report for accountId: {}", accountId);
        GcpFinOpsReportDto finalReport = new GcpFinOpsReportDto();

        CompletableFuture<Void> billingFuture = gcpCostService.getBillingSummary(accountId).thenAccept(summary -> {
            finalReport.setBillingSummary(summary);
            double mtd = summary.stream().mapToDouble(dto -> dto.getAmount()).sum();
            finalReport.setMonthToDateSpend(mtd);
            finalReport.setForecastedSpend(gcpDataService.calculateForecastedSpend(mtd));
        });

        CompletableFuture<Void> historyFuture = gcpCostService.getHistoricalCosts(accountId).thenAccept(history -> {
            finalReport.setCostHistory(history);
            if (history.size() > 1) finalReport.setLastMonthSpend(history.get(history.size() - 2).getAmount());
        });
        
        String billingAccountId = "YOUR_BILLING_ACCOUNT_ID"; // <-- IMPORTANT: Replace placeholder

        return CompletableFuture.allOf(
            billingFuture, historyFuture,
            gcpOptimizationService.getOptimizationSummary(accountId).thenAccept(finalReport::setOptimizationSummary),
            gcpOptimizationService.getRightsizingRecommendations(accountId).thenAccept(finalReport::setRightsizingRecommendations),
            gcpOptimizationService.getWasteReport(accountId).thenAccept(finalReport::setWastedResources),
            gcpBudgetService.getBudgets(billingAccountId).thenAccept(finalReport::setBudgets),
            gcpOptimizationService.getTaggingCompliance(accountId).thenAccept(finalReport::setTaggingCompliance)
        ).thenApply(v -> {
            log.info("Successfully aggregated GCP FinOps report for accountId: {}", accountId);
            return ResponseEntity.ok(finalReport);
        }).exceptionally(ex -> {
            log.error("Failed to generate GCP FinOps report for accountId: {}", accountId, ex);
            return ResponseEntity.internalServerError().build();
        });
    }
}