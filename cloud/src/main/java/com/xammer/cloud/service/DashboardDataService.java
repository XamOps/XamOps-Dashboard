package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.dto.ReservationInventoryDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DashboardDataService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardDataService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final GcpDataService gcpDataService;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final SecurityService securityService;
    private final FinOpsService finOpsService;
    private final ReservationService reservationService;
    private final DatabaseCacheService dbCache;
    private final ObjectMapper objectMapper;

    @Autowired
    public DashboardDataService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            GcpDataService gcpDataService,
            @Lazy CloudListService cloudListService,
            @Lazy OptimizationService optimizationService,
            @Lazy SecurityService securityService,
            @Lazy FinOpsService finOpsService,
            @Lazy ReservationService reservationService,
            DatabaseCacheService dbCache,
            ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.securityService = securityService;
        this.finOpsService = finOpsService;
        this.reservationService = reservationService;
        this.dbCache = dbCache;
        this.objectMapper = objectMapper;
    }


    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    public DashboardData getDashboardData(String accountId, boolean forceRefresh) throws ExecutionException, InterruptedException, IOException {
        String cacheKey = "dashboardData-" + accountId;

        if (!forceRefresh) {
            Optional<DashboardData> cachedData = dbCache.get(cacheKey, DashboardData.class);
            if (cachedData.isPresent()) {
                return cachedData.get();
            }
        }

        CloudAccount account = getAccount(accountId);
        DashboardData freshData;

        if ("GCP".equals(account.getProvider())) {
            GcpDashboardData gcpData = gcpDataService.getDashboardData(account.getGcpProjectId())
                .exceptionally(ex -> {
                    logger.error("Failed to get a complete GCP dashboard data object for account {}. Returning partial data.", account.getGcpProjectId(), ex);
                    return new GcpDashboardData(); // Return empty DTO on failure
                })
                .get();
            freshData = mapGcpDataToDashboardData(gcpData, account);
        } else {
            freshData = getAwsDashboardData(account, forceRefresh);
        }

        dbCache.put(cacheKey, freshData);
        return freshData;
    }

    private DashboardData mapGcpDataToDashboardData(GcpDashboardData gcpData, CloudAccount account) {
        DashboardData data = new DashboardData();
        
        DashboardData.Account mainAccount = new DashboardData.Account();
        mainAccount.setId(account.getGcpProjectId());
        mainAccount.setName(account.getAccountName());
        
        mainAccount.setResourceInventory(gcpData.getResourceInventory());
        mainAccount.setIamResources(gcpData.getIamResources());
        mainAccount.setSecurityScore(gcpData.getSecurityScore());
        mainAccount.setSecurityInsights(gcpData.getSecurityInsights());
        mainAccount.setSavingsSummary(gcpData.getSavingsSummary());
        mainAccount.setMonthToDateSpend(gcpData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(gcpData.getForecastedSpend());
        mainAccount.setLastMonthSpend(gcpData.getLastMonthSpend());
        mainAccount.setOptimizationSummary(gcpData.getOptimizationSummary());
        mainAccount.setRegionStatus(gcpData.getRegionStatus());
        
        List<String> costLabels = gcpData.getCostHistory().stream().map(c -> c.getName()).collect(Collectors.toList());
        List<Double> costValues = gcpData.getCostHistory().stream().map(c -> c.getAmount()).collect(Collectors.toList());
        List<Boolean> costAnomalies = gcpData.getCostHistory().stream().map(c -> c.isAnomaly()).collect(Collectors.toList());
        mainAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));
        List<DashboardData.BillingSummary> billingSummary = gcpData.getBillingSummary().stream()
            .map(b -> new DashboardData.BillingSummary(b.getName(), b.getAmount()))
            .collect(Collectors.toList());
        mainAccount.setBillingSummary(billingSummary);
        List<DashboardData.OptimizationRecommendation> gceRecs = gcpData.getRightsizingRecommendations().stream()
            .map(rec -> new DashboardData.OptimizationRecommendation(
                "GCE", rec.getResourceName(), rec.getCurrentMachineType(),
                rec.getRecommendedMachineType(), rec.getMonthlySavings(), "Rightsizing opportunity", 0.0, 0.0))
            .collect(Collectors.toList());
        mainAccount.setEc2Recommendations(gceRecs);
        List<DashboardData.WastedResource> wastedResources = gcpData.getWastedResources().stream()
            .map(waste -> new DashboardData.WastedResource(
                waste.getResourceName(), waste.getResourceName(), waste.getType(),
                waste.getLocation(), waste.getMonthlySavings(), "Idle Resource"))
            .collect(Collectors.toList());
        mainAccount.setWastedResources(wastedResources);
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0,0,0));
        mainAccount.setCostAnomalies(Collections.emptyList());
        mainAccount.setEbsRecommendations(Collections.emptyList());
        mainAccount.setLambdaRecommendations(Collections.emptyList());

        data.setSelectedAccount(mainAccount);
        
        List<DashboardData.Account> availableAccounts = cloudAccountRepository.findAll().stream()
            .map(acc -> new DashboardData.Account(
                "AWS".equals(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                acc.getAccountName(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 0.0, 0.0, 0.0
            ))
            .collect(Collectors.toList());
        data.setAvailableAccounts(availableAccounts);
        
        return data;
    }


    private DashboardData getAwsDashboardData(CloudAccount account, boolean forceRefresh) throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", account.getAwsAccountId());

        CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService.getAllResourcesGrouped(account.getAwsAccountId(), forceRefresh);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService.getRegionStatusForAccount(account, forceRefresh);
        List<DashboardData.RegionStatus> activeRegions = activeRegionsFuture.get();

        CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(groupedResourcesFuture);
        CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account, activeRegions, forceRefresh);
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = optimizationService.getEc2InstanceRecommendations(account, activeRegions, forceRefresh);
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = optimizationService.getEbsVolumeRecommendations(account, activeRegions, forceRefresh);
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = optimizationService.getLambdaFunctionRecommendations(account, activeRegions, forceRefresh);
        CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService.getWastedResources(account, activeRegions, forceRefresh);
        CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = securityService.getComprehensiveSecurityFindings(account, activeRegions, forceRefresh);
        CompletableFuture<List<ReservationInventoryDto>> reservationInventoryFuture = reservationService.getReservationInventory(account, activeRegions, forceRefresh);
        CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService.getCostHistory(account, forceRefresh);
        CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService.getBillingSummary(account, forceRefresh);
        CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account, forceRefresh);
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account, forceRefresh);
        CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = reservationService.getReservationAnalysis(account, forceRefresh);
        CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = reservationService.getReservationPurchaseRecommendations(account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "STANDARD", forceRefresh);
        
        CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
            wastedResourcesFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture
        );

        CompletableFuture.allOf(
            inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
            wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
            iamFuture, savingsFuture, anomaliesFuture, reservationFuture, reservationPurchaseFuture,
            reservationInventoryFuture
        ).join();

        logger.info("--- ALL ASYNC DATA FETCHES COMPLETE for account {}, assembling DTO ---", account.getAwsAccountId());

        List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.get();
        List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.get();
        List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.get();
        List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.get();
        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.get();
        List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.get();

        List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
            .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getCategory, Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity, Collectors.counting())))
            .entrySet().stream()
            .map(entry -> new DashboardData.SecurityInsight(
                String.format("%s has potential issues", entry.getKey()),
                entry.getKey(),
                entry.getValue().keySet().stream().findFirst().orElse("INFO"),
                entry.getValue().values().stream().mapToInt(Long::intValue).sum()
            )).collect(Collectors.toList());
            
        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(
                wastedResources, ec2Recs, ebsRecs, lambdaRecs, anomalies
        );

        int securityScore = calculateSecurityScore(securityFindings);

        DashboardData data = new DashboardData();
        DashboardData.Account mainAccount = new DashboardData.Account(
            account.getAwsAccountId(), account.getAccountName(),
            activeRegions, inventoryFuture.get(), cwStatusFuture.get(), securityInsights,
            costHistoryFuture.get(), billingFuture.get(), iamFuture.get(), savingsFuture.get(),
            ec2Recs, anomalies, ebsRecs, lambdaRecs,
            reservationFuture.get(), reservationPurchaseFuture.get(),
            optimizationSummary, wastedResources, Collections.emptyList(),
            securityScore, 0.0, 0.0, 0.0
        );

        data.setSelectedAccount(mainAccount);

        List<DashboardData.Account> availableAccounts = cloudAccountRepository.findAll().stream()
            .map(acc -> new DashboardData.Account(
                "AWS".equals(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                acc.getAccountName(),
                Collections.emptyList(),
                null, null, Collections.emptyList(), null, Collections.emptyList(), null, null,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                100, 0.0, 0.0, 0.0
            ))
            .collect(Collectors.toList());
        data.setAvailableAccounts(availableAccounts);

        return data;
    }
    
    private CompletableFuture<DashboardData.ResourceInventory> getResourceInventory(CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture) {
        return groupedResourcesFuture.thenApply(groupedResources -> {
            DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
            Map<String, Integer> counts = groupedResources.stream()
                    .collect(Collectors.toMap(
                            DashboardData.ServiceGroupDto::getServiceType,
                            group -> group.getResources().size()
                    ));

            inventory.setVpc(counts.getOrDefault("VPC", 0));
            inventory.setEcs(counts.getOrDefault("ECS Cluster", 0));
            inventory.setEc2(counts.getOrDefault("EC2 Instance", 0));
            inventory.setKubernetes(counts.getOrDefault("EKS Cluster", 0));
            inventory.setLambdas(counts.getOrDefault("Lambda Function", 0));
            inventory.setEbsVolumes(counts.getOrDefault("EBS Volume", 0));
            inventory.setImages(counts.getOrDefault("AMI", 0));
            inventory.setSnapshots(counts.getOrDefault("Snapshot", 0));
            inventory.setS3Buckets(counts.getOrDefault("S3 Bucket", 0));
            inventory.setRdsInstances(counts.getOrDefault("RDS Instance", 0));
            inventory.setRoute53Zones(counts.getOrDefault("Route 53 Zone", 0));
            inventory.setLoadBalancers(counts.getOrDefault("Load Balancer", 0));
            return inventory;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "cloudwatchStatus-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.CloudWatchStatus> cachedData = dbCache.get(cacheKey, DashboardData.CloudWatchStatus.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        DashboardData.CloudWatchStatus status = new DashboardData.CloudWatchStatus(0, 0, 0);
        dbCache.put(cacheKey, status);
        return CompletableFuture.completedFuture(status);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.IamResources> getIamResources(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamResources> cachedData = dbCache.get(cacheKey, DashboardData.IamResources.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        IamClient iam = awsClientProvider.getIamClient(account);
        logger.info("Fetching IAM resources for account {}...", account.getAwsAccountId());
        int users = 0, groups = 0, policies = 0, roles = 0;
        try { users = iam.listUsers().users().size(); } catch (Exception e) { logger.error("IAM check failed for Users on account {}", account.getAwsAccountId(), e); }
        try { groups = iam.listGroups().groups().size(); } catch (Exception e) { logger.error("IAM check failed for Groups on account {}", account.getAwsAccountId(), e); }
        try { policies = iam.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size(); } catch (Exception e) { logger.error("IAM check failed for Policies on account {}", account.getAwsAccountId(), e); }
        try { roles = iam.listRoles().roles().size(); } catch (Exception e) { logger.error("IAM check failed for Roles on account {}", account.getAwsAccountId(), e); }
        
        DashboardData.IamResources resources = new DashboardData.IamResources(users, groups, policies, roles);
        dbCache.put(cacheKey, resources);
        return CompletableFuture.completedFuture(resources);
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary(
            CompletableFuture<List<DashboardData.WastedResource>> wastedFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture) {
        
        return CompletableFuture.allOf(wastedFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture)
            .thenApply(v -> {
                double wasteSavings = wastedFuture.join().stream()
                        .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                        .sum();

                double rightsizingSavings = Stream.of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
                        .flatMap(List::stream)
                        .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                        .sum();
                
                List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
                if (rightsizingSavings > 0) {
                    suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
                }
                if (wasteSavings > 0) {
                    suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));
                }
                
                double totalPotential = wasteSavings + rightsizingSavings;
                
                return new DashboardData.SavingsSummary(totalPotential, suggestions);
            });
    }

    private DashboardData.OptimizationSummary getOptimizationSummary(
        List<DashboardData.WastedResource> wastedResources,
        List<DashboardData.OptimizationRecommendation> ec2Recs,
        List<DashboardData.OptimizationRecommendation> ebsRecs,
        List<DashboardData.OptimizationRecommendation> lambdaRecs,
        List<DashboardData.CostAnomaly> anomalies
    ) {
        double rightsizingSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
            .flatMap(List::stream)
            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
            .sum();

        double wasteSavings = wastedResources.stream()
            .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
            .sum();

        double totalSavings = rightsizingSavings + wasteSavings;
        long criticalAlerts = anomalies.size() + ec2Recs.size() + ebsRecs.size() + lambdaRecs.size();
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    private int calculateSecurityScore(List<DashboardData.SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }
        Map<String, Long> counts = findings.stream()
            .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity, Collectors.counting()));

        long criticalWeight = 5;
        long highWeight = 2;
        long mediumWeight = 1;
        long lowWeight = 0;

        long weightedScore = (counts.getOrDefault("CRITICAL", 0L) * criticalWeight) +
                              (counts.getOrDefault("HIGH", 0L) * highWeight) +
                              (counts.getOrDefault("MEDIUM", 0L) * mediumWeight) +
                              (counts.getOrDefault("LOW", 0L) * lowWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);
        
        return Math.max(0, (int) Math.round(score));
    }
}