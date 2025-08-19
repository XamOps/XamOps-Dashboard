package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.ImageState;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OptimizationService {

    private static final Logger logger = LoggerFactory.getLogger(OptimizationService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final PricingService pricingService;
    private final CloudListService cloudListService;
    private final DatabaseCacheService dbCache;

    @Autowired
    public OptimizationService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            PricingService pricingService,
            @Lazy CloudListService cloudListService,
            DatabaseCacheService dbCache) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.pricingService = pricingService;
        this.cloudListService = cloudListService;
        this.dbCache = dbCache;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getAllOptimizationRecommendations(String accountId, boolean forceRefresh) {
        String cacheKey = "allRecommendations-" + accountId;
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        return cloudListService.getRegionStatusForAccount(account, forceRefresh).thenCompose(activeRegions -> {
            logger.info("Fetching all optimization recommendations for account {}...", account.getAwsAccountId());
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2 = getEc2InstanceRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebs = getEbsVolumeRecommendations(account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambda = getLambdaFunctionRecommendations(account, activeRegions, forceRefresh);
            return CompletableFuture.allOf(ec2, ebs, lambda).thenApply(v -> {
                List<DashboardData.OptimizationRecommendation> allRecs = Stream.of(ec2.join(), ebs.join(), lambda.join()).flatMap(List::stream).collect(Collectors.toList());
                logger.debug("Fetched a total of {} optimization recommendations for account {}", allRecs.size(), accountId);
                dbCache.put(cacheKey, allRecs);
                return allRecs;
            });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEc2InstanceRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "ec2Recs-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            String nextToken = null;

            // **FIXED LOGIC**: Use raw strings instead of enum constants to avoid resolution issues.
                RecommendationPreferences recommendationPreferences = RecommendationPreferences.builder()
                    .cpuVendorArchitectures(List.of(CpuVendorArchitecture.AWS_ARM64))
                    .build();

            do {
                GetEc2InstanceRecommendationsRequest.Builder requestBuilder = GetEc2InstanceRecommendationsRequest.builder()
                    .recommendationPreferences(recommendationPreferences); // Apply the preference
                
                if (nextToken != null) {
                    requestBuilder.nextToken(nextToken);
                }
                GetEc2InstanceRecommendationsResponse response = co.getEC2InstanceRecommendations(requestBuilder.build());
                
                response.instanceRecommendations().stream()
                    .filter(r -> r.finding() != null && r.finding() != Finding.OPTIMIZED && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
                    .map(r -> {
                        InstanceRecommendationOption bestOption = r.recommendationOptions().stream()
                            .max(Comparator.comparingDouble(opt -> 
                                opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null && opt.savingsOpportunity().estimatedMonthlySavings().value() != null
                                ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0))
                            .orElse(r.recommendationOptions().get(0));

                        double savings = bestOption.savingsOpportunity() != null && bestOption.savingsOpportunity().estimatedMonthlySavings() != null && bestOption.savingsOpportunity().estimatedMonthlySavings().value() != null
                                ? bestOption.savingsOpportunity().estimatedMonthlySavings().value() : 0.0;
                        
                        double recommendedCost = pricingService.getEc2InstanceMonthlyPrice(bestOption.instanceType(), regionId);
                        double currentCost = recommendedCost + savings;

                        return new DashboardData.OptimizationRecommendation(
                                "EC2", r.instanceArn().split("/")[1], r.currentInstanceType(), bestOption.instanceType(),
                                savings, r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", ")),
                                currentCost, recommendedCost
                        );
                    })
                    .forEach(recommendations::add);
                
                nextToken = response.nextToken();
            } while (nextToken != null);
            
            return recommendations;
        }, "EC2 Recommendations").thenApply(result -> {
            dbCache.put(cacheKey, result);
            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEbsVolumeRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "ebsRecs-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            String nextToken = null;

            do {
                GetEbsVolumeRecommendationsRequest.Builder requestBuilder = GetEbsVolumeRecommendationsRequest.builder();
                if (nextToken != null) {
                    requestBuilder.nextToken(nextToken);
                }
                GetEbsVolumeRecommendationsResponse response = co.getEBSVolumeRecommendations(requestBuilder.build());

                response.volumeRecommendations().stream()
                    .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.volumeRecommendationOptions() != null && !r.volumeRecommendationOptions().isEmpty())
                    .map(r -> {
                        VolumeRecommendationOption bestOption = r.volumeRecommendationOptions().stream()
                            .max(Comparator.comparingDouble(opt ->
                                opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0))
                            .orElse(r.volumeRecommendationOptions().get(0));

                        double savings = bestOption.savingsOpportunity() != null && bestOption.savingsOpportunity().estimatedMonthlySavings() != null ? bestOption.savingsOpportunity().estimatedMonthlySavings().value() : 0.0;
                        
                        double recommendedCost = pricingService.getEbsGbMonthPrice(regionId, bestOption.configuration().volumeType()) * bestOption.configuration().volumeSize();
                        double currentCost = savings + recommendedCost;

                        return new DashboardData.OptimizationRecommendation(
                                "EBS", r.volumeArn().split("/")[1],
                                r.currentConfiguration().volumeType() + " - " + r.currentConfiguration().volumeSize() + "GiB",
                                bestOption.configuration().volumeType() + " - " + bestOption.configuration().volumeSize() + "GiB",
                                savings, r.finding().toString(), currentCost, recommendedCost
                        );
                    })
                    .forEach(recommendations::add);
                
                nextToken = response.nextToken();
            } while (nextToken != null);

            return recommendations;
        }, "EBS Recommendations").thenApply(result -> {
            dbCache.put(cacheKey, result);
            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getLambdaFunctionRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "lambdaRecs-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            String nextToken = null;

            try {
                do {
                    GetLambdaFunctionRecommendationsRequest.Builder requestBuilder = GetLambdaFunctionRecommendationsRequest.builder();
                    if (nextToken != null) {
                        requestBuilder.nextToken(nextToken);
                    }
                    GetLambdaFunctionRecommendationsResponse response = co.getLambdaFunctionRecommendations(requestBuilder.build());

                    response.lambdaFunctionRecommendations().stream()
                        .filter(r -> r.finding() != null && r.finding() != LambdaFunctionRecommendationFinding.OPTIMIZED && r.memorySizeRecommendationOptions() != null && !r.memorySizeRecommendationOptions().isEmpty())
                        .map(r -> {
                            LambdaFunctionMemoryRecommendationOption bestOption = r.memorySizeRecommendationOptions().stream()
                                .max(Comparator.comparingDouble(opt ->
                                    opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0))
                                .orElse(r.memorySizeRecommendationOptions().get(0));

                            double savings = bestOption.savingsOpportunity() != null && bestOption.savingsOpportunity().estimatedMonthlySavings() != null ? bestOption.savingsOpportunity().estimatedMonthlySavings().value() : 0.0;
                            double recommendedCost = savings > 0 ? savings * 1.5 : 0; // Simplified cost
                            double currentCost = recommendedCost + savings;

                            return new DashboardData.OptimizationRecommendation(
                                    "Lambda", r.functionArn().substring(r.functionArn().lastIndexOf(':') + 1),
                                    r.currentMemorySize() + " MB", bestOption.memorySize() + " MB",
                                    savings, r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", ")),
                                    currentCost, recommendedCost
                            );
                        })
                        .forEach(recommendations::add);
                    
                    nextToken = response.nextToken();
                } while (nextToken != null);
            } catch (Exception e) {
                logger.error("Could not fetch Lambda Recommendations for account {} in region {}. This might be a permissions issue or Compute Optimizer is not enabled.", account.getAwsAccountId(), regionId, e);
            }
            return recommendations;
        }, "Lambda Recommendations").thenApply(result -> {
            dbCache.put(cacheKey, result);
            return result;
        });
    }
    
    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "wastedResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.WastedResource>> cachedData = dbCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        logger.info("Fetching wasted resources for account {}...", account.getAwsAccountId());
        List<CompletableFuture<List<DashboardData.WastedResource>>> futures = List.of(
                findUnattachedEbsVolumes(account, activeRegions), findUnusedElasticIps(account, activeRegions),
                findOldSnapshots(account, activeRegions), findDeregisteredAmis(account, activeRegions),
                findIdleRdsInstances(account, activeRegions), findIdleLoadBalancers(account, activeRegions),
                findUnusedSecurityGroups(account, activeRegions), findIdleEc2Instances(account, activeRegions),
                findUnattachedEnis(account, activeRegions), findIdleEksClusters(account, activeRegions),
                findIdleEcsClusters(account, activeRegions), findUnderutilizedLambdaFunctions(account, activeRegions),
                findOldDbSnapshots(account, activeRegions), findUnusedCloudWatchLogGroups(account, activeRegions)
        );

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<DashboardData.WastedResource> allWasted = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
                    logger.debug("... found {} total wasted resources for account {}.", allWasted.size(), account.getAwsAccountId());
                    dbCache.put(cacheKey, allWasted);
                    return allWasted;
                });
    }

    // ... [ The rest of the waste-finding methods (findUnattachedEbsVolumes, etc.) remain unchanged ] ...
    private CompletableFuture<List<DashboardData.WastedResource>> findUnattachedEbsVolumes(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeVolumes(req -> req.filters(List.of(software.amazon.awssdk.services.ec2.model.Filter.builder().name("status").values("available").build())))
                    .volumes().stream()
                    .map(volume -> {
                        double monthlyCost = calculateEbsMonthlyCost(volume, regionId);
                        return new DashboardData.WastedResource(volume.volumeId(), getTagName(volume.tags(), volume.volumeId()), "EBS Volume", regionId, monthlyCost, "Unattached Volume");
                    })
                    .collect(Collectors.toList());
        }, "Unattached EBS Volumes");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedElasticIps(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            double monthlyCost = pricingService.getElasticIpMonthlyPrice(regionId);
            return ec2.describeAddresses().addresses().stream()
                    .filter(address -> address.associationId() == null)
                    .map(address -> new DashboardData.WastedResource(address.allocationId(), address.publicIp(), "Elastic IP", regionId, monthlyCost, "Unassociated EIP"))
                    .collect(Collectors.toList());
        }, "Unused Elastic IPs");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findOldSnapshots(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return ec2.describeSnapshots(r -> r.ownerIds("self")).snapshots().stream()
                    .filter(s -> s.startTime().isBefore(ninetyDaysAgo))
                    .map(snapshot -> new DashboardData.WastedResource(snapshot.snapshotId(), getTagName(snapshot.tags(), snapshot.snapshotId()), "Snapshot", regionId, calculateSnapshotMonthlyCost(snapshot), "Older than 90 days"))
                    .collect(Collectors.toList());
        }, "Old Snapshots");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findDeregisteredAmis(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            DescribeImagesRequest imagesRequest = DescribeImagesRequest.builder().owners("self").build();
            return ec2.describeImages(imagesRequest).images().stream()
                    .filter(image -> image.state() != ImageState.AVAILABLE)
                    .map(image -> new DashboardData.WastedResource(image.imageId(), image.name(), "AMI", regionId, 0.5, "Deregistered or Failed State"))
                    .collect(Collectors.toList());
        }, "Deregistered AMIs");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleRdsInstances(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            return rds.describeDBInstances().dbInstances().stream()
                    .filter(db -> isRdsInstanceIdle(account, db, regionId))
                    .map(dbInstance -> {
                        double monthlyCost = pricingService.getRdsInstanceMonthlyPrice(dbInstance, regionId);
                        return new DashboardData.WastedResource(dbInstance.dbInstanceIdentifier(), dbInstance.dbInstanceIdentifier(), "RDS Instance", regionId, monthlyCost, "Idle RDS Instance (no connections)");
                    })
                    .collect(Collectors.toList());
        }, "Idle RDS Instances");
    }

    private boolean isRdsInstanceIdle(CloudAccount account, software.amazon.awssdk.services.rds.model.DBInstance dbInstance, String regionId) {
        try {
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(Instant.now().minus(7, ChronoUnit.DAYS)).endTime(Instant.now())
                    .metricDataQueries(MetricDataQuery.builder()
                            .id("rdsConnections").metricStat(MetricStat.builder()
                                    .metric(software.amazon.awssdk.services.cloudwatch.model.Metric.builder().namespace("AWS/RDS").metricName("DatabaseConnections")
                                            .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder().name("DBInstanceIdentifier").value(dbInstance.dbInstanceIdentifier()).build()).build())
                                    .period(86400).stat("Maximum").build())
                            .returnData(true).build())
                    .build();
            List<MetricDataResult> results = cw.getMetricData(request).metricDataResults();
            if (!results.isEmpty() && !results.get(0).values().isEmpty()) {
                return results.get(0).values().stream().allMatch(v -> v < 1);
            }
        } catch (Exception e) {
            logger.error("Could not get metrics for RDS instance {} in account {}: {}", dbInstance.dbInstanceIdentifier(), account.getAwsAccountId(), e.getMessage());
        }
        return false;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleLoadBalancers(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, regionId);
            return elbv2.describeLoadBalancers().loadBalancers().stream()
                .filter(lb -> {
                    boolean isIdle = elbv2.describeTargetGroups(req -> req.loadBalancerArn(lb.loadBalancerArn()))
                            .targetGroups().stream()
                            .allMatch(tg -> elbv2.describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn())).targetHealthDescriptions().isEmpty());
                    return isIdle;
                })
                .map(lb -> new DashboardData.WastedResource(lb.loadBalancerArn(), lb.loadBalancerName(), "Load Balancer", regionId, 17.0, "Idle Load Balancer (no targets)"))
                .collect(Collectors.toList());
        }, "Idle Load Balancers");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEksClusters(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EksClient eks = awsClientProvider.getEksClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<DashboardData.WastedResource> findings = new ArrayList<>();
            
            eks.listClusters().clusters().forEach(clusterName -> {
                try {
                    GetMetricDataRequest request = GetMetricDataRequest.builder()
                            .startTime(Instant.now().minus(14, ChronoUnit.DAYS))
                            .endTime(Instant.now())
                            .metricDataQueries(MetricDataQuery.builder()
                                .id("cpu").metricStat(MetricStat.builder()
                                        .metric(software.amazon.awssdk.services.cloudwatch.model.Metric.builder()
                                                .namespace("ContainerInsights").metricName("node_cpu_utilization")
                                                .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder().name("ClusterName").value(clusterName).build()).build())
                                        .period(86400).stat("Average").build()).returnData(true).build())
                            .build();

                    List<MetricDataResult> results = cw.getMetricData(request).metricDataResults();
                    if (!results.isEmpty() && !results.get(0).values().isEmpty()) {
                        double avgCpu = results.get(0).values().stream().mapToDouble(Double::doubleValue).average().orElse(100.0);
                        if (avgCpu < 5.0) { 
                            findings.add(new DashboardData.WastedResource(clusterName, clusterName, "EKS Cluster", regionId, 73.0, "Idle Cluster (low CPU)"));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not check EKS cluster {} for waste in region {}", clusterName, regionId, e);
                }
            });
            return findings;
        }, "Idle EKS Clusters");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEcsClusters(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EcsClient ecs = awsClientProvider.getEcsClient(account, regionId);
            return ecs.listClusters().clusterArns().stream()
                .map(clusterArn -> ecs.describeClusters(r -> r.clusters(clusterArn)).clusters().get(0))
                .filter(cluster -> cluster.runningTasksCount() == 0 && cluster.activeServicesCount() == 0)
                .map(cluster -> new DashboardData.WastedResource(cluster.clusterArn(), cluster.clusterName(), "ECS Cluster", regionId, 0.0, "Idle Cluster (0 tasks/services)"))
                .collect(Collectors.toList());
        }, "Idle ECS Clusters");
    }
    
    private CompletableFuture<List<DashboardData.WastedResource>> findUnderutilizedLambdaFunctions(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            LambdaClient lambda = awsClientProvider.getLambdaClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<DashboardData.WastedResource> findings = new ArrayList<>();

            lambda.listFunctions().functions().forEach(func -> {
                try {
                    GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                            .namespace("AWS/Lambda").metricName("Invocations")
                            .dimensions(software.amazon.awssdk.services.cloudwatch.model.Dimension.builder().name("FunctionName").value(func.functionName()).build())
                            .startTime(Instant.now().minus(30, ChronoUnit.DAYS)).endTime(Instant.now())
                            .period(2592000).statistics(Statistic.SUM).build();
                    
                    List<Datapoint> datapoints = cw.getMetricStatistics(request).datapoints();
                    if (datapoints.isEmpty() || datapoints.get(0).sum() < 10) {
                        findings.add(new DashboardData.WastedResource(func.functionArn(), func.functionName(), "Lambda", regionId, 0.50, "Low Invocations (<10 in 30d)"));
                    }
                } catch (Exception e) {
                    logger.warn("Could not check Lambda function {} for waste in region {}", func.functionName(), regionId, e);
                }
            });
            return findings;
        }, "Underutilized Lambda Functions");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findOldDbSnapshots(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return rds.describeDBSnapshots(r -> r.snapshotType("manual")).dbSnapshots().stream()
                .filter(snap -> snap.snapshotCreateTime().isBefore(ninetyDaysAgo))
                .map(snap -> new DashboardData.WastedResource(snap.dbSnapshotIdentifier(), snap.dbSnapshotIdentifier(), "DB Snapshot", regionId, snap.allocatedStorage() * 0.095, "Older than 90 days"))
                .collect(Collectors.toList());
        }, "Old DB Snapshots");
    }
    
    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedCloudWatchLogGroups(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchLogsClient logs = awsClientProvider.getCloudWatchLogsClient(account, regionId);
            long sixtyDaysAgoMillis = Instant.now().minus(60, ChronoUnit.DAYS).toEpochMilli();
            List<DashboardData.WastedResource> findings = new ArrayList<>();
            List<LogGroup> logGroups = logs.describeLogGroups().logGroups();
            
            for (LogGroup lg : logGroups) {
                if (lg.creationTime() < sixtyDaysAgoMillis && lg.storedBytes() == 0) {
                    findings.add(new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(), "Log Group", regionId, 0.0, "Empty and created over 60 days ago"));
                    continue;
                }
                try {
                    DescribeLogStreamsResponse response = logs.describeLogStreams(req -> req
                        .logGroupName(lg.logGroupName()).orderBy("LastEventTime").descending(true).limit(1));
                    if (response.logStreams().isEmpty() || response.logStreams().get(0).lastEventTimestamp() == null || response.logStreams().get(0).lastEventTimestamp() < sixtyDaysAgoMillis) {
                         findings.add(new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(), "Log Group", regionId, 0.0, "No new events in 60+ days"));
                    }
                } catch (Exception e) {
                    logger.warn("Could not describe log streams for {}: {}", lg.logGroupName(), e.getMessage());
                }
            }
            return findings;
        }, "Unused Log Groups");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedSecurityGroups(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeSecurityGroups().securityGroups().stream()
                    .filter(sg -> sg.vpcId() != null && isSecurityGroupUnused(account, sg.groupId(), regionId))
                    .map(sg -> new DashboardData.WastedResource(sg.groupId(), sg.groupName(), "Security Group", regionId, 0.0, "Unused Security Group"))
                    .collect(Collectors.toList());
        }, "Unused Security Groups");
    }

    private boolean isSecurityGroupUnused(CloudAccount account, String groupId, String regionId) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            DescribeNetworkInterfacesRequest request = DescribeNetworkInterfacesRequest.builder()
                    .filters(software.amazon.awssdk.services.ec2.model.Filter.builder().name("group-id").values(groupId).build()).build();
            return ec2.describeNetworkInterfaces(request).networkInterfaces().isEmpty();
        } catch (Exception e) {
            logger.error("Could not check usage for security group {} in account {}: {}", groupId, account.getAwsAccountId(), e.getMessage());
        }
        return false;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEc2Instances(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeInstances().reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .filter(instance -> isEc2InstanceIdle(account, instance, regionId))
                    .map(instance -> {
                        double monthlyCost = pricingService.getEc2InstanceMonthlyPrice(instance.instanceTypeAsString(), regionId);
                        return new DashboardData.WastedResource(instance.instanceId(), getTagName(instance.tags(), instance.instanceId()), "EC2 Instance", regionId, monthlyCost, "Idle EC2 Instance (low CPU)");
                    })
                    .collect(Collectors.toList());
        }, "Idle EC2 Instances");
    }

    private boolean isEc2InstanceIdle(CloudAccount account, Instance instance, String regionId) {
        try {
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            GetMetricDataRequest request = buildMetricDataRequest(instance.instanceId(), "CPUUtilization", "AWS/EC2", "InstanceId");
            List<MetricDataResult> results = cw.getMetricData(request).metricDataResults();
            if (!results.isEmpty() && !results.get(0).values().isEmpty()) {
                return results.get(0).values().stream().mapToDouble(Double::doubleValue).average().orElse(100.0) < 3.0;
            }
        } catch (Exception e) {
            logger.error("Could not get metrics for EC2 instance {}: {}", instance.instanceId(), e.getMessage());
        }
        return false;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnattachedEnis(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
    return fetchAllRegionalResources(account, activeRegions, regionId -> {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeNetworkInterfaces(req -> req.filters(List.of(software.amazon.awssdk.services.ec2.model.Filter.builder().name("status").values("available").build())))
            .networkInterfaces().stream()
            .map(eni -> new DashboardData.WastedResource(eni.networkInterfaceId(), getTagName(eni.tagSet(), eni.networkInterfaceId()), "ENI", regionId, 0.0, "Unattached ENI"))
            .collect(Collectors.toList());
    }, "Unattached ENIs");
    }
    
    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
            .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchFunction.apply(regionStatus.getRegionId());
                } catch (AwsServiceException e) {
                    logger.warn("Optimization check failed for account {}: {} in region {}. AWS Error: {}", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e.awsErrorDetails().errorMessage());
                    return Collections.<T>emptyList();
                } catch (Exception e) {
                    logger.error("Optimization check failed for account {}: {} in region {}.", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                    return Collections.<T>emptyList();
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }
    
    public String getTagName(List<Tag> tags, String defaultName) {
        if (tags == null || tags.isEmpty()) return defaultName;
        return tags.stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(defaultName);
    }
    private double calculateEbsMonthlyCost(Volume volume, String region) { double gbMonthPrice = pricingService.getEbsGbMonthPrice(region, volume.volumeTypeAsString()); return volume.size() * gbMonthPrice; }
    private double calculateSnapshotMonthlyCost(Snapshot snapshot) { if (snapshot.volumeSize() != null) return snapshot.volumeSize() * 0.05; return 0.0; }
    
    private GetMetricDataRequest buildMetricDataRequest(String resourceId, String metricName, String namespace, String dimensionName) { 
        Metric metric = Metric.builder().namespace(namespace).metricName(metricName).dimensions(Dimension.builder().name(dimensionName).value(resourceId).build()).build(); 
        MetricStat metricStat = MetricStat.builder().metric(metric).period(300).stat("Average").build(); 
        MetricDataQuery metricDataQuery = MetricDataQuery.builder().id(metricName.toLowerCase().replace(" ", "").replace("/", "")).metricStat(metricStat).returnData(true).build(); 
        return GetMetricDataRequest.builder().startTime(Instant.now().minus(1, ChronoUnit.DAYS)).endTime(Instant.now()).metricDataQueries(metricDataQuery).scanBy(ScanBy.TIMESTAMP_DESCENDING).build(); 
    }
}
