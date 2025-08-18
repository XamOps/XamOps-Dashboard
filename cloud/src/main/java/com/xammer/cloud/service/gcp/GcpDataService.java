// File: src/main/java/com/xammer/cloud/service/gcp/GcpDataService.java
package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.DatabaseInstance;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;
import com.google.api.services.dns.model.ManagedZone;
import com.google.cloud.compute.v1.Network;
import com.google.cloud.compute.v1.NetworksClient;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.container.v1.Cluster;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.dto.gcp.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpDataService {
    
    // ... other fields remain the same

    private final GcpClientProvider gcpClientProvider;
    private final GcpCostService gcpCostService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpSecurityService gcpSecurityService;
    private final com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, double[]> regionCoordinates = loadRegionCoordinates();

    public GcpDataService(GcpClientProvider gcpClientProvider,
                          GcpCostService gcpCostService,
                          GcpOptimizationService gcpOptimizationService,
                          GcpSecurityService gcpSecurityService,
                          com.xammer.cloud.repository.CloudAccountRepository cloudAccountRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpSecurityService = gcpSecurityService;
        this.cloudAccountRepository = cloudAccountRepository;
    }
    
    // NEW METHOD to get region coordinates
private Map<String, double[]> loadRegionCoordinates() {
        Map<String, double[]> coords = new java.util.HashMap<>();
        try {
            // Hardcoded coordinates for major GCP regions. This is a more stable alternative.
            // A more advanced implementation could use the GCP Geocoding API if enabled.
            coords.put("us-east1", new double[]{33.829, -84.341});
            coords.put("us-central1", new double[]{41.258, -95.940});
            coords.put("us-west1", new double[]{37.422, -122.084});
            coords.put("us-west2", new double[]{33.943, -118.408});
            coords.put("us-west3", new double[]{40.761, -111.891});
            coords.put("us-west4", new double[]{36.170, -115.140});
            coords.put("southamerica-east1", new double[]{-23.550, -46.633});
            coords.put("europe-west1", new double[]{50.850, 4.350});
            coords.put("europe-west2", new double[]{51.507, -0.128});
            coords.put("europe-west3", new double[]{50.110, 8.680});
            coords.put("europe-west4", new double[]{52.370, 4.890});
            coords.put("europe-north1", new double[]{60.192, 24.946});
            coords.put("asia-south1", new double[]{19.076, 72.877});
            coords.put("asia-southeast1", new double[]{1.352, 103.819});
            coords.put("asia-southeast2", new double[]{-6.208, 106.845});
            coords.put("asia-east1", new double[]{25.033, 121.565});
            coords.put("asia-east2", new double[]{22.319, 114.169});
            coords.put("asia-northeast1", new double[]{35.689, 139.692});
            coords.put("asia-northeast2", new double[]{34.694, 135.502});
            coords.put("asia-northeast3", new double[]{37.566, 126.978});
            coords.put("australia-southeast1", new double[]{-33.868, 151.209});
            coords.put("australia-southeast2", new double[]{-37.813, 144.963});
        } catch (Exception e) {
            log.error("Failed to load GCP region coordinates.", e);
        }
        return coords;
    }

    // NEW METHOD to get region status
    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForGcp(List<GcpResourceDto> resources) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> activeRegions = resources.stream()
                .map(GcpResourceDto::getLocation)
                .map(loc -> loc.contains("-") ? loc.substring(0, loc.lastIndexOf('-')) : loc)
                .filter(regionCoordinates::containsKey)
                .collect(Collectors.toSet());

            return activeRegions.stream().map(regionId -> {
                double[] coords = regionCoordinates.get(regionId);
                return new DashboardData.RegionStatus(regionId, regionId, "ACTIVE", coords[0], coords[1]);
            }).collect(Collectors.toList());
        });
    }

    public CompletableFuture<GcpDashboardData> getDashboardData(String gcpProjectId) {
        log.info("--- LAUNCHING EXPANDED ASYNC DATA AGGREGATION FOR GCP project {} ---", gcpProjectId);

        // THE FIX IS HERE: Added .exceptionally() to each future to handle individual failures gracefully.
        CompletableFuture<List<GcpResourceDto>> resourcesFuture = getAllResources(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get all resources for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        // THE FIX IS HERE: Re-enable the security findings future.
        // The underlying issue in GcpSecurityService has been resolved.
        CompletableFuture<List<GcpSecurityFinding>> securityFindingsFuture = gcpSecurityService.getSecurityFindings(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get security findings for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });
        
        CompletableFuture<DashboardData.IamResources> iamResourcesFuture = getIamResources(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get IAM resources for project {}: {}", gcpProjectId, ex.getMessage());
                return new DashboardData.IamResources(0, 0, 0, 0); // Return empty object
            });

        CompletableFuture<List<GcpCostDto>> costHistoryFuture = gcpCostService.getHistoricalCosts(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get cost history for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<List<GcpCostDto>> billingSummaryFuture = gcpCostService.getBillingSummary(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get billing summary for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<List<GcpWasteItem>> wasteReportFuture = gcpOptimizationService.getWasteReport(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get waste report for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<List<GcpOptimizationRecommendation>> rightsizingFuture = gcpOptimizationService.getRightsizingRecommendations(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get rightsizing recommendations for project {}: {}", gcpProjectId, ex.getMessage());
                return Collections.emptyList();
            });

        CompletableFuture<DashboardData.SavingsSummary> savingsSummaryFuture = gcpOptimizationService.getSavingsSummary(gcpProjectId)
             .exceptionally(ex -> {
                log.error("Failed to get savings summary for project {}: {}", gcpProjectId, ex.getMessage());
                return new DashboardData.SavingsSummary(0.0, Collections.emptyList()); // Return empty object
            });

        CompletableFuture<DashboardData.OptimizationSummary> optimizationSummaryFuture = gcpOptimizationService.getOptimizationSummary(gcpProjectId)
            .exceptionally(ex -> {
                log.error("Failed to get optimization summary for project {}: {}", gcpProjectId, ex.getMessage());
                return new DashboardData.OptimizationSummary(0.0, 0); // Return empty object
            });


        // Chain the new region status future
        CompletableFuture<List<DashboardData.RegionStatus>> regionStatusFuture = resourcesFuture.thenCompose(this::getRegionStatusForGcp);

        return CompletableFuture.allOf(
            resourcesFuture, securityFindingsFuture, iamResourcesFuture, costHistoryFuture,
            billingSummaryFuture, wasteReportFuture, rightsizingFuture, savingsSummaryFuture,
            optimizationSummaryFuture, regionStatusFuture // Add to allOf
        ).thenApply(v -> {
            log.info("--- ALL EXPANDED GCP ASYNC DATA FETCHES COMPLETE, assembling DTO for project {} ---", gcpProjectId);

            GcpDashboardData data = new GcpDashboardData();
            
            data.setRegionStatus(regionStatusFuture.join()); // Populate the DTO
            data.setCostHistory(costHistoryFuture.join());
            // ... (rest of the method is the same)
            data.setBillingSummary(billingSummaryFuture.join());
            data.setWastedResources(wasteReportFuture.join());
            data.setRightsizingRecommendations(rightsizingFuture.join());
            data.setOptimizationSummary(optimizationSummaryFuture.join());
            data.setSavingsSummary(savingsSummaryFuture.join());
            List<GcpResourceDto> resources = resourcesFuture.join();
            List<GcpSecurityFinding> securityFindings = securityFindingsFuture.join();
            DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
            Map<String, Long> counts = resources.stream().collect(Collectors.groupingBy(GcpResourceDto::getType, Collectors.counting()));
            inventory.setEc2((int) counts.getOrDefault("Compute Engine", 0L).longValue());
            inventory.setS3Buckets((int) counts.getOrDefault("Cloud Storage", 0L).longValue());
            inventory.setRdsInstances((int) counts.getOrDefault("Cloud SQL", 0L).longValue());
            inventory.setKubernetes((int) counts.getOrDefault("Kubernetes Engine", 0L).longValue());
            inventory.setVpc((int) counts.getOrDefault("VPC Network", 0L).longValue());
            inventory.setRoute53Zones((int) counts.getOrDefault("Cloud DNS", 0L).longValue());
            data.setResourceInventory(inventory);
            double currentMtdSpend = data.getBillingSummary().stream().mapToDouble(GcpCostDto::getAmount).sum();
            data.setMonthToDateSpend(currentMtdSpend);
            LocalDate today = LocalDate.now();
            int daysInMonth = today.lengthOfMonth();
            int currentDay = today.getDayOfMonth();
            data.setForecastedSpend(currentDay > 0 ? (currentMtdSpend / currentDay) * daysInMonth : 0.0);
            String lastMonthStr = today.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
            double lastMonthSpend = data.getCostHistory().stream()
                    .filter(c -> c.getName().equals(lastMonthStr))
                    .mapToDouble(GcpCostDto::getAmount)
                    .findFirst().orElse(0.0);
            data.setLastMonthSpend(lastMonthSpend);
            data.setSecurityScore(gcpSecurityService.calculateSecurityScore(securityFindings));
            List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
                .collect(Collectors.groupingBy(GcpSecurityFinding::getCategory, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new DashboardData.SecurityInsight(
                    String.format("%s has potential issues", entry.getKey()),
                    entry.getKey(),
                    "High",
                    entry.getValue().intValue()
                )).collect(Collectors.toList());
            data.setSecurityInsights(securityInsights);
            data.setIamResources(iamResourcesFuture.join());

            return data;
        });
    }

    // ... other methods remain unchanged ...
    private GcpResourceDto mapInstanceToDto(Instance instance) {
        String zoneUrl = instance.getZone();
        String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
        return new GcpResourceDto(String.valueOf(instance.getId()), instance.getName(),
                "Compute Engine", zone, instance.getStatus());
    }

    private GcpResourceDto mapBucketToDto(Bucket bucket) {
        return new GcpResourceDto(bucket.getName(), bucket.getName(),
                "Cloud Storage", bucket.getLocation(), "ACTIVE");
    }

    private GcpResourceDto mapGkeToDto(Cluster cluster) {
        return new GcpResourceDto(cluster.getId(), cluster.getName(),
                "Kubernetes Engine", cluster.getLocation(), cluster.getStatus().toString());
    }

    private GcpResourceDto mapSqlToDto(DatabaseInstance instance) {
        return new GcpResourceDto(instance.getName(), instance.getName(),
                "Cloud SQL", instance.getRegion(), instance.getState().toString());
    }

    private GcpResourceDto mapVpcToDto(Network network) {
        return new GcpResourceDto(String.valueOf(network.getId()), network.getName(),
                "VPC Network", "global", "ACTIVE");
    }

    private GcpResourceDto mapDnsToDto(ManagedZone zone) {
        return new GcpResourceDto(String.valueOf(zone.getId()), zone.getDnsName(),
                "Cloud DNS", "global", "ACTIVE");
    }

    public CompletableFuture<List<GcpResourceDto>> getAllResources(String gcpProjectId) {
        log.info("Starting to fetch all GCP resources for project: {}", gcpProjectId);
        CompletableFuture<List<GcpResourceDto>> instancesFuture = CompletableFuture.supplyAsync(() -> getComputeInstances(gcpProjectId), executor);
        CompletableFuture<List<GcpResourceDto>> bucketsFuture = CompletableFuture.supplyAsync(() -> getStorageBuckets(gcpProjectId), executor);
        CompletableFuture<List<GcpResourceDto>> gkeFuture = CompletableFuture.supplyAsync(() -> getGkeClusters(gcpProjectId), executor);
        CompletableFuture<List<GcpResourceDto>> sqlFuture = CompletableFuture.supplyAsync(() -> getCloudSqlInstances(gcpProjectId), executor);
        CompletableFuture<List<GcpResourceDto>> vpcFuture = CompletableFuture.supplyAsync(() -> getVpcNetworks(gcpProjectId), executor);
        CompletableFuture<List<GcpResourceDto>> dnsFuture = CompletableFuture.supplyAsync(() -> getDnsZones(gcpProjectId), executor);

        return CompletableFuture.allOf(instancesFuture, bucketsFuture, gkeFuture, sqlFuture, vpcFuture, dnsFuture)
                .thenApply(v -> Stream.of(instancesFuture.join(), bucketsFuture.join(), gkeFuture.join(),
                                sqlFuture.join(), vpcFuture.join(), dnsFuture.join())
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<DashboardData.IamResources> getIamResources(String gcpProjectId) {
        log.info("Attempting to get IAM resources for project: {}", gcpProjectId);
        return CompletableFuture.supplyAsync(() -> {
            Optional<ProjectsClient> clientOpt = gcpClientProvider.getProjectsClient(gcpProjectId);
            if (clientOpt.isEmpty()) return new DashboardData.IamResources(0, 0, 0, 0);
            try (ProjectsClient projectsClient = clientOpt.get()) {
                Project project = projectsClient.getProject("projects/" + gcpProjectId);
                log.info("Fetched project details for: {}", project.getDisplayName());
                // This is a simplified count. A full implementation would query IAM policies.
                int userCount = 10; // Placeholder
                int roleCount = 20; // Placeholder
                return new DashboardData.IamResources(userCount, 0, 0, roleCount);
            } catch (Exception e) {
                log.error("Error fetching IAM resources for project: {}", gcpProjectId, e);
                return new DashboardData.IamResources(0, 0, 0, 0);
            }
        });
    }

    public CompletableFuture<GcpVpcTopology> getVpcTopology(String gcpProjectId) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Fetching VPC Topology for project: {}", gcpProjectId);
            // Placeholder implementation; expand as needed
            return new GcpVpcTopology();
        });
    }
    public void createGcpAccount(GcpAccountRequestDto request, Client client) throws IOException {
        try {
            Storage storage = gcpClientProvider.createStorageClient(request.getServiceAccountKey());
            storage.list(Storage.BucketListOption.pageSize(1));
            CloudAccount account = new CloudAccount();
            account.setAccountName(request.getAccountName());
            account.setProvider("GCP");
            account.setAwsAccountId(request.getProjectId()); // Reusing AWS field for project ID
            account.setGcpServiceAccountKey(request.getServiceAccountKey());
            account.setStatus("CONNECTED");
            account.setAccessType("read-only");
            account.setClient(client);
            account.setExternalId(request.getProjectId());
            account.setGcpProjectId(request.getProjectId());
            cloudAccountRepository.save(account);
        } catch (IOException e) {
            throw new RuntimeException("GCP connection error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("GCP error: " + e.getMessage(), e);
        }
    }
    
    // START: Added methods to fix the compilation errors
    private List<GcpResourceDto> getComputeInstances(String gcpProjectId) {
        log.info("Fetching Compute Engine instances for project: {}", gcpProjectId);
        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (InstancesClient client = clientOpt.get()) {
            List<GcpResourceDto> instances = StreamSupport.stream(client.aggregatedList(gcpProjectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getInstancesList().stream())
                    .map(this::mapInstanceToDto)
                    .collect(Collectors.toList());
            log.info("Found {} Compute Engine instances.", instances.size());
            return instances;
        } catch (Exception e) {
            log.error("Error fetching Compute Instances for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getStorageBuckets(String gcpProjectId) {
        log.info("Fetching Cloud Storage buckets for project: {}", gcpProjectId);
        Optional<Storage> clientOpt = gcpClientProvider.getStorageClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try {
            List<GcpResourceDto> buckets = StreamSupport.stream(clientOpt.get().list().iterateAll().spliterator(), false)
                    .map(this::mapBucketToDto)
                    .collect(Collectors.toList());
            log.info("Found {} Cloud Storage buckets.", buckets.size());
            return buckets;
        } catch (Exception e) {
            log.error("Error fetching Storage Buckets for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getGkeClusters(String gcpProjectId) {
        log.info("Fetching Kubernetes Engine clusters for project: {}", gcpProjectId);
        Optional<ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (ClusterManagerClient client = clientOpt.get()) {
            String parent = "projects/" + gcpProjectId + "/locations/-";
            List<Cluster> clusters = client.listClusters(parent).getClustersList();
            log.info("Found {} Kubernetes Engine clusters.", clusters.size());
            return clusters.stream()
                    .map(this::mapGkeToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching GKE clusters for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getCloudSqlInstances(String gcpProjectId) {
        log.info("Fetching Cloud SQL instances for project: {}", gcpProjectId);
        Optional<SQLAdmin> sqlAdminClientOpt = gcpClientProvider.getSqlAdminClient(gcpProjectId);
        if (sqlAdminClientOpt.isEmpty()) return List.of();

        try {
            List<DatabaseInstance> instances = sqlAdminClientOpt.get()
                    .instances()
                    .list(gcpProjectId)
                    .execute()
                    .getItems();
            if (instances == null) return List.of();
            log.info("Found {} Cloud SQL instances.", instances.size());
            return instances.stream().map(this::mapSqlToDto).collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error fetching Cloud SQL instances for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getVpcNetworks(String gcpProjectId) {
        log.info("Fetching VPC Networks for project: {}", gcpProjectId);
        Optional<NetworksClient> clientOpt = gcpClientProvider.getNetworksClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try (NetworksClient client = clientOpt.get()) {
            List<GcpResourceDto> networks = StreamSupport.stream(client.list(gcpProjectId).iterateAll().spliterator(), false)
                    .map(this::mapVpcToDto)
                    .collect(Collectors.toList());
            log.info("Found {} VPC Networks.", networks.size());
            return networks;
        } catch (Exception e) {
            log.error("Error fetching VPC Networks for project: {}", gcpProjectId, e);
            return List.of();
        }
    }

    private List<GcpResourceDto> getDnsZones(String gcpProjectId) {
        log.info("Fetching Cloud DNS zones for project: {}", gcpProjectId);
        Optional<com.google.api.services.dns.Dns> clientOpt = gcpClientProvider.getDnsZonesClient(gcpProjectId);
        if (clientOpt.isEmpty()) return List.of();
        try {
            com.google.api.services.dns.Dns dns = clientOpt.get();
            com.google.api.services.dns.Dns.ManagedZones.List request = dns.managedZones().list(gcpProjectId);
            List<ManagedZone> zones = request.execute().getManagedZones();
            if (zones == null) return List.of();
            log.info("Found {} Cloud DNS zones.", zones.size());
            return zones.stream()
                    .map(this::mapDnsToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error fetching DNS Zones for project: {}", gcpProjectId, e);
            return List.of();
        }
    }
    // END: Added methods to fix the compilation errors
}