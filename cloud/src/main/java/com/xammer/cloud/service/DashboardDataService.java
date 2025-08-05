package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ReservationInventoryDto;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DashboardDataService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardDataService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final DashboardUpdateService dashboardUpdateService;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final SecurityService securityService;
    private final FinOpsService finOpsService;
    private final ReservationService reservationService;

    @Autowired
    public DashboardDataService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            DashboardUpdateService dashboardUpdateService,
            CloudListService cloudListService,
            OptimizationService optimizationService,
            SecurityService securityService,
            FinOpsService finOpsService,
            ReservationService reservationService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.dashboardUpdateService = dashboardUpdateService;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.securityService = securityService;
        this.finOpsService = finOpsService;
        this.reservationService = reservationService;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Cacheable(value = "dashboardData", key = "#accountId")
    public DashboardData getDashboardData(String accountId) throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", accountId);
        CloudAccount account = getAccount(accountId);

        CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService.getAllResourcesGrouped(accountId);
        
        // This is a crucial step: get active regions first to pass to other services.
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService.getRegionStatusForAccount(account);
        List<DashboardData.RegionStatus> activeRegions = activeRegionsFuture.get();

        CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResources = groupedResourcesFuture; // Reuse the future

        CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(groupedResources);
        CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account, activeRegions);
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = optimizationService.getEc2InstanceRecommendations(account, activeRegions);
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = optimizationService.getEbsVolumeRecommendations(account, activeRegions);
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = optimizationService.getLambdaFunctionRecommendations(account, activeRegions);
        CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService.getWastedResources(account, activeRegions);
        CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = securityService.getComprehensiveSecurityFindings(account, activeRegions);
        CompletableFuture<List<ReservationInventoryDto>> reservationInventoryFuture = reservationService.getReservationInventory(account, activeRegions);
        CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService.getCostHistory(account);
        CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService.getBillingSummary(account);
        CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account);
        
        CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
                wastedResourcesFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture
        );

        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService.getCostAnomalies(account);
        CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = reservationService.getReservationAnalysis(account);
        CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = reservationService.getReservationPurchaseRecommendations(account);

        CompletableFuture.allOf(
            inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
            wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
            iamFuture, savingsFuture, anomaliesFuture, reservationFuture, reservationPurchaseFuture,
            reservationInventoryFuture
        ).join();

        logger.info("--- ALL ASYNC DATA FETCHES COMPLETE for account {}, assembling DTO ---", accountId);

        List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.get();
        List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.isCompletedExceptionally() ? Collections.emptyList() : ec2RecsFuture.get();
        List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.isCompletedExceptionally() ? Collections.emptyList() : ebsRecsFuture.get();
        List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.isCompletedExceptionally() ? Collections.emptyList() : lambdaRecsFuture.get();
        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.isCompletedExceptionally() ? Collections.emptyList() : anomaliesFuture.get();
        List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.isCompletedExceptionally() ? Collections.emptyList() : securityFindingsFuture.get();
        
        List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
            .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getCategory))
            .entrySet().stream()
            .map(entry -> new DashboardData.SecurityInsight(
                String.format("%s has %d potential issues", entry.getKey(), entry.getValue().size()),
                entry.getKey(),
                "SECURITY",
                entry.getValue().size()
            )).collect(Collectors.toList());

        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(
                wastedResources, ec2Recs, ebsRecs, lambdaRecs, anomalies
        );
        
        int securityScore = calculateSecurityScore(securityFindings);

        DashboardData data = new DashboardData();
        DashboardData.Account mainAccount = new DashboardData.Account(
                accountId, account.getAccountName(),
                activeRegions, inventoryFuture.get(), cwStatusFuture.get(), securityInsights,
                costHistoryFuture.get(), billingFuture.get(), iamFuture.get(), savingsFuture.get(),
                ec2Recs, anomalies, ebsRecs, lambdaRecs,
                reservationFuture.get(), reservationPurchaseFuture.get(),
                optimizationSummary, wastedResources, Collections.emptyList(), // Service Quotas removed from here
                securityScore);

        data.setSelectedAccount(mainAccount);

        List<DashboardData.Account> availableAccounts = cloudAccountRepository.findAll().stream()
                .map(acc -> new DashboardData.Account(acc.getAwsAccountId(), acc.getAccountName(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0))
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
    @Cacheable(value = "cloudwatchStatus", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        // This is a simplified version; a full implementation might need the original fetchAllRegionalResources logic
        // For now, we'll assume a single region check is sufficient for dashboard purposes or that it's handled elsewhere.
        // Returning a placeholder.
        return CompletableFuture.completedFuture(new DashboardData.CloudWatchStatus(0, 0, 0));
    }


    @Async("awsTaskExecutor")
    @Cacheable(value = "iamResources", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.IamResources> getIamResources(CloudAccount account) {
        IamClient iam = awsClientProvider.getIamClient(account);
        logger.info("Fetching IAM resources for account {}...", account.getAwsAccountId());
        int users = 0, groups = 0, policies = 0, roles = 0;
        try { users = iam.listUsers().users().size(); } catch (Exception e) { logger.error("IAM check failed for Users on account {}", account.getAwsAccountId(), e); }
        try { groups = iam.listGroups().groups().size(); } catch (Exception e) { logger.error("IAM check failed for Groups on account {}", account.getAwsAccountId(), e); }
        try { policies = iam.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size(); } catch (Exception e) { logger.error("IAM check failed for Policies on account {}", account.getAwsAccountId(), e); }
        try { roles = iam.listRoles().roles().size(); } catch (Exception e) { logger.error("IAM check failed for Roles on account {}", account.getAwsAccountId(), e); }
        return CompletableFuture.completedFuture(new DashboardData.IamResources(users, groups, policies, roles));
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

        long weightedScore = (counts.getOrDefault("Critical", 0L) * criticalWeight) +
                              (counts.getOrDefault("High", 0L) * highWeight) +
                              (counts.getOrDefault("Medium", 0L) * mediumWeight) +
                              (counts.getOrDefault("Low", 0L) * lowWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);
        
        return Math.max(0, (int) Math.round(score));
    }
}