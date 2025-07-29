package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.PerformanceInsightDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Volume;
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
import java.time.LocalDate; // Added import
import java.time.format.DateTimeFormatter; // Added import
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
public class PerformanceInsightsService {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceInsightsService.class);

    private final AwsDataService awsDataService;
    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final Map<String, PerformanceInsightDto> archivedInsights = new HashMap<>();

    @Autowired
    public PerformanceInsightsService(AwsDataService awsDataService, CloudAccountRepository cloudAccountRepository,
                                      AwsClientProvider awsClientProvider) {
        this.awsDataService = awsDataService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
    }

    /**
     * NEW METHOD
     * Fetches historical daily request counts for all ALBs in an account.
     * @param accountId The AWS Account ID
     * @param days The number of past days to fetch data for.
     * @return A list of maps, formatted for the Prophet forecasting service.
     */
    public List<Map<String, Object>> getDailyRequestCounts(String accountId, int days) {
        List<Map<String, Object>> dailyTotals = new ArrayList<>();
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // Initialize map with all dates to ensure we have entries even for days with zero requests
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("ds", date.format(formatter));
            dayData.put("y", 0.0);
            dailyTotals.add(dayData);
        }

        try {
            List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();

            for (DashboardData.RegionStatus region : activeRegions) {
                ElasticLoadBalancingV2Client elbv2Client = awsClientProvider.getElbv2Client(account, region.getRegionId());
                CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(account, region.getRegionId());

                List<LoadBalancer> loadBalancers = elbv2Client.describeLoadBalancers().loadBalancers();

                for (LoadBalancer lb : loadBalancers) {
                    double requestCount = getRequestCount(cloudWatchClient, lb.loadBalancerArn()); // Uses your existing helper method

                    // This example adds the total 24hr request count to the current day.
                    // A more advanced implementation might fetch daily data points from CloudWatch.
                    Map<String, Object> todayData = dailyTotals.get(dailyTotals.size() - 1);
                    double currentTotal = (double) todayData.get("y");
                    todayData.put("y", currentTotal + requestCount);
                }
            }
        } catch (Exception e) {
            logger.error("Could not fetch historical request counts for account {}", accountId, e);
        }

        return dailyTotals;
    }

    public List<PerformanceInsightDto> getInsights(String accountId, String severity) {
        logger.info("Starting multi-region performance insights scan for account: {}", accountId);
        List<PerformanceInsightDto> allInsights = new ArrayList<>();
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        try {
            List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();
            logger.info("Found {} active regions to scan for performance insights.", activeRegions.size());

            List<CompletableFuture<List<PerformanceInsightDto>>> futures = new ArrayList<>();

            // Process regional insights
            for (DashboardData.RegionStatus region : activeRegions) {
                CompletableFuture<List<PerformanceInsightDto>> regionFuture = CompletableFuture.supplyAsync(() -> {
                    List<PerformanceInsightDto> regionalInsights = new ArrayList<>();
                    regionalInsights.addAll(getEC2InsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getRDSInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getLambdaInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getEBSInsightsForRegion(account, region.getRegionId()));
                    regionalInsights.addAll(getELBInsightsForRegion(account, region.getRegionId()));
                    logger.info("Completed performance insights scan for region: {}", region.getRegionId());
                    regionalInsights.forEach(insight -> insight.setRegion(region.getRegionId()));
                    return regionalInsights;
                });
                futures.add(regionFuture);
            }

            // Correctly add the consolidated S3 insights task
            futures.add(CompletableFuture.supplyAsync(() -> getS3Insights(account)));

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            for (CompletableFuture<List<PerformanceInsightDto>> future : futures) {
                allInsights.addAll(future.get());
            }

            logger.info("Total insights generated across all regions before filtering: {}", allInsights.size());

            if (severity != null && !severity.isEmpty() && !severity.equalsIgnoreCase("ALL")) {
                PerformanceInsightDto.InsightSeverity severityEnum = PerformanceInsightDto.InsightSeverity
                        .valueOf(severity.toUpperCase());
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

    private List<PerformanceInsightDto> getEC2InsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for EC2 performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsDataService.getEc2Client(account.getAwsAccountId(), regionId);
            CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(), regionId);

            var instancesResponse = ec2Client.describeInstances();

            instancesResponse.reservations().forEach(reservation -> {
                reservation.instances().forEach(instance -> {
                    if (instance.state().nameAsString().equals("running")) {
                        double avgCpuUtilization = getAverageMetric(
                                cloudWatchClient, "AWS/EC2", "CPUUtilization",
                                "InstanceId", instance.instanceId());

                        if (avgCpuUtilization < 10.0) {
                            insights.add(new PerformanceInsightDto(
                                    "ec2-" + instance.instanceId() + "-underutilized",
                                    "EC2 instance " + instance.instanceId() + " is underutilized with " +
                                            String.format("%.1f", avgCpuUtilization) + "% average CPU utilization",
                                    "EC2 instance showing low resource utilization",
                                    avgCpuUtilization < 5.0 ? PerformanceInsightDto.InsightSeverity.CRITICAL
                                            : PerformanceInsightDto.InsightSeverity.WARNING,
                                    account.getAwsAccountId(), 1, "EC2", instance.instanceId(),
                                    "Consider downsizing or terminating this instance", "/docs/ec2-rightsizing",
                                    calculateEC2Savings(instance.instanceType().toString()),
                                    regionId, Instant.now().toString()));
                        }

                        String instanceType = instance.instanceType().toString();
                        if (instanceType.startsWith("t2.")) {
                            insights.add(new PerformanceInsightDto(
                                    "ec2-" + instance.instanceId() + "-generation",
                                    "EC2 instance " + instance.instanceId()
                                            + " is using a previous generation instance type (" + instanceType + ").",
                                    "An older instance family is in use.",
                                    PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
                                    account.getAwsAccountId(), 1, "EC2", instance.instanceId(),
                                    "Upgrade to a newer instance family (e.g., t3) for better price-performance.",
                                    "/docs/ec2-generations",
                                    calculateEC2Savings(instanceType) * 0.1,
                                    regionId, Instant.now().toString()));
                        }
                    }
                });
            });
        } catch (Exception e) {
            logger.error("Error fetching EC2 insights for account: {} in region {}", account.getAwsAccountId(),
                    regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getRDSInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for RDS performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            RdsClient rdsClient = awsClientProvider.getRdsClient(account, regionId);
            CloudWatchClient cloudWatchClient = awsClientProvider.getCloudWatchClient(account, regionId);

            var dbInstancesResponse = rdsClient.describeDBInstances();

            for (DBInstance dbInstance : dbInstancesResponse.dbInstances()) {
                if (dbInstance.dbInstanceStatus().equals("available")) {
                    double avgCpuUtilization = getAverageMetric(
                            cloudWatchClient, "AWS/RDS", "CPUUtilization",
                            "DBInstanceIdentifier", dbInstance.dbInstanceIdentifier());

                    double avgConnections = getAverageMetric(
                            cloudWatchClient, "AWS/RDS", "DatabaseConnections",
                            "DBInstanceIdentifier", dbInstance.dbInstanceIdentifier());

                    if (avgCpuUtilization < 20.0 && avgConnections < 5.0) {
                        insights.add(new PerformanceInsightDto(
                                "rds-" + dbInstance.dbInstanceIdentifier() + "-underutilized",
                                "RDS instance " + dbInstance.dbInstanceIdentifier() +
                                        " shows low utilization with " + String.format("%.1f", avgCpuUtilization) +
                                        "% CPU and " + String.format("%.0f", avgConnections) + " average connections",
                                "RDS instance showing low resource utilization",
                                PerformanceInsightDto.InsightSeverity.WARNING,
                                account.getAwsAccountId(), 1, "RDS", dbInstance.dbInstanceIdentifier(),
                                "Consider downsizing this RDS instance class", "/docs/rds-rightsizing",
                                calculateRDSSavings(dbInstance.dbInstanceClass()),
                                regionId, Instant.now().toString()));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching RDS insights for account: {} in region {}", account.getAwsAccountId(),
                    regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getLambdaInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for Lambda performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            LambdaClient lambdaClient = awsClientProvider.getLambdaClient(account, regionId);
            var functionsResponse = lambdaClient.listFunctions();

            for (FunctionConfiguration function : functionsResponse.functions()) {
                var versionsResponse = lambdaClient.listVersionsByFunction(
                        builder -> builder.functionName(function.functionName()));

                long activeVersions = versionsResponse.versions().stream()
                        .filter(v -> !v.version().equals("$LATEST"))
                        .count();

                if (activeVersions > 0) {
                    insights.add(new PerformanceInsightDto(
                            "lambda-" + function.functionName() + "-versions",
                            "Versions of the " + function.functionName()
                                    + " lambda function is not used according to the best practices",
                            "Lambda function versions are not optimized for best practices",
                            PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
                            account.getAwsAccountId(), 1, "Lambda", function.functionName(),
                            "Consider using aliases to use this version according to the best practices",
                            "/docs/lambda-best-practices",
                            calculateLambdaSavings(function), regionId, Instant.now().toString()));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching Lambda insights for account: {} in region {}", account.getAwsAccountId(),
                    regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getELBInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for ELB performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            ElasticLoadBalancingV2Client elbv2Client = awsClientProvider.getElbv2Client(account, regionId);
            CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(), regionId);

            var loadBalancersResponse = elbv2Client.describeLoadBalancers();

            for (LoadBalancer lb : loadBalancersResponse.loadBalancers()) {
                // Enhanced P95 latency check
                double p95Latency1h = getP95LatencyForALB(cloudWatchClient, lb.loadBalancerArn(), 1);

                // High latency insights
                if (p95Latency1h > 2000) { // > 2 seconds
                    insights.add(new PerformanceInsightDto(
                            "alb-" + lb.loadBalancerName() + "-high-latency",
                            "Application Load Balancer " + lb.loadBalancerName() +
                                    " has high P95 latency: " + String.format("%.0f", p95Latency1h) + "ms",
                            "High response times may indicate performance issues",
                            p95Latency1h > 5000 ? PerformanceInsightDto.InsightSeverity.CRITICAL
                                    : PerformanceInsightDto.InsightSeverity.WARNING,
                            account.getAwsAccountId(), 1, "ALB", lb.loadBalancerName(),
                            "Investigate target health, scaling policies, and application performance",
                            "/docs/alb-performance-optimization",
                            0.0, // No direct cost savings, but performance impact
                            regionId, Instant.now().toString()));
                }

                // Request count analysis
                double requestCount = getRequestCount(cloudWatchClient, lb.loadBalancerArn());
                if (requestCount < 10) { // Very low traffic
                    insights.add(new PerformanceInsightDto(
                            "alb-" + lb.loadBalancerName() + "-low-traffic",
                            "Application Load Balancer " + lb.loadBalancerName() +
                                    " has very low traffic: " + String.format("%.0f", requestCount) + " requests/day",
                            "Load balancer with minimal traffic may be unnecessary",
                            PerformanceInsightDto.InsightSeverity.WARNING,
                            account.getAwsAccountId(), 1, "ALB", lb.loadBalancerName(),
                            "Consider consolidating with other load balancers or removing if not needed",
                            "/docs/alb-cost-optimization",
                            calculateALBSavings(),
                            regionId, Instant.now().toString()));
                }

                // Target group health check
                var targetGroupsResponse = elbv2Client
                        .describeTargetGroups(req -> req.loadBalancerArn(lb.loadBalancerArn()));

                for (TargetGroup tg : targetGroupsResponse.targetGroups()) {
                    var targetHealthResponse = elbv2Client
                            .describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn()));

                    long healthyTargets = targetHealthResponse.targetHealthDescriptions().stream()
                            .filter(th -> th.targetHealth().state() == TargetHealthStateEnum.HEALTHY)
                            .count();

                    if (healthyTargets == 0 && !targetHealthResponse.targetHealthDescriptions().isEmpty()) {
                        insights.add(new PerformanceInsightDto(
                                "elb-" + lb.loadBalancerName() + "-no-healthy-hosts",
                                "Load Balancer " + lb.loadBalancerName() + " has no healthy targets in target group "
                                        + tg.targetGroupName() + ".",
                                "This load balancer is not currently serving traffic to any healthy instances.",
                                PerformanceInsightDto.InsightSeverity.CRITICAL,
                                account.getAwsAccountId(), 1, "ELB", lb.loadBalancerName(),
                                "Check the health check settings for the target group and the status of the registered instances.",
                                "/docs/elb-troubleshooting",
                                17.0,
                                regionId, Instant.now().toString()));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching ELB insights for account: {} in region {}", account.getAwsAccountId(),
                    regionId, e);
        }
        return insights;
    }

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

    private List<PerformanceInsightDto> getEBSInsightsForRegion(CloudAccount account, String regionId) {
        logger.info("Checking for EBS performance insights in region {}...", regionId);
        List<PerformanceInsightDto> insights = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsDataService.getEc2Client(account.getAwsAccountId(), regionId);
            CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(), regionId);

            DescribeVolumesResponse volumesResponse = ec2Client
                    .describeVolumes(req -> req.filters(f -> f.name("status").values("in-use")));

            for (Volume volume : volumesResponse.volumes()) {
                double readOps = getSumMetric(cloudWatchClient, "AWS/EBS", "VolumeReadOps", "VolumeId",
                        volume.volumeId());
                double writeOps = getSumMetric(cloudWatchClient, "AWS/EBS", "VolumeWriteOps", "VolumeId",
                        volume.volumeId());

                if (readOps < 100 && writeOps < 100) {
                    insights.add(new PerformanceInsightDto(
                            "ebs-" + volume.volumeId() + "-low-iops",
                            "EBS Volume " + volume.volumeId() + " has very low activity.",
                            "This volume may be over-provisioned for its workload.",
                            PerformanceInsightDto.InsightSeverity.WARNING,
                            account.getAwsAccountId(), 1, "EBS", volume.volumeId(),
                            "Consider switching to a lower-cost volume type (e.g., gp2 to gp3) or reducing provisioned IOPS.",
                            "/docs/ebs-optimization",
                            calculateEBSSavings(volume),
                            regionId, Instant.now().toString()));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching EBS insights for account: {} in region {}", account.getAwsAccountId(),
                    regionId, e);
        }
        return insights;
    }

    private List<PerformanceInsightDto> getS3Insights(CloudAccount account) {
        logger.info("Checking for all S3 performance insights...");
        List<PerformanceInsightDto> insights = new ArrayList<>();
        S3Client s3Client = awsClientProvider.getS3Client(account, "us-east-1");
        Map<String, String> bucketRegionCache = new HashMap<>();

        try {
            List<Bucket> buckets = s3Client.listBuckets().buckets();
            for (Bucket bucket : buckets) {
                try {
                    String bucketRegion = getBucketRegion(bucket.name(), s3Client, bucketRegionCache);
                    S3Client regionalS3Client = awsClientProvider.getS3Client(account, bucketRegion);
                    CloudWatchClient regionalCloudWatchClient = awsClientProvider.getCloudWatchClient(account, bucketRegion);

                    try {
                        regionalS3Client.getBucketLifecycleConfiguration(r -> r.bucket(bucket.name()));
                    } catch (S3Exception e) {
                        if ("NoSuchLifecycleConfiguration".equals(e.awsErrorDetails().errorCode())) {
                            double bucketSize = getBucketSize(regionalCloudWatchClient, bucket.name());
                            if (bucketSize > 1_000_000_000) {
                                insights.add(new PerformanceInsightDto(
                                        "s3-" + bucket.name() + "-no-lifecycle",
                                        "S3 Bucket " + bucket.name() + " does not have a lifecycle policy configured.",
                                        "Objects may not be transitioned to more cost-effective storage tiers automatically.",
                                        PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
                                        account.getAwsAccountId(), 1, "S3", bucket.name(),
                                        "Consider adding a lifecycle policy to transition or expire objects.",
                                        "/docs/s3-lifecycle",
                                        calculateS3Savings(bucketSize), "Global", Instant.now().toString()));
                            }
                        } else {
                            throw e;
                        }
                    }

                    double bucketSize = getBucketSize(regionalCloudWatchClient, bucket.name());
                    if (bucketSize > 1000000) {
                        insights.add(new PerformanceInsightDto(
                                "s3-" + bucket.name() + "-storage-class",
                                "Consider S3 Intelligent-Tiering for bucket " + bucket.name(),
                                "Potential savings by optimizing storage classes.",
                                PerformanceInsightDto.InsightSeverity.WEAK_WARNING,
                                account.getAwsAccountId(), 1, "S3", bucket.name(),
                                "Move objects to Intelligent-Tiering to save on storage costs.",
                                "/docs/s3-intelligent-tiering",
                                calculateS3Savings(bucketSize), "Global", Instant.now().toString()));
                    }

                } catch (Exception e) {
                    logger.error("Failed to process S3 bucket insights for {}: {}", bucket.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to list S3 buckets for account {}: {}", account.getAwsAccountId(), e);
        }
        return insights;
    }

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

    public Map<String, Object> getALBPerformanceMetrics(String accountId, String region) {
        Map<String, Object> metrics = new HashMap<>();
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        try {
            List<Double> allP95Latencies = new ArrayList<>();
            List<Double> allRequestCounts = new ArrayList<>();

            List<String> regionsToCheck = new ArrayList<>();
            if (region != null && !region.isEmpty()) {
                regionsToCheck.add(region);
            } else {
                List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account)
                        .get();
                regionsToCheck = activeRegions.stream().map(DashboardData.RegionStatus::getRegionId)
                        .collect(Collectors.toList());
            }

            for (String regionId : regionsToCheck) {
                try {
                    ElasticLoadBalancingV2Client elbv2Client = awsClientProvider.getElbv2Client(account, regionId);
                    CloudWatchClient cloudWatchClient = awsDataService.getCloudWatchClient(account.getAwsAccountId(),
                            regionId);

                    var loadBalancersResponse = elbv2Client.describeLoadBalancers();

                    for (LoadBalancer lb : loadBalancersResponse.loadBalancers()) {
                        double p95Latency1h = getP95LatencyForALB(cloudWatchClient, lb.loadBalancerArn(), 1);
                        double requestCount = getRequestCount(cloudWatchClient, lb.loadBalancerArn());

                        if (p95Latency1h > 0) {
                            allP95Latencies.add(p95Latency1h);
                        }
                        if (requestCount > 0) {
                            allRequestCounts.add(requestCount);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get ALB metrics for region: {}", regionId, e);
                }
            }

            double avgP95Latency = allP95Latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double maxP95Latency = allP95Latencies.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double totalRequests = allRequestCounts.stream().mapToDouble(Double::doubleValue).sum();

            metrics.put("avgP95Latency", Math.round(avgP95Latency));
            metrics.put("maxP95Latency", Math.round(maxP95Latency));
            metrics.put("totalRequests", Math.round(totalRequests));
            metrics.put("albCount", allP95Latencies.size());

            return metrics;

        } catch (Exception e) {
            logger.error("Error fetching ALB performance metrics for account: {}", accountId, e);
            return Map.of(
                    "avgP95Latency", 0.0,
                    "maxP95Latency", 0.0,
                    "totalRequests", 0.0,
                    "albCount", 0);
        }
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
        List<PerformanceInsightDto> insights = getInsights(accountId, "ALL");

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalInsights", insights.size());
        summary.put("critical", insights.stream()
                .mapToInt(i -> i.getSeverity() == PerformanceInsightDto.InsightSeverity.CRITICAL ? 1 : 0).sum());
        summary.put("warning", insights.stream()
                .mapToInt(i -> i.getSeverity() == PerformanceInsightDto.InsightSeverity.WARNING ? 1 : 0).sum());
        summary.put("weakWarning", insights.stream()
                .mapToInt(i -> i.getSeverity() == PerformanceInsightDto.InsightSeverity.WEAK_WARNING ? 1 : 0).sum());
        summary.put("potentialSavings", insights.stream()
                .mapToDouble(PerformanceInsightDto::getPotentialSavings).sum());

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
            String[] headers = { "Insight", "Severity", "Account", "Quantity", "Resource Type", "Resource ID", "Region",
                    "Recommendation", "Potential Savings" };
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(createHeaderStyle(workbook));
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
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=performance-insights-" + accountId + "-"
                    + System.currentTimeMillis() + ".xlsx");
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