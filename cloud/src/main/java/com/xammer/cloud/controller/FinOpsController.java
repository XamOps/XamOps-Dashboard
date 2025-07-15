package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/finops")
public class FinOpsController {

    private final AwsDataService awsDataService;

    public FinOpsController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/report")
    public ResponseEntity<FinOpsReportDto> getFinOpsReport(@RequestParam String accountId, @RequestParam(required = false) boolean forceRefresh) throws ExecutionException, InterruptedException {
        if (forceRefresh) {
            awsDataService.clearFinOpsReportCache(accountId);
        }
        FinOpsReportDto report = awsDataService.getFinOpsReport(accountId).get();
        return ResponseEntity.ok(report);
    }
    
    @GetMapping("/cost-by-tag")
    public ResponseEntity<List<Map<String, Object>>> getCostByTag(@RequestParam String accountId, @RequestParam String tagKey) throws ExecutionException, InterruptedException {
        List<Map<String, Object>> costData = awsDataService.getCostByTag(accountId, tagKey).get();
        return ResponseEntity.ok(costData);
    }

    @PostMapping("/budgets")
    public ResponseEntity<Void> createBudget(@RequestParam String accountId, @RequestBody BudgetDetails budgetDetails) {
        awsDataService.createBudget(accountId, budgetDetails);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
