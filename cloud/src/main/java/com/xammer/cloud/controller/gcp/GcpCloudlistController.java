package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/gcp/cloudlist")
public class GcpCloudlistController {

    private final GcpDataService gcpDataService;

    public GcpCloudlistController(GcpDataService gcpDataService) {
        this.gcpDataService = gcpDataService;
    }

    /**
     * Fetches all discoverable resources for a given GCP project.
     * @param accountId The GCP Project ID.
     * @return A CompletableFuture with a list of all discovered resources.
     */
    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<List<GcpResourceDto>>> getAllResources(@RequestParam String accountId) {
        return gcpDataService.getAllResources(accountId)
                .thenApply(ResponseEntity::ok);
    }
}
