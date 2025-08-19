package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CloudGuardService {

    private static final Logger logger = LoggerFactory.getLogger(CloudGuardService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;
    private final Set<String> keyQuotas;
    private final FinOpsService finOpsService;
    private final DatabaseCacheService dbCache; // Added for caching

    @Autowired
    public CloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService,
            @Value("${quotas.key-codes}") Set<String> keyQuotas,
            FinOpsService finOpsService,
            DatabaseCacheService dbCache) { // Added dbCache
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.keyQuotas = keyQuotas;
        this.finOpsService = finOpsService;
        this.dbCache = dbCache; // Added dbCache
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getServiceQuotaInfo(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "serviceQuotas-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        if (activeRegions.isEmpty()) {
            logger.warn("No active regions found for account {}, skipping service quota check.", account.getAwsAccountId());
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        CompletableFuture<Map<String, Double>> usageFuture = getCurrentUsageData(account, activeRegions);

        return usageFuture.thenCompose(usageMap -> {
            String primaryRegion = activeRegions.get(0).getRegionId();
            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account, primaryRegion);
            logger.info("Fetching service quota info for account {} in region {}...", account.getAwsAccountId(), primaryRegion);
            List<DashboardData.ServiceQuotaInfo> quotaInfos = new ArrayList<>();
            List<String> serviceCodes = Arrays.asList("ec2", "vpc", "rds", "lambda", "elasticloadbalancing");

            for (String serviceCode : serviceCodes) {
                try {
                    logger.info("Fetching quotas for service: {} in account {}", serviceCode, account.getAwsAccountId());
                    ListServiceQuotasRequest request = ListServiceQuotasRequest.builder().serviceCode(serviceCode).build();
                    List<ServiceQuota> quotas = sqClient.listServiceQuotas(request).quotas();

                    for (ServiceQuota quota : quotas) {
                        double usage = usageMap.getOrDefault(quota.quotaCode(), 0.0);
                        double limit = quota.value();
                        
                        double percentage = (limit > 0) ? (usage / limit) * 100 : 0;
                        if (percentage > 50 || isKeyQuota(quota.quotaCode())) {
                             String status = "OK";
                            if (percentage > 90) {
                                status = "CRITICAL";
                            } else if (percentage > 75) {
                                status = "WARN";
                            }

                            quotaInfos.add(new DashboardData.ServiceQuotaInfo(
                                quota.serviceName(),
                                quota.quotaName(),
                                limit,
                                usage,
                                status
                            ));
                        }
                    }
                } catch (Exception e) {
                    logger.error("Could not fetch service quotas for {} in account {}.", serviceCode, account.getAwsAccountId(), e);
                }
            }
            dbCache.put(cacheKey, quotaInfos); // Save fresh data to cache
            return CompletableFuture.completedFuture(quotaInfos);
        });
    }

    private boolean isKeyQuota(String quotaCode) {
        return this.keyQuotas.contains(quotaCode);
    }

    private CompletableFuture<Map<String, Double>> getCurrentUsageData(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        CompletableFuture<Integer> ec2CountFuture = countEc2Instances(account, activeRegions);
        CompletableFuture<Integer> vpcCountFuture = countVpcs(account, activeRegions);
        CompletableFuture<Integer> rdsCountFuture = countRdsInstances(account, activeRegions);
        CompletableFuture<Integer> albCountFuture = countAlbs(account, activeRegions);

        return CompletableFuture.allOf(ec2CountFuture, vpcCountFuture, rdsCountFuture, albCountFuture)
            .thenApply(v -> {
                Map<String, Double> usageMap = new HashMap<>();
                usageMap.put("L-1216C47A", (double) ec2CountFuture.join());
                usageMap.put("L-F678F1CE", (double) vpcCountFuture.join());
                usageMap.put("L-7295265B", (double) rdsCountFuture.join());
                usageMap.put("L-69A177A2", (double) albCountFuture.join());
                return usageMap;
            });
    }

    private CompletableFuture<Integer> countEc2Instances(CloudAccount account, List<DashboardData.RegionStatus> regions) {
        List<CompletableFuture<Integer>> futures = regions.stream()
            .map(region -> CompletableFuture.supplyAsync(() -> {
                try {
                    Ec2Client ec2 = awsClientProvider.getEc2Client(account, region.getRegionId());
                    return ec2.describeInstances(r -> r.filters(f -> f.name("instance-state-name").values("running")))
                             .reservations().stream().mapToInt(r -> r.instances().size()).sum();
                } catch (Exception e) {
                    logger.error("Failed to count EC2 instances in region {} for account {}", region.getRegionId(), account.getAwsAccountId(), e);
                    return 0;
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().mapToInt(CompletableFuture::join).sum());
    }

    private CompletableFuture<Integer> countVpcs(CloudAccount account, List<DashboardData.RegionStatus> regions) {
        List<CompletableFuture<Integer>> futures = regions.stream()
            .map(region -> CompletableFuture.supplyAsync(() -> {
                try {
                    Ec2Client ec2 = awsClientProvider.getEc2Client(account, region.getRegionId());
                    return ec2.describeVpcs().vpcs().size();
                } catch (Exception e) {
                    logger.error("Failed to count VPCs in region {} for account {}", region.getRegionId(), account.getAwsAccountId(), e);
                    return 0;
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().mapToInt(CompletableFuture::join).sum());
    }

    private CompletableFuture<Integer> countRdsInstances(CloudAccount account, List<DashboardData.RegionStatus> regions) {
        List<CompletableFuture<Integer>> futures = regions.stream()
            .map(region -> CompletableFuture.supplyAsync(() -> {
                try {
                    RdsClient rds = awsClientProvider.getRdsClient(account, region.getRegionId());
                    return rds.describeDBInstances().dbInstances().size();
                } catch (Exception e) {
                    logger.error("Failed to count RDS instances in region {} for account {}", region.getRegionId(), account.getAwsAccountId(), e);
                    return 0;
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().mapToInt(CompletableFuture::join).sum());
    }
    
    private CompletableFuture<Integer> countAlbs(CloudAccount account, List<DashboardData.RegionStatus> regions) {
        List<CompletableFuture<Integer>> futures = regions.stream()
            .map(region -> CompletableFuture.supplyAsync(() -> {
                try {
                    ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, region.getRegionId());
                    return (int) elbv2.describeLoadBalancers().loadBalancers().stream()
                        .filter(lb -> "application".equalsIgnoreCase(lb.typeAsString()))
                        .count();
                } catch (Exception e) {
                    logger.error("Failed to count ALBs in region {} for account {}", region.getRegionId(), account.getAwsAccountId(), e);
                    return 0;
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().mapToInt(CompletableFuture::join).sum());
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomalies(CloudAccount account, boolean forceRefresh) {
        return finOpsService.getCostAnomalies(account, forceRefresh);
    }
}