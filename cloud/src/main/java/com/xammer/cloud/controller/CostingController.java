package com.xammer.cloud.controller;

import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.CostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/costing")
public class CostingController {

    private static final Logger logger = LoggerFactory.getLogger(CostingController.class);

    private final CostService costService;

    public CostingController(CostService costService) {
        this.costService = costService;
    }

    @GetMapping("/breakdown")
    public CompletableFuture<ResponseEntity<List<CostDto>>> getCostBreakdown(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam(required = false) String tagKey) {
        return costService.getCostBreakdown(accountId, groupBy, tagKey)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching cost breakdown for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/historical")
    public CompletableFuture<ResponseEntity<HistoricalCostDto>> getHistoricalCost(
            @RequestParam String accountId,
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String regionName,
            @RequestParam(defaultValue = "30") int days) {
        return costService.getHistoricalCost(accountId, serviceName, regionName, days)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching historical cost for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(new HistoricalCostDto(Collections.emptyList(), Collections.emptyList()));
                });
    }

    @GetMapping("/historical-by-dimension")
    public CompletableFuture<ResponseEntity<HistoricalCostDto>> getHistoricalCostForDimension(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam String dimensionValue,
            @RequestParam(required = false) String tagKey) {
        return costService.getHistoricalCostForDimension(accountId, groupBy, dimensionValue, tagKey)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching historical cost for dimension {} in account {}", dimensionValue, accountId, ex);
                    return ResponseEntity.status(500).body(new HistoricalCostDto(Collections.emptyList(), Collections.emptyList()));
                });
    }
}