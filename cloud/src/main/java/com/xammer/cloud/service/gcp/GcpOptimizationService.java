package com.xammer.cloud.service.gcp;

import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpOptimizationService {

    private final GcpClientProvider gcpClientProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public GcpOptimizationService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public CompletableFuture<List<GcpOptimizationRecommendation>> getRightsizingRecommendations(String gcpProjectId) {
        // ... existing implementation ...
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();

            try (RecommenderClient client = clientOpt.get()) {
                String recommenderId = "google.compute.instance.MachineTypeRecommender";
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);

                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(this::mapToRightsizingDto)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get rightsizing recommendations for project {}", gcpProjectId, e);
                return List.of();
            }
        });
    }

    public CompletableFuture<List<GcpWasteItem>> getWasteReport(String gcpProjectId) {
        // ... existing implementation ...
        log.info("Starting waste report generation for GCP project: {}", gcpProjectId);
        CompletableFuture<List<GcpWasteItem>> idleDisksFuture = findIdleResources(gcpProjectId, "google.compute.disk.IdleResourceRecommender", "Idle Persistent Disk");
        CompletableFuture<List<GcpWasteItem>> idleAddressesFuture = findIdleResources(gcpProjectId, "google.compute.address.IdleResourceRecommender", "Unused IP Address");
        CompletableFuture<List<GcpWasteItem>> idleInstancesFuture = findIdleResources(gcpProjectId, "google.compute.instance.IdleResourceRecommender", "Idle VM Instance");

        return CompletableFuture.allOf(idleDisksFuture, idleAddressesFuture, idleInstancesFuture)
                .thenApply(v -> Stream.of(idleDisksFuture.join(), idleAddressesFuture.join(), idleInstancesFuture.join())
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary(String gcpProjectId) {
        CompletableFuture<List<GcpWasteItem>> wasteFuture = getWasteReport(gcpProjectId);
        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = getRightsizingRecommendations(gcpProjectId);

        return CompletableFuture.allOf(wasteFuture, rightsizingFuture).thenApply(v -> {
            double wasteSavings = wasteFuture.join().stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum();
            double rightsizingSavings = rightsizingFuture.join().stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();
            
            List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
            if (rightsizingSavings > 0) suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
            if (wasteSavings > 0) suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));
            
            return new DashboardData.SavingsSummary(wasteSavings + rightsizingSavings, suggestions);
        });
    }

    public CompletableFuture<DashboardData.OptimizationSummary> getOptimizationSummary(String gcpProjectId) {
        CompletableFuture<List<GcpWasteItem>> wasteFuture = getWasteReport(gcpProjectId);
        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = getRightsizingRecommendations(gcpProjectId);

        return CompletableFuture.allOf(wasteFuture, rightsizingFuture).thenApply(v -> {
            double totalSavings = wasteFuture.join().stream().mapToDouble(GcpWasteItem::getMonthlySavings).sum()
                                + rightsizingFuture.join().stream().mapToDouble(GcpOptimizationRecommendation::getMonthlySavings).sum();
            long criticalAlerts = rightsizingFuture.join().size();
            return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
        });
    }

    private CompletableFuture<List<GcpWasteItem>> findIdleResources(String gcpProjectId, String recommenderId, String wasteType) {
        // ... existing implementation ...
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("Recommender client not available for project {}, skipping check for {}", gcpProjectId, wasteType);
                return new ArrayList<GcpWasteItem>();
            }

            try (RecommenderClient client = clientOpt.get()) {
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                log.info("Querying recommender '{}' for project {}", recommenderId, gcpProjectId);

                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(rec -> mapToWasteDto(rec, wasteType))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get waste report for recommender {} in project {}. This may be due to permissions or the API not being enabled.", recommenderId, gcpProjectId, e);
                return new ArrayList<GcpWasteItem>();
            }
        }, executor);
    }
    
    private GcpOptimizationRecommendation mapToRightsizingDto(Recommendation rec) {
        // ... existing implementation ...
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resourceName")) {
            resourceName = rec.getContent().getOverview().getFieldsMap().get("resourceName").getStringValue();
        }
        
        String currentMachineType = "N/A";
        String recommendedMachineType = "N/A";
        if (rec.getDescription().contains(" to ")) {
            String[] parts = rec.getDescription().split(" to ");
            currentMachineType = parts[0].replace("Change machine type from ", "");
            recommendedMachineType = parts[1];
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }
        
        return new GcpOptimizationRecommendation(resourceName, currentMachineType, recommendedMachineType, monthlySavings);
    }

    private GcpWasteItem mapToWasteDto(Recommendation rec, String wasteType) {
        // ... existing implementation ...
        String resourceName = "N/A";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("resource")) {
             resourceName = rec.getContent().getOverview().getFieldsMap().get("resource").getStringValue();
             resourceName = resourceName.substring(resourceName.lastIndexOf('/') + 1);
        }

        String location = "global";
        if (rec.getContent().getOverview().getFieldsMap().containsKey("location")) {
            location = rec.getContent().getOverview().getFieldsMap().get("location").getStringValue();
        }

        double monthlySavings = 0;
        if (rec.getPrimaryImpact().hasCostProjection()) {
            monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        }

        return new GcpWasteItem(resourceName, wasteType, location, monthlySavings);
    }
    
    // NEW: Method to find idle Persistent Disks, mapping to generic DTO
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getIdlePersistentDisks(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();
            try (RecommenderClient client = clientOpt.get()) {
                String recommenderId = "google.compute.disk.IdleResourceRecommender";
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(this::mapToDiskRecommendation)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to get idle disk recommendations for project {}", gcpProjectId, e);
                return List.of();
            }
        }, executor);
    }
    
    private DashboardData.OptimizationRecommendation mapToDiskRecommendation(Recommendation rec) {
        double monthlySavings = rec.getPrimaryImpact().getCostProjection().getCost().getNanos() / -1_000_000_000.0;
        String resourceName = rec.getDescription().replace("Delete idle persistent disk ", "").split(" at ")[0];
        return new DashboardData.OptimizationRecommendation(
                "Cloud Storage",
                resourceName,
                "Persistent Disk",
                "Delete Disk",
                monthlySavings,
                rec.getDescription(),
                monthlySavings,
                0.0 // Deleting the disk means the cost goes to zero.
        );
    }

    // NEW: Method to find underutilized Cloud Functions
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getUnderutilizedCloudFunctions(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            // This is a placeholder as a dedicated recommender for functions might not exist in the same way.
            // A real implementation would involve checking monitoring metrics and making a recommendation.
            // For now, we will return an empty list or mock data.
            return List.of();
        }, executor);
    }
}