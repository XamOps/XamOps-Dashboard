package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/finops")
public class GcpFinOpsController {

    private final GcpCostService gcpCostService;

    public GcpFinOpsController(GcpCostService gcpCostService) {
        this.gcpCostService = gcpCostService;
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<List<GcpCostDto>>> getFinOpsReport(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "service") String groupBy,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LocalDate end = (endDate == null) ? LocalDate.now() : LocalDate.parse(endDate);
        LocalDate start = (startDate == null) ? end.withDayOfMonth(1) : LocalDate.parse(startDate);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        try {
            return gcpCostService.getCost(accountId, groupBy, start.format(formatter), end.format(formatter))
                    .thenApply(ResponseEntity::ok);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @GetMapping("/costs-by-service")
    public CompletableFuture<ResponseEntity<List<GcpCostDto>>> getCostsByService(@RequestParam String accountId) {
         LocalDate end = LocalDate.now();
        LocalDate start = end.withDayOfMonth(1);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        try {
            return gcpCostService.getCost(accountId, "service", start.format(formatter), end.format(formatter))
                    .thenApply(ResponseEntity::ok);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

     @GetMapping("/historical-costs")
    public CompletableFuture<ResponseEntity<List<GcpCostDto>>> getHistoricalCosts(@RequestParam String accountId) {
        try {
            return gcpCostService.getHistoricalCosts(accountId).thenApply(ResponseEntity::ok);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}