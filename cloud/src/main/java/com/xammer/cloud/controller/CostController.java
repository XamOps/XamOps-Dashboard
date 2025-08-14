package com.xammer.cloud.controller;

import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.CostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/costs")
public class CostController {

    private final CostService costService;

    public CostController(CostService costService) {
        this.costService = costService;
    }

    @GetMapping("/breakdown")
    public CompletableFuture<ResponseEntity<List<CostDto>>> getCostBreakdown(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return costService.getCostBreakdown(accountId, groupBy, tag, forceRefresh)
                .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/historical")
    public CompletableFuture<ResponseEntity<HistoricalCostDto>> getHistoricalCost(
            @RequestParam String accountId,
            @RequestParam String groupBy,
            @RequestParam String dimensionValue,
            @RequestParam(required = false) String tagKey,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return costService.getHistoricalCostForDimension(accountId, groupBy, dimensionValue, tagKey, forceRefresh)
                .thenApply(ResponseEntity::ok);
    }
}