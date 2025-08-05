package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.dto.WhatIfScenarioDto;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class PerformanceInsightsService {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceInsightsService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final PricingService pricingService;
    private final CloudListService cloudListService;
    private final EksService eksService;
    private final Map<String, PerformanceInsightDto> archivedInsights = new HashMap<>();

    @Autowired
    public PerformanceInsightsService(CloudAccountRepository cloudAccountRepository,
                                      AwsClientProvider awsClientProvider,
                                      PricingService pricingService,
                                      @Lazy CloudListService cloudListService,
                                      @Lazy EksService eksService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.pricingService = pricingService;
        this.cloudListService = cloudListService;
        this.eksService = eksService;
    }

    public List<PerformanceInsightDto> getInsights(String accountId, String severity) {
        logger.info("Starting multi-region performance insights scan for account: {}", accountId);
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        try {
            List<DashboardData.RegionStatus> activeRegions = cloudListService.getRegionStatusForAccount(account).get();
            logger.info("Found {} active regions to scan for performance insights.", activeRegions.size());

            List<CompletableFuture<List<PerformanceInsightDto>>> futures = new ArrayList<>();

            for (DashboardData.RegionStatus region : activeRegions) {
                CompletableFuture<List<PerformanceInsightDto>> regionFuture = CompletableFuture.supplyAsync(() -> {
                    List<PerformanceInsightDto> regionalInsights = new ArrayList<>();
                    regionalInsights.addAll(getEC2InsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getRDSInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getLambdaInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getEBSInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getELBInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getContainerInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getBestPracticeAuditsForRegion(account, region.getRegionId()));
                    logger.info("Completed performance insights scan for region: {}", region.getRegionId());
                    regionalInsights.forEach(insight -> insight.setRegion(region.getRegionId()));
                    return regionalInsights;
                });
                futures.add(regionFuture);
            }

            futures.add(CompletableFuture.supplyAsync(() -> getS3Insights(account)));

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<PerformanceInsightDto> allInsights = futures.stream()
                    .flatMap(future -> future.join().stream())
                    .collect(Collectors.toList());

            logger.info("Total insights generated across all regions before filtering: {}", allInsights.size());

            if (severity != null && !severity.isEmpty() && !"ALL".equalsIgnoreCase(severity)) {
                PerformanceInsightDto.InsightSeverity severityEnum = PerformanceInsightDto.InsightSeverity.valueOf(severity.toUpperCase());
                return allInsights.stream()
                        .filter(insight -> insight.getSeverity() == severityEnum)
                        .collect(Collectors.toList());
            }

            return allInsights;

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error fetching performance insights for account: {}", accountId, e);
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
    }

    private List<PerformanceInsightDto> getBestPracticeAuditsForRegion(CloudAccount account, String regionId) {
        logger.info("Running best practice audits in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, regionId);
            ec2Client.describeVolumes().volumes().stream()
                .filter(v -> "gp2".equals(v.volumeTypeAsString()))
                .forEach(v -> insights.add(new PerformanceInsightDto(
                    "ebs-" + v.volumeId() + "-gp2",
                    "EBS Volume " + v.volumeId() + " is using gp2 instead of gp3.",
                    "Migrating to gp3 provides better performance at a lower cost.",
                    PerformanceInsightDto.InsightSeverity.WEAK_WARNING, PerformanceInsightDto.InsightCategory.BEST_PRACTICE,
                    account.getAwsAccountId(), 1, "EBS", v.volumeId(),
                    "Consider migrating this volume to the gp3 storage class.", "/docs/ebs-gp3-migration",
                    calculateEBSSavings(v) * 0.2, regionId, Instant.now().toString()
                )));
        } catch (Exception e) {
            logger.error("Error running best practice audits for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }


     public CompletableFuture<WhatIfScenarioDto> getWhatIfScenario(String accountId, String resourceId, String targetInstanceType) {
        return CompletableFuture.supplyAsync(() -> {
            CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                    .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

            String region = findInstanceRegion(account, resourceId);
            if (region == null) {
                throw new RuntimeException("Could not find region for instance: " + resourceId);
            }

            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
            Instance instance = ec2Client.describeInstances(r -> r.instanceIds(resourceId)).reservations().get(0).instances().get(0);
            String currentInstanceType = instance.instanceTypeAsString();

            double currentCost = pricingService.getEc2InstanceMonthlyPrice(currentInstanceType, region);
            double targetCost = pricingService.getEc2InstanceMonthlyPrice(targetInstanceType, region);

            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
            double peakCpu = getPeakCpuUtilization(cwClient, resourceId);

            double projectedPeakCpu = peakCpu / 2;

            return new WhatIfScenarioDto(
                    resourceId, currentInstanceType, targetInstanceType,
                    currentCost, targetCost, targetCost - currentCost,
                    peakCpu, projectedPeakCpu,
                    "Projected peak CPU utilization would be approximately " + String.format("%.1f", projectedPeakCpu) + "%."
            );
        });
    }

    private String findInstanceRegion(CloudAccount account, String instanceId) {
        try {
            List<DashboardData.RegionStatus> activeRegions = cloudListService.getRegionStatusForAccount(account).get();
            for (DashboardData.RegionStatus region : activeRegions) {
                Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region.getRegionId());
                DescribeInstancesResponse response = ec2Client.describeInstances(r -> r.instanceIds(instanceId));
                if (!response.reservations().isEmpty() && !response.reservations().get(0).instances().isEmpty()) {
                    return region.getRegionId();
                }
            }
        } catch (Exception e) {
            logger.error("Error finding region for instance {}: {}", instanceId, e.getMessage());
        }
        return null;
    }

    private double getPeakCpuUtilization(CloudWatchClient cloudWatchClient, String instanceId) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(14, ChronoUnit.DAYS);

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/EC2")
                    .metricName("CPUUtilization")
                    .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                    .startTime(startTime).endTime(endTime).period(3600) // Hourly
                    .statistics(Statistic.MAXIMUM).build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                    .mapToDouble(Datapoint::maximum).max().orElse(0.0);
        } catch (Exception e) {
            logger.error("Could not fetch peak CPU for {}", instanceId, e);
            return 0.0;
        }
    }
    public int calculatePerformanceScore(List<PerformanceInsightDto> insights) {
        int score = 100;
        int criticalWeight = 10;
        int warningWeight = 5;
        int weakWarningWeight = 2;

        for (PerformanceInsightDto insight : insights) {
            switch (insight.getSeverity()) {
                case CRITICAL:
                    score -= criticalWeight;
                    break;
                case WARNING:
                    score -= warningWeight;
                    break;
                case WEAK_WARNING:
                    score -= weakWarningWeight;
                    break;
            }
        }
        return Math.max(0, score);
    }

    // ... (getEC2InsightsForRegion, getRDSInsightsForRegion, getLambdaInsightsForRegion, getELBInsightsForRegion remain the same)
private List<PerformanceInsightDto> getEC2InsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for EC2 performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(account, regionId);

            ec2Client.describeInstances().reservations().stream()
                .flatMap(r -> r.instances().stream())
                .filter(instance -> "running".equals(instance.state().nameAsString()))
                .forEach(instance -> {
                    double avgCpu = getAverageMetric(cloudWatchClient, "AWS/EC2", "CPUUtilization", "InstanceId", instance.instanceId());
                    if (avgCpu < 10.0) {
                        insights.add(new PerformanceInsightDto(
                                "ec2-" + instance.instanceId() + "-underutilized",
                                "EC2 instance " + instance.instanceId() + " is underutilized (" + String.format("%.1f", avgCpu) + "% avg CPU).",
                                "Low resource utilization.",
                                avgCpu < 5.0 ? PerformanceInsightDto.InsightSeverity.CRITICAL : PerformanceInsightDto.InsightSeverity.WARNING,
                                PerformanceInsightDto.InsightCategory.COST, account.getAwsAccountId(), 1, "EC2",
                                instance.instanceId(), "Consider downsizing or terminating this instance.", "/docs/ec2-rightsizing",
                                calculateEC2Savings(instance.instanceTypeAsString()), regionId, Instant.now().toString()
                        ));
                    }
                });
        } catch (Exception e) {
            logger.error("Error fetching EC2 insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getRDSInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for RDS performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            RdsClient rdsClient = awsClientProvider.getRdsClient(account, regionId);
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(account, regionId);

            for (DBInstance dbInstance : rdsClient.describeDBInstances().dbInstances()) {
                if ("available".equals(dbInstance.dbInstanceStatus())) {
                    double avgCpu = getAverageMetric(cloudWatchClient, "AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", dbInstance.dbInstanceIdentifier());
                    double avgConns = getAverageMetric(cloudWatchClient, "AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", dbInstance.dbInstanceIdentifier());

                    if (avgCpu < 20.0 && avgConns < 5.0) {
                        insights.add(new PerformanceInsightDto(
                                "rds-" + dbInstance.dbInstanceIdentifier() + "-underutilized",
                                "RDS instance " + dbInstance.dbInstanceIdentifier() + " shows low utilization",
                                "Low CPU (" + String.format("%.1f", avgCpu) + "%) and connections (" + String.format("%.0f", avgConns) + ")",
                                PerformanceInsightDto.InsightSeverity.WARNING,
                                PerformanceInsightDto.InsightCategory.COST,
                                account.getAwsAccountId(), 1, "RDS", dbInstance.dbInstanceIdentifier(),
                                "Consider downsizing this RDS instance class", "/docs/rds-rightsizing",
                                calculateRDSSavings(dbInstance.dbInstanceClass()),
                                regionId, Instant.now().toString()));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching RDS insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }

        private List<PerformanceInsightDto> getLambdaInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for Lambda performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            LambdaClient lambdaClient = awsClientProvider.getLambdaClient(account, regionId);
            for (FunctionConfiguration function : lambdaClient.listFunctions().functions()) {
                long activeVersions = lambdaClient.listVersionsByFunction(v -> v.functionName(function.functionName()))
                        .versions().stream().filter(v -> !"$LATEST".equals(v.version())).count();
                if (activeVersions > 5) { // Example threshold
                    insights.add(new PerformanceInsightDto(
                            "lambda-" + function.functionName() + "-versions",
                            "Lambda function " + function.functionName() + " has a high number of versions.",
                            "Excessive versions can complicate management and increase storage costs slightly.",
                            PerformanceInsightDto.InsightSeverity.WEAK_WARNING, PerformanceInsightDto.InsightCategory.BEST_PRACTICE,
                            account.getAwsAccountId(), (int) activeVersions, "Lambda", function.functionName(),
                            "Review and remove unused function versions.", "/docs/lambda-versions",
                            0.0, regionId, Instant.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching Lambda insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getEBSInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for EBS performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(account, regionId);
            DescribeVolumesResponse volumesResponse = ec2Client.describeVolumes(req -> req.filters(f -> f.name("status").values("in-use")));
            for (Volume volume : volumesResponse.volumes()) {
                double readOps = getSumMetric(cloudWatchClient, "AWS/EBS", "VolumeReadOps", "VolumeId", volume.volumeId());
                double writeOps = getSumMetric(cloudWatchClient, "AWS/EBS", "VolumeWriteOps", "VolumeId", volume.volumeId());
                if (readOps < 100 && writeOps < 100) {
                    insights.add(new PerformanceInsightDto(
                            "ebs-" + volume.volumeId() + "-low-iops",
                            "EBS Volume " + volume.volumeId() + " has very low activity.",
                            "This volume may be over-provisioned.",
                            PerformanceInsightDto.InsightSeverity.WARNING, PerformanceInsightDto.InsightCategory.COST,
                            account.getAwsAccountId(), 1, "EBS", volume.volumeId(),
                            "Consider a lower-cost volume type or reducing IOPS.", "/docs/ebs-optimization",
                            calculateEBSSavings(volume), regionId, Instant.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching EBS insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }


        private List<PerformanceInsightDto> getELBInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for ELB performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            ElasticLoadBalancingV2Client elbv2Client = awsClientProvider.getElbv2Client(account, regionId);
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(account, regionId);
            for (LoadBalancer lb : elbv2Client.describeLoadBalancers().loadBalancers()) {
                double requestCount = getRequestCount(cloudWatchClient, lb.loadBalancerArn());
                if (requestCount < 10) {
                    insights.add(new PerformanceInsightDto(
                            "alb-" + lb.loadBalancerName() + "-low-traffic",
                            "ALB " + lb.loadBalancerName() + " has very low traffic.",
                            "Load balancer with minimal traffic may be unnecessary.",
                            PerformanceInsightDto.InsightSeverity.WARNING, PerformanceInsightDto.InsightCategory.COST,
                            account.getAwsAccountId(), 1, "ALB", lb.loadBalancerName(),
                            "Consider consolidating or removing if not needed.", "/docs/alb-cost-optimization",
                            calculateALBSavings(), regionId, Instant.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching ELB insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }

        private List<PerformanceInsightDto> getS3Insights(CloudAccount account) {
        logger.info("Checking for S3 performance insights...");
        List<PerformanceInsightDto> insights = new ArrayList<>();
        S3Client s3Client = awsClientProvider.getS3Client(account, "us-east-1");
        try {
            for (Bucket bucket : s3Client.listBuckets().buckets()) {
                // S3 insights are generally global or require specific region clients, keeping this simple.
            }
        } catch (Exception e) {
            logger.error("Failed to list S3 buckets for account {}: {}", account.getAwsAccountId(), e);
        }
        return insights;
    }
    

    private List<PerformanceInsightDto> getContainerInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for Container performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            List<K8sClusterInfo> clusters = eksService.getEksClusterInfo(account.getAwsAccountId()).get();
            for (K8sClusterInfo cluster : clusters) {
                if (!"ACTIVE".equalsIgnoreCase(cluster.getStatus())) {
                    insights.add(new PerformanceInsightDto(
                            "eks-" + cluster.getName() + "-inactive",
                            "EKS Cluster " + cluster.getName() + " is not active.",
                            "Inactive clusters may still incur costs.",
                            PerformanceInsightDto.InsightSeverity.CRITICAL, PerformanceInsightDto.InsightCategory.FAULT_TOLERANCE,
                            account.getAwsAccountId(), 1, "EKS", cluster.getName(),
                            "Repair or terminate the cluster.", "/docs/eks-troubleshooting",
                            73.0, regionId, Instant.now().toString()
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching Container insights for account: {} in region {}", account.getAwsAccountId(), regionId, e);
        }
        return insights;
    }
    

    // ... (rest of the service file remains the same)
    private double getP95LatencyForALB(CloudWatchClient cloudWatchClient, String loadBalancerArn, int timeRangeHours) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(timeRangeHours, ChronoUnit.HOURS);

            int period = 300; // 5 minutes in seconds

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/ApplicationELB")
                    .metricName("TargetResponseTime")
                    .dimensions(Dimension.builder()
                            .name("LoadBalancer")
                            .value(extractLoadBalancerName(loadBalancerArn))
                            .build())
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(period)
                    .extendedStatistics("p95")
                    .build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                    .filter(dp -> dp.extendedStatistics().containsKey("p95"))
                    .max(Comparator.comparing(Datapoint::timestamp))
                    .map(dp -> dp.extendedStatistics().get("p95") * 1000) // Convert to milliseconds
                    .orElse(0.0);

        } catch (Exception e) {
            logger.error("Could not fetch p95 latency for {}", loadBalancerArn, e);
            return 0.0;
        }
    }

    private String extractLoadBalancerName(String loadBalancerArn) {
        String[] parts = loadBalancerArn.split("/");
        if (parts.length >= 3) {
            return parts[1] + "/" + parts[2] + "/" + parts[3];
        }
        return loadBalancerArn;
    }

    private double getRequestCount(CloudWatchClient cloudWatchClient, String loadBalancerArn) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(24, ChronoUnit.HOURS);

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/ApplicationELB")
                    .metricName("RequestCount")
                    .dimensions(Dimension.builder()
                            .name("LoadBalancer")
                            .value(extractLoadBalancerName(loadBalancerArn))
                            .build())
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(86400) // 24 hours
                    .statistics(Statistic.SUM)
                    .build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                    .mapToDouble(Datapoint::sum)
                    .sum();
        } catch (Exception e) {
            logger.debug("Could not fetch request count for {}", loadBalancerArn, e);
            return 0.0;
        }
    }

    // private List<PerformanceInsightDto> getEBSInsightsForRegion(CloudAccount account, String regionId) {
    //     logger.info("Checking for EBS performance insights in region {}...", regionId);
    //     List<PerformanceInsightDto> insights = new ArrayList<>();
    //     try {
    //         Ec2Client ec2Client = awsDataService.getEc2Client(account.getAwsAccountId(), regionId);
    //         CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(), regionId);

    //         DescribeVolumesResponse volumesResponse = ec2Client
    //                 .describeVolumes(req -> req.filters(f -> f.name("status").values("in-use")));

    //         for (Volume volume : volumesResponse.volumes()) {
    //             double readOps = getSumMetric(cloudWatchClient, "AWS/EBS", "VolumeReadOps", "VolumeId",
    //                     volume.volumeId());
    //             double writeOps = getSumMetric(cloudWatchClient, "AWS/EBS", "VolumeWriteOps", "VolumeId",
    //                     volume.volumeId());

    //             if (readOps < 100 && writeOps < 100) {
    //                 insights.add(new PerformanceInsightDto(
    //                         "ebs-" + volume.volumeId() + "-low-iops",
    //                         "EBS Volume " + volume.volumeId() + " has very low activity.",
    //                         "This volume may be over-provisioned for its workload.",
    //                         PerformanceInsightDto.InsightSeverity.WARNING,
    //                         PerformanceInsightDto.InsightCategory.COST,
    //                         account.getAwsAccountId(), 1, "EBS", volume.volumeId(),
    //                         "Consider switching to a lower-cost volume type (e.g., gp2 to gp3) or reducing provisioned IOPS.",
    //                         "/docs/ebs-optimization",
    //                         calculateEBSSavings(volume),
    //                         regionId, Instant.now().toString()));
    //             }
    //         }
    //     } catch (Exception e) {
    //         logger.error("Error fetching EBS insights for account: {} in region {}", account.getAwsAccountId(),
    //                 regionId, e);
    //     }
    //     return insights;
    // }

    // private List<PerformanceInsightDto> getS3Insights(CloudAccount account) {
    //     logger.info("Checking for all S3 performance insights...");
    //     List<PerformanceInsightDto> insights = new ArrayList<>();
    //     S3Client s3Client = awsClientProvider.getS3Client(account, "us-east-1");
    //     Map<String, String> bucketRegionCache = new HashMap<>();

    //     try {
    //         List<Bucket> buckets = s3Client.listBuckets().buckets();
    //         for (Bucket bucket : buckets) {
    //             try {
    //                 String bucketRegion = getBucketRegion(bucket.name(), s3Client, bucketRegionCache);
    //                 S3Client regionalS3Client = awsClientProvider.getS3Client(account, bucketRegion);
    //                 CloudWatchClient regionalCloudWatchClient = awsClientProvider.getCloudWatchClient(account, bucketRegion);

    //                 try {
    //                     regionalS3Client.getBucketLifecycleConfiguration(r -> r.bucket(bucket.name()));
    //                 } catch (S3Exception e) {
    //                     if ("NoSuchLifecycleConfiguration".equals(e.awsErrorDetails().errorCode())) {
    //                         double bucketSize = getBucketSize(regionalCloudWatchClient, bucket.name());
    //                         if (bucketSize > 1_000_000_000) {
    //                             insights.add(new PerformanceInsightDto(
    //                                     "s3-" + bucket.name() + "-no-lifecycle",
    //                                     "S3 Bucket " + bucket.name() + " does not have a lifecycle policy configured.",
    //                                     "Objects may not be transitioned to more cost-effective storage tiers automatically.",
    //                                     PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
    //                                     PerformanceInsightDto.InsightCategory.COST,
    //                                     account.getAwsAccountId(), 1, "S3", bucket.name(),
    //                                     "Consider adding a lifecycle policy to transition or expire objects.",
    //                                     "/docs/s3-lifecycle",
    //                                     calculateS3Savings(bucketSize), "Global", Instant.now().toString()));
    //                         }
    //                     } else {
    //                         throw e;
    //                     }
    //                 }

    //                 double bucketSize = getBucketSize(regionalCloudWatchClient, bucket.name());
    //                 if (bucketSize > 1000000) {
    //                     insights.add(new PerformanceInsightDto(
    //                             "s3-" + bucket.name() + "-storage-class",
    //                             "Consider S3 Intelligent-Tiering for bucket " + bucket.name(),
    //                             "Potential savings by optimizing storage classes.",
    //                             PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
    //                             PerformanceInsightDto.InsightCategory.COST,
    //                             account.getAwsAccountId(), 1, "S3", bucket.name(),
    //                             "Move objects to Intelligent-Tiering to save on storage costs.",
    //                             "/docs/s3-intelligent-tiering",
    //                             calculateS3Savings(bucketSize), "Global", Instant.now().toString()));
    //                 }

    //             } catch (Exception e) {
    //                 logger.error("Failed to process S3 bucket insights for {}: {}", bucket.name(), e.getMessage());
    //             }
    //         }
    //     } catch (Exception e) {
    //         logger.error("Failed to list S3 buckets for account {}: {}", account.getAwsAccountId(), e);
    //     }
    //     return insights;
    // }

    private String getBucketRegion(String bucketName, S3Client s3Client, Map<String, String> bucketRegionCache) {
        if (bucketRegionCache.containsKey(bucketName)) {
            return bucketRegionCache.get(bucketName);
        }

        String bucketRegion = "us-east-1";
        try {
            GetBucketLocationResponse locationResponse = s3Client.getBucketLocation(
                    GetBucketLocationRequest.builder().bucket(bucketName).build());
            String locationConstraint = locationResponse.locationConstraintAsString();
            if (locationConstraint != null && !locationConstraint.isEmpty()) {
                bucketRegion = locationConstraint;
            }
        } catch (S3Exception e) {
            if (e.awsErrorDetails() != null && e.awsErrorDetails().sdkHttpResponse() != null) {
                Optional<String> regionFromHeader = e.awsErrorDetails().sdkHttpResponse()
                        .firstMatchingHeader("x-amz-bucket-region");
                if (regionFromHeader.isPresent()) {
                    bucketRegion = regionFromHeader.get();
                }
            }
            logger.debug("Could not determine region for bucket {}, using {}: {}",
                    bucketName, bucketRegion, e.awsErrorDetails().errorMessage());
        }
        bucketRegionCache.put(bucketName, bucketRegion);
        return bucketRegion;
    }

    private double getAverageMetric(CloudWatchClient cloudWatchClient, String namespace,
                                    String metricName, String dimensionName, String dimensionValue) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(24, ChronoUnit.HOURS);

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace(namespace)
                    .metricName(metricName)
                    .dimensions(Dimension.builder().name(dimensionName).value(dimensionValue).build())
                    .startTime(startTime).endTime(endTime).period(86400)
                    .statistics(Statistic.AVERAGE).build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                    .mapToDouble(Datapoint::average).average().orElse(0.0);
        } catch (Exception e) {
            logger.debug("Could not fetch metric {} for {}", metricName, dimensionValue, e);
            return 0.0;
        }
    }

    private double getSumMetric(CloudWatchClient cloudWatchClient, String namespace, String metricName,
                                String dimensionName, String dimensionValue) {
        try {
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(24, ChronoUnit.HOURS);

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace(namespace)
                    .metricName(metricName)
                    .dimensions(Dimension.builder().name(dimensionName).value(dimensionValue).build())
                    .startTime(startTime).endTime(endTime).period(86400)
                    .statistics(Statistic.SUM).build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                    .mapToDouble(Datapoint::sum).sum();
        } catch (Exception e) {
            logger.debug("Could not fetch metric {} for {}", metricName, dimensionValue, e);
            return 0.0;
        }
    }

    private double getBucketSize(CloudWatchClient cloudWatchClient, String bucketName) {
        try {
            Instant endTime = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant startTime = endTime.minus(1, ChronoUnit.DAYS);

            GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                    .namespace("AWS/S3")
                    .metricName("BucketSizeBytes")
                    .dimensions(
                            Dimension.builder().name("BucketName").value(bucketName).build(),
                            Dimension.builder().name("StorageType").value("StandardStorage").build())
                    .startTime(startTime)
                    .endTime(endTime)
                    .period(86400)
                    .statistics(Statistic.AVERAGE)
                    .build();

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(request);

            return response.datapoints().stream()
                    .mapToDouble(Datapoint::average)
                    .max()
                    .orElse(0.0);

        } catch (Exception e) {
            logger.debug("Could not fetch bucket size for {}", bucketName);
            return 0.0;
        }
    }

    private double calculateLambdaSavings(FunctionConfiguration function) {
        int memoryMB = function.memorySize();
        return (memoryMB / 128.0) * 2.5;
    }

    private double calculateS3Savings(double sizeBytes) {
        double sizeGB = sizeBytes / (1024 * 1024 * 1024);
        return sizeGB * 0.023 * 0.4;
    }

    private double calculateEC2Savings(String instanceType) {
        Map<String, Double> costs = Map.of(
                "t2.micro", 8.5, "t2.small", 17.0, "t3.medium", 30.0,
                "t3.large", 60.0, "m5.large", 70.0, "m5.xlarge", 140.0);
        return costs.getOrDefault(instanceType, 50.0);
    }

    private double calculateRDSSavings(String instanceClass) {
        Map<String, Double> costs = Map.of(
                "db.t3.micro", 15.0, "db.t3.small", 30.0, "db.t3.medium", 60.0,
                "db.m5.large", 120.0, "db.m5.xlarge", 240.0);
        return costs.getOrDefault(instanceClass, 80.0);
    }

    private double calculateEBSSavings(Volume volume) {
        if (volume.volumeTypeAsString().equals("gp2")) {
            return volume.size() * 0.01;
        }
        if (volume.volumeTypeAsString().equals("io1") || volume.volumeTypeAsString().equals("io2")) {
            return volume.size() * 0.05;
        }
        return volume.size() * 0.02;
    }

    private double calculateALBSavings() {
        return 17.0;
    }

    public Map<String, Object> getInsightsSummary(String accountId) {
        List<PerformanceInsightDto> allInsights = getInsights(accountId, "ALL");
        List<PerformanceInsightDto> insightsForSummary = allInsights.stream()
                .filter(insight -> insight.getCategory() != PerformanceInsightDto.InsightCategory.BEST_PRACTICE)
                .collect(Collectors.toList());

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInsights", insightsForSummary.size());
        summary.put("critical", insightsForSummary.stream().filter(i -> i.getSeverity() == PerformanceInsightDto.InsightSeverity.CRITICAL).count());
        summary.put("warning", insightsForSummary.stream().filter(i -> i.getSeverity() == PerformanceInsightDto.InsightSeverity.WARNING).count());
        summary.put("weakWarning", insightsForSummary.stream().filter(i -> i.getSeverity() == PerformanceInsightDto.InsightSeverity.WEAK_WARNING).count());
        summary.put("potentialSavings", allInsights.stream().mapToDouble(PerformanceInsightDto::getPotentialSavings).sum());
        summary.put("performanceScore", calculatePerformanceScore(allInsights));
        return summary;
    }

    public void archiveInsight(String insightId) {
        archivedInsights.put(insightId, null);
        logger.info("Archived insight: {}", insightId);
    }

    public void bulkArchiveInsights(List<String> insightIds) {
        insightIds.forEach(this::archiveInsight);
        logger.info("Bulk archived {} insights", insightIds.size());
    }

    public void exportInsightsToExcel(String accountId, String severity, HttpServletResponse response) {
        List<PerformanceInsightDto> insights = getInsights(accountId, severity);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Performance Insights");
            Row headerRow = sheet.createRow(0);
            String[] headers = { "Insight", "Severity", "Account", "Quantity", "Resource Type", "Resource ID", "Region", "Recommendation", "Potential Savings" };
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }
            for (int i = 0; i < insights.size(); i++) {
                Row row = sheet.createRow(i + 1);
                PerformanceInsightDto insight = insights.get(i);
                row.createCell(0).setCellValue(insight.getInsight());
                row.createCell(1).setCellValue(insight.getSeverity().toString());
                row.createCell(2).setCellValue(insight.getAccount());
                row.createCell(3).setCellValue(insight.getQuantity());
                row.createCell(4).setCellValue(insight.getResourceType());
                row.createCell(5).setCellValue(insight.getResourceId());
                row.createCell(6).setCellValue(insight.getRegion());
                row.createCell(7).setCellValue(insight.getRecommendation());
                row.createCell(8).setCellValue(insight.getPotentialSavings());
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=performance-insights.xlsx");
            workbook.write(response.getOutputStream());
        } catch (IOException e) {
            logger.error("Error exporting insights to Excel", e);
        }
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}