package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/costs")
@Slf4j
public class GcpCostController {

    private final GcpCostService gcpCostService;

    public GcpCostController(GcpCostService gcpCostService) {
        this.gcpCostService = gcpCostService;
    }

    @GetMapping("/breakdown")
    public CompletableFuture<ResponseEntity<List<GcpCostDto>>> getCostBreakdown(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "SERVICE") String groupBy) {

        log.info("Received request for GCP cost breakdown for accountId: {}, groupBy: {}", accountId, groupBy);

        return gcpCostService.getCostBreakdown(accountId, groupBy)
                .thenApply(costData -> {
                    log.info("Successfully fetched {} cost breakdown items for accountId: {}", costData.size(), accountId);
                    return ResponseEntity.ok(costData);
                })
                .exceptionally(ex -> {
                    // This block will catch any exception from the async service call
                    log.error("Critical error fetching GCP cost breakdown for accountId: {}. Returning an error response.", accountId, ex);
                    return ResponseEntity.internalServerError().body(Collections.emptyList());
                });
    }
}