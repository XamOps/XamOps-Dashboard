package com.xammer.cloud.service.gcp;


import com.google.cloud.recommender.v1.Recommendation;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderName;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpOptimizationRecommendation;
import com.xammer.cloud.dto.gcp.GcpWasteItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.xammer.cloud.dto.gcp.TaggingComplianceDto;
import com.xammer.cloud.dto.gcp.GcpResourceDto;
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
    // Utility method to fetch average metric from GCP Monitoring API
    private double fetchAverageMetric(String projectId, String instanceId, String metricType) {
        try (com.google.cloud.monitoring.v3.MetricServiceClient client = com.google.cloud.monitoring.v3.MetricServiceClient.create()) {
            long now = System.currentTimeMillis() / 1000;
            long start = now - 30 * 24 * 60 * 60; // last 30 days
            com.google.monitoring.v3.TimeInterval interval = com.google.monitoring.v3.TimeInterval.newBuilder()
                .setStartTime(com.google.protobuf.util.Timestamps.fromSeconds(start))
                .setEndTime(com.google.protobuf.util.Timestamps.fromSeconds(now))
                .build();

            String filter = String.format(
                "metric.type=\"%s\" AND resource.labels.instance_id=\"%s\"",
                metricType, instanceId);

            com.google.monitoring.v3.ListTimeSeriesRequest request = com.google.monitoring.v3.ListTimeSeriesRequest.newBuilder()
                .setName(com.google.monitoring.v3.ProjectName.of(projectId).toString())
                .setFilter(filter)
                .setInterval(interval)
                .setView(com.google.monitoring.v3.ListTimeSeriesRequest.TimeSeriesView.FULL)
                .build();

            double sum = 0;
            int count = 0;
            for (com.google.monitoring.v3.TimeSeries ts : client.listTimeSeries(request).iterateAll()) {
                for (com.google.monitoring.v3.Point p : ts.getPointsList()) {
                    sum += p.getValue().getDoubleValue();
                    count++;
                }
            }
            return count > 0 ? sum / count : 1.0; // Default to 1.0 if no data
        } catch (Exception e) {
            log.error("Error fetching metric {} for instance {}", metricType, instanceId, e);
            return 1.0;
        }
    }
    // Example: Find underutilized VMs (high config, low usage)
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getUnderutilizedVMs(String gcpProjectId) {
        return gcpDataService.getAllResources(gcpProjectId).thenApply(resources -> {
            double cpuThreshold = 0.10; // 10% average CPU usage
            double gpuThreshold = 0.10; // 10% average GPU usage
            double memThreshold = 0.10; // 10% average memory usage
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            for (GcpResourceDto r : resources) {
                if (r.getType().equalsIgnoreCase("Compute Engine") && r.getStatus().equalsIgnoreCase("RUNNING")) {
                    String machineType = r.getTags() != null ? r.getTags().getOrDefault("machineType", "") : "";
                    String instanceId = r.getId();
                    double cpuUsage = fetchAverageMetric(gcpProjectId, instanceId, "compute.googleapis.com/instance/cpu/utilization");
                    double gpuUsage = fetchAverageMetric(gcpProjectId, instanceId, "compute.googleapis.com/instance/gpu/utilization");
                    double memUsage = fetchAverageMetric(gcpProjectId, instanceId, "compute.googleapis.com/instance/memory/utilization");
                    boolean highConfig = machineType.contains("n2") || machineType.contains("a2") || machineType.contains("highcpu") || machineType.contains("highmem") || machineType.contains("gpu");
                    boolean underutilized = highConfig && cpuUsage < cpuThreshold && gpuUsage < gpuThreshold && memUsage < memThreshold;
                    if (underutilized) {
                        recommendations.add(new DashboardData.OptimizationRecommendation(
                            "Compute Engine",
                            r.getName(),
                            machineType,
                            "Rightsize VM",
                            0.0,
                            String.format("VM is overprovisioned. CPU: %.2f%%, GPU: %.2f%%, Memory: %.2f%%. Consider switching to a lower configuration.", cpuUsage * 100, gpuUsage * 100, memUsage * 100),
                            0.0,
                            cpuUsage
                        ));
                    }
                }
            }
            return recommendations;
        });
    }
    private static final String GLOBAL_LOCATION = "global";

    private final GcpClientProvider gcpClientProvider;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final GcpDataService gcpDataService; 

    public GcpOptimizationService(GcpClientProvider gcpClientProvider, @org.springframework.context.annotation.Lazy GcpDataService gcpDataService) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpDataService = gcpDataService;
    }

    public CompletableFuture<List<GcpOptimizationRecommendation>> getRightsizingRecommendations(String gcpProjectId) {
        // ... existing implementation ...
        return CompletableFuture.supplyAsync(() -> {
            Optional<RecommenderClient> clientOpt = gcpClientProvider.getRecommenderClient(gcpProjectId);
            if (clientOpt.isEmpty()) return List.of();

            try (RecommenderClient client = clientOpt.get()) {
                String recommenderId = "google.compute.instance.MachineTypeRecommender";
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, GLOBAL_LOCATION, recommenderId);

                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(this::mapToRightsizingDto)
                        .toList();
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
                        .toList());
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
                return new ArrayList<>();
            }

            try (RecommenderClient client = clientOpt.get()) {
                RecommenderName recommenderName = RecommenderName.of(gcpProjectId, "global", recommenderId);
                log.info("Querying recommender '{}' for project {}", recommenderId, gcpProjectId);

                return StreamSupport.stream(client.listRecommendations(recommenderName).iterateAll().spliterator(), false)
                        .map(rec -> mapToWasteDto(rec, wasteType))
                        .toList();
            } catch (Exception e) {
                log.error("Failed to get waste report for recommender {} in project {}. This may be due to permissions or the API not being enabled.", recommenderId, gcpProjectId, e);
                return new ArrayList<>();
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
                        .toList();
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
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getUnderutilizedCloudFunctions() {
        return CompletableFuture.supplyAsync(List::of, executor);
    }
    public CompletableFuture<TaggingComplianceDto> getTaggingCompliance(String gcpProjectId) {
        // Required tags for compliance (AWS-like)
        List<String> requiredTags = List.of("Name", "Environment", "Owner");
        return gcpDataService.getAllResources(gcpProjectId).thenApply(resources -> {
            TaggingComplianceDto dto = new TaggingComplianceDto();
            if (resources == null || resources.isEmpty()) {
                dto.setCompliancePercentage(100.0);
                return dto;
            }
            int total = resources.size();
            List<GcpResourceDto> untaggedResources = resources.stream()
                .filter(r -> r.getTags() == null || requiredTags.stream().anyMatch(tag -> r.getTags().get(tag) == null || r.getTags().get(tag).isEmpty()))
                .toList();
            int untagged = untaggedResources.size();
            dto.setTotalResourcesScanned(total);
            dto.setUntaggedResourcesCount(untagged);
            dto.setCompliancePercentage(((double) (total - untagged) / total) * 100.0);
            dto.setUntaggedResources(
                untaggedResources.stream().limit(5)
                    .map(r -> new TaggingComplianceDto.UntaggedResource(r.getName(), r.getType()))
                    .toList()
            );
            return dto;
        });
    }
}