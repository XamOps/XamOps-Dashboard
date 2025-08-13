package com.xammer.cloud.service.gcp;

import com.google.cloud.securitycenter.v1.Finding;
import com.google.cloud.securitycenter.v1.ListFindingsRequest;
import com.google.cloud.securitycenter.v1.SecurityCenterClient;
import com.google.cloud.securitycenter.v1.ListFindingsResponse.ListFindingsResult;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpSecurityService {

    private final GcpClientProvider gcpClientProvider;

    public GcpSecurityService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SecurityCenterClient> clientOpt = gcpClientProvider.getSecurityCenterClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("Security Command Center client not available for project {}. Returning empty list.", gcpProjectId);
                return List.of();
            }

            try (SecurityCenterClient client = clientOpt.get()) {
                // THE FIX IS HERE: Corrected parent format to work with the latest API versions.
                String parent = String.format("projects/%s", gcpProjectId);
                log.info("Fetching security findings for project {} from parent {}", gcpProjectId, parent);
                
                ListFindingsRequest request = ListFindingsRequest.newBuilder().setParent(parent).build();
                List<GcpSecurityFinding> findings = StreamSupport.stream(client.listFindings(request).iterateAll().spliterator(), false)
                        .map(ListFindingsResult::getFinding)
                        .filter(finding -> finding.getState() == Finding.State.ACTIVE)
                        .map(this::mapToDto)
                        .collect(Collectors.toList());

                if (findings.isEmpty()) {
                    log.info("No active findings found for project {}.", gcpProjectId);
                }
                return findings;

            } catch (Exception e) {
                log.error("Failed to get security findings for project {}. Check if the Security Command Center API is enabled and configured for the correct tier. Error: {}", gcpProjectId, e.getMessage());
                return List.of();
            }
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

    private GcpSecurityFinding mapToDto(Finding finding) {
        return new GcpSecurityFinding(
                finding.getCategory(),
                "Description not available in this mapping.",
                finding.getSeverity().toString(),
                finding.getResourceName()
        );
    }
}