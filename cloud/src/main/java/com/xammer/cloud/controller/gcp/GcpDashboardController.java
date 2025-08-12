package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/dashboard")
public class GcpDashboardController {

    private final GcpDataService gcpDataService;

    public GcpDashboardController(GcpDataService gcpDataService) {
        this.gcpDataService = gcpDataService;
    }

    @GetMapping
    public CompletableFuture<GcpDashboardData> getDashboardData(@RequestParam String accountId) {
        return gcpDataService.getDashboardData(accountId);
    }
}
