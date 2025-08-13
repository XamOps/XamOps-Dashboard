package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import com.xammer.cloud.service.gcp.GcpOptimizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/optimization")
public class GcpOptimizationController {

    private final GcpOptimizationService gcpOptimizationService;

    public GcpOptimizationController(GcpOptimizationService gcpOptimizationService) {
        this.gcpOptimizationService = gcpOptimizationService;
    }

    @GetMapping("/rightsizing-recommendations")
    public CompletableFuture<ResponseEntity<List<GcpOptimizationRecommendation>>> getRightsizingRecommendations(@RequestParam String accountId) {
        return gcpOptimizationService.getRightsizingRecommendations(accountId)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * NEW ENDPOINT: Fetches a report of wasted resources in the specified GCP project.
     * @param accountId The GCP Project ID.
     * @return A CompletableFuture containing a list of GcpWasteItem objects.
     */
    @GetMapping("/waste-report")
    public CompletableFuture<ResponseEntity<List<GcpWasteItem>>> getWasteReport(@RequestParam String accountId) {
        return gcpOptimizationService.getWasteReport(accountId)
                .thenApply(ResponseEntity::ok);
    }
}
