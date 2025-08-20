package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import com.xammer.cloud.service.gcp.GcpDataService;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/finops")
public class GcpFinOpsController {

    private final GcpCostService gcpCostService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpDataService gcpDataService;

    public GcpFinOpsController(GcpCostService gcpCostService,
                               GcpOptimizationService gcpOptimizationService,
                               GcpDataService gcpDataService) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpDataService = gcpDataService;
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<GcpFinOpsReportDto>> getFinOpsReport(@RequestParam String accountId) {
        // Fetch all data points in parallel
        CompletableFuture<GcpFinOpsReportDto> billingSummaryFuture = gcpCostService.getBillingSummary(accountId)
                .thenApply(summary -> {
                    GcpFinOpsReportDto report = new GcpFinOpsReportDto();
                    report.setBillingSummary(summary);
                    double mtdSpend = summary.stream().mapToDouble(dto -> dto.getAmount()).sum();
                    report.setMonthToDateSpend(mtdSpend);
                    return report;
                });

        CompletableFuture<GcpFinOpsReportDto> costHistoryFuture = gcpCostService.getHistoricalCosts(accountId)
                .thenApply(history -> {
                    GcpFinOpsReportDto report = new GcpFinOpsReportDto();
                    report.setCostHistory(history);
                    // Set last month's spend from historical data
                    // This part can be enhanced to be more precise
                    if (history.size() > 1) {
                        report.setLastMonthSpend(history.get(history.size() - 2).getAmount());
                    }
                    return report;
                });
        
        CompletableFuture<GcpFinOpsReportDto> optimizationSummaryFuture = gcpOptimizationService.getOptimizationSummary(accountId)
                .thenApply(optSummary -> {
                    GcpFinOpsReportDto report = new GcpFinOpsReportDto();
                    report.setOptimizationSummary(optSummary);
                    return report;
                });
        
        CompletableFuture<GcpFinOpsReportDto> rightsizingFuture = gcpOptimizationService.getRightsizingRecommendations(accountId)
                .thenApply(recommendations -> {
                    GcpFinOpsReportDto report = new GcpFinOpsReportDto();
                    report.setRightsizingRecommendations(recommendations);
                    return report;
                });

        CompletableFuture<GcpFinOpsReportDto> wasteReportFuture = gcpOptimizationService.getWasteReport(accountId)
                .thenApply(wasteItems -> {
                    GcpFinOpsReportDto report = new GcpFinOpsReportDto();
                    report.setWastedResources(wasteItems);
                    return report;
                });

        // Combine all futures
        return CompletableFuture.allOf(billingSummaryFuture, costHistoryFuture, optimizationSummaryFuture, rightsizingFuture, wasteReportFuture)
                .thenApply(v -> {
                    GcpFinOpsReportDto finalReport = new GcpFinOpsReportDto();
                    // Merge data from all futures into the final report
                    finalReport.setBillingSummary(billingSummaryFuture.join().getBillingSummary());
                    finalReport.setMonthToDateSpend(billingSummaryFuture.join().getMonthToDateSpend());
                    finalReport.setCostHistory(costHistoryFuture.join().getCostHistory());
                    finalReport.setLastMonthSpend(costHistoryFuture.join().getLastMonthSpend());
                    finalReport.setOptimizationSummary(optimizationSummaryFuture.join().getOptimizationSummary());
                    finalReport.setRightsizingRecommendations(rightsizingFuture.join().getRightsizingRecommendations());
                    finalReport.setWastedResources(wasteReportFuture.join().getWastedResources());
                    
                    // Calculate forecasted spend
                    double mtd = finalReport.getMonthToDateSpend();
                    finalReport.setForecastedSpend(gcpDataService.calculateForecastedSpend(mtd));
                    
                    return ResponseEntity.ok(finalReport);
                });
    }
}