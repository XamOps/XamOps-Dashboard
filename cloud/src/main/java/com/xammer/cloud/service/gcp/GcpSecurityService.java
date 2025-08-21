package com.xammer.cloud.service.gcp;

import com.google.cloud.securitycenter.v2.Finding;
import com.google.cloud.securitycenter.v2.ListFindingsRequest;
import com.google.cloud.securitycenter.v2.SecurityCenterClient;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GcpSecurityService {

    private final GcpClientProvider gcpClientProvider;

    public GcpSecurityService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    /**
     * **[FIXED]** This method is now updated to use the Security Command Center V2 API.
     */
    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            // Use the new V2 client from the provider
            Optional<SecurityCenterClient> clientOpt = gcpClientProvider.getSecurityCenterV2Client(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("Security Command Center V2 client not available for project {}. Returning empty list.", gcpProjectId);
                return Collections.emptyList();
            }

            List<GcpSecurityFinding> findings = new ArrayList<>();
            try (SecurityCenterClient client = clientOpt.get()) {
                // The parent resource for findings is a source within a project.
                // Using '-' as a wildcard for the source ID gets findings from all available sources.
                String parent = String.format("projects/%s/sources/-", gcpProjectId);
                log.info("Fetching security findings for project {} from parent {} using V2 API", gcpProjectId, parent);
                
                ListFindingsRequest request = ListFindingsRequest.newBuilder()
                    .setParent(parent)
                    .setFilter("state=\"ACTIVE\"") // Filter for active findings
                    .build();

                // Iterate through all pages of results and map them to our DTO
                for (com.google.cloud.securitycenter.v2.ListFindingsResponse.ListFindingsResult result : client.listFindings(request).iterateAll()) {
                    findings.add(mapToDto(result.getFinding()));
                }

                log.info("Successfully fetched {} active security findings for project {}.", findings.size(), gcpProjectId);

            } catch (Exception e) {
                log.error("Failed to get security findings for project {}. Check if the Security Command Center API is enabled and configured for the correct tier. Error: {}", gcpProjectId, e.getMessage());
                // The original error will be logged for debugging if the issue persists.
            }
            return findings;
        });
    }
    
    public int calculateSecurityScore(List<GcpSecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }
        Map<String, Long> counts = findings.stream()
            .collect(Collectors.groupingBy(GcpSecurityFinding::getSeverity, Collectors.counting()));

        long criticalWeight = 5;
        long highWeight = 2;
        long mediumWeight = 1;

        long weightedScore = (counts.getOrDefault("CRITICAL", 0L) * criticalWeight) +
                              (counts.getOrDefault("HIGH", 0L) * highWeight) +
                              (counts.getOrDefault("MEDIUM", 0L) * mediumWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);
        
        return Math.max(0, (int) Math.round(score));
    }

    /**
     * **[FIXED]** Maps a V2 Finding object to our internal DTO.
     * Also correctly maps the description field now.
     */
    private GcpSecurityFinding mapToDto(Finding finding) {
        return new GcpSecurityFinding(
                finding.getCategory(),
                finding.getDescription(), // Use getDescription() from the V2 Finding
                finding.getSeverity().toString(),
                finding.getResourceName()
        );
    }
}