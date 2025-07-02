// in cloud/src/main/java/com/xammer/cloud/service/AwsDataService.java

package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.DashboardData.ServiceGroupDto;
import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.dto.ResourceDto;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.*;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.PasswordPolicy;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class AwsDataService {

    private static final Logger logger = LoggerFactory.getLogger(AwsDataService.class);

    private final Ec2Client ec2Client;
    private final IamClient iamClient;
    private final EcsClient ecsClient;
    private final EksClient eksClient;
    private final LambdaClient lambdaClient;
    private final CloudWatchClient cloudWatchClient;
    private final CostExplorerClient costExplorerClient;
    private final ComputeOptimizerClient computeOptimizerClient;
    private final PricingService pricingService;
    private final RdsClient rdsClient;
    private final S3Client s3Client;
    private final ElasticLoadBalancingV2Client elbv2Client;
    private final AutoScalingClient autoScalingClient;
    private final ElastiCacheClient elastiCacheClient;
    private final DynamoDbClient dynamoDbClient;
    private final EcrClient ecrClient;
    private final Route53Client route53Client;
    private final CloudTrailClient cloudTrailClient;
    private final AcmClient acmClient;
    private final CloudWatchLogsClient cloudWatchLogsClient;
    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final String configuredRegion;

    private static final Set<String> SUSTAINABLE_REGIONS = Set.of("us-east-1", "us-west-2", "eu-west-1", "eu-central-1",
            "ca-central-1");

    private static final Map<String, double[]> REGION_GEO = Map.ofEntries(
            Map.entry("us-east-1", new double[]{38.8951, -77.0364}),
            Map.entry("us-east-2", new double[]{40.0, -83.0}),
            Map.entry("us-west-1", new double[]{37.3541, -121.9552}),
            Map.entry("us-west-2", new double[]{45.5231, -122.6765}),
            Map.entry("ca-central-1", new double[]{45.4112, -75.6984}),
            Map.entry("eu-west-1", new double[]{53.3498, -6.2603}),
            Map.entry("eu-west-2", new double[]{51.5074, -0.1278}),
            Map.entry("eu-west-3", new double[]{48.8566, 2.3522}),
            Map.entry("eu-central-1", new double[]{50.1109, 8.6821}),
            Map.entry("eu-north-1", new double[]{59.3293, 18.0686}),
            Map.entry("ap-southeast-1", new double[]{1.3521, 103.8198}),
            Map.entry("ap-southeast-2", new double[]{-33.8688, 151.2093}),
            Map.entry("ap-northeast-1", new double[]{35.6895, 139.6917}),
            Map.entry("ap-northeast-2", new double[]{37.5665, 126.9780}),
            Map.entry("ap-northeast-3", new double[]{34.6937, 135.5023}),
            Map.entry("ap-south-1", new double[]{19.0760, 72.8777}),
            Map.entry("sa-east-1", new double[]{-23.5505, -46.6333}));

    public AwsDataService(Ec2Client ec2, IamClient iam, EcsClient ecs, EksClient eks, LambdaClient lambda,
                          CloudWatchClient cw, CostExplorerClient ce, ComputeOptimizerClient co,
                          PricingService pricingService, RdsClient rdsClient, S3Client s3Client,
                          ElasticLoadBalancingV2Client elbv2Client, AutoScalingClient autoScalingClient,
                          ElastiCacheClient elastiCacheClient, DynamoDbClient dynamoDbClient, EcrClient ecrClient,
                          Route53Client route53Client, CloudTrailClient cloudTrailClient, AcmClient acmClient,
                          CloudWatchLogsClient cloudWatchLogsClient, SnsClient snsClient, SqsClient sqsClient) {
        this.ec2Client = ec2;
        this.iamClient = iam;
        this.ecsClient = ecs;
        this.eksClient = eks;
        this.lambdaClient = lambda;
        this.cloudWatchClient = cw;
        this.costExplorerClient = ce;
        this.computeOptimizerClient = co;
        this.pricingService = pricingService;
        this.rdsClient = rdsClient;
        this.s3Client = s3Client;
        this.elbv2Client = elbv2Client;
        this.autoScalingClient = autoScalingClient;
        this.elastiCacheClient = elastiCacheClient;
        this.dynamoDbClient = dynamoDbClient;
        this.ecrClient = ecrClient;
        this.route53Client = route53Client;
        this.cloudTrailClient = cloudTrailClient;
        this.acmClient = acmClient;
        this.cloudWatchLogsClient = cloudWatchLogsClient;
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        // Set the region explicitly or via configuration
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }
    
    public DashboardData getDashboardData() throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING ASYNC DATA FETCH FROM AWS ---");

        CompletableFuture<List<DashboardData.RegionStatus>> regionStatusFuture = getRegionStatusForAccount();
        CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory();
        CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus();
        CompletableFuture<List<DashboardData.SecurityInsight>> insightsFuture = getSecurityInsights();
        CompletableFuture<DashboardData.CostHistory> costHistoryFuture = getCostHistory();
        CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = getBillingSummary();
        CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources();
        CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary();
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = getEc2InstanceRecommendations();
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = getCostAnomalies();
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = getEbsVolumeRecommendations();
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = getLambdaFunctionRecommendations();
        CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = getReservationAnalysis();
        CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = getReservationPurchaseRecommendations();

        CompletableFuture.allOf(regionStatusFuture, inventoryFuture, cwStatusFuture, insightsFuture,
                costHistoryFuture, billingFuture, iamFuture, savingsFuture,
                ec2RecsFuture, anomaliesFuture, ebsRecsFuture,
                lambdaRecsFuture, reservationFuture, reservationPurchaseFuture).join();

        logger.info("--- ALL ASYNC DATA FETCHES COMPLETE ---");

        List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.get();
        List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.get();
        List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.get();
        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.get();

        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(ec2Recs, ebsRecs, lambdaRecs,
                anomalies);

        DashboardData data = new DashboardData();
        DashboardData.Account mainAccount = new DashboardData.Account(
                "123456789012", "MachaDalo",
                regionStatusFuture.get(), inventoryFuture.get(), cwStatusFuture.get(), insightsFuture.get(),
                costHistoryFuture.get(), billingFuture.get(), iamFuture.get(), savingsFuture.get(),
                ec2Recs, anomalies, ebsRecs,
                lambdaRecs, reservationFuture.get(), reservationPurchaseFuture.get(),
                optimizationSummary,
                null);

        data.setAvailableAccounts(
                List.of(mainAccount, new DashboardData.Account("987654321098", "Xammer", new ArrayList<>(), null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null)));
        data.setSelectedAccount(mainAccount);
        return data;
    }


    /**
     * Checks if a given AWS region has any active resources across a range of services.
     * This method is designed to be efficient, returning true as soon as the first resource is found.
     *
     * @param region The AWS region to check.
     * @return true if any resource (EC2, EBS, RDS, Lambda, ECS) is found, false otherwise.
     */
    private boolean isRegionActive(software.amazon.awssdk.regions.Region region) {
        logger.debug("Performing activity check for region: {}", region.id());
        try {
            // Check for EC2 Instances (any state) or EBS Volumes
            Ec2Client regionEc2 = Ec2Client.builder().region(region).build();
            if (regionEc2.describeInstances().hasReservations() && !regionEc2.describeInstances().reservations().isEmpty()) return true;
            if (regionEc2.describeVolumes().hasVolumes() && !regionEc2.describeVolumes().volumes().isEmpty()) return true;

            // Check for RDS Instances
            RdsClient regionRds = RdsClient.builder().region(region).build();
            if (regionRds.describeDBInstances().hasDbInstances() && !regionRds.describeDBInstances().dbInstances().isEmpty()) return true;

            // Check for Lambda Functions
            LambdaClient regionLambda = LambdaClient.builder().region(region).build();
            if (regionLambda.listFunctions().hasFunctions() && !regionLambda.listFunctions().functions().isEmpty()) return true;

            // Check for ECS Clusters
            EcsClient regionEcs = EcsClient.builder().region(region).build();
            if (regionEcs.listClusters().hasClusterArns() && !regionEcs.listClusters().clusterArns().isEmpty()) return true;

        } catch (AwsServiceException | SdkClientException e) {
            // This can happen if a region is not enabled for the account. It's safe to ignore.
            logger.warn("Could not perform active check for region {}: {}. Assuming inactive.", region.id(), e.getMessage());
            return false;
        }

        logger.debug("No activity found in region: {}", region.id());
        return false;
    }


    @Async("awsTaskExecutor")
    @Cacheable("regionStatus")
    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForAccount() {
        logger.info("Fetching status for all available and active AWS regions...");
        try {
            // Get all regions the account can access, regardless of opt-in status initially.
            List<Region> allRegions = ec2Client.describeRegions().regions();
            logger.info("Found {} total regions available to the account. Now checking for activity.", allRegions.size());

            return CompletableFuture.completedFuture(
                allRegions.parallelStream() // Use parallel stream for faster checking
                    .filter(region -> !"not-opted-in".equals(region.optInStatus())) // Filter out non-opted-in regions
                    .filter(region -> {
                        // Ensure the region has geographic coordinates defined for map plotting
                        if (!REGION_GEO.containsKey(region.regionName())) {
                            logger.warn("Region {} is available but has no geographic coordinates defined. It will be excluded from the map.", region.regionName());
                            return false;
                        }
                        return true;
                    })
                    .filter(region -> isRegionActive(software.amazon.awssdk.regions.Region.of(region.regionName()))) // Check for any active resources
                    .map(this::mapRegionToStatus) // Convert active regions to DTOs
                    .collect(Collectors.toList())
            );
        } catch (Exception e) {
            logger.error("Could not fetch and process AWS regions.", e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }


    private DashboardData.RegionStatus mapRegionToStatus(Region region) {
        double[] coords = REGION_GEO.get(region.regionName());
        String status = "ACTIVE"; // If it passed the filter, it's active

        if (SUSTAINABLE_REGIONS.contains(region.regionName())) {
            status = "SUSTAINABLE"; // Override status if it's also a sustainable region
        }

        return new DashboardData.RegionStatus(region.regionName(), region.regionName(), status, coords[0], coords[1]);
    }

    @Async("awsTaskExecutor")
    @Cacheable("allRecommendations")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getAllOptimizationRecommendations() {
        logger.info("Fetching all optimization recommendations (EC2, EBS, Lambda)...");

        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = getEc2InstanceRecommendations();
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = getEbsVolumeRecommendations();
        CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = getLambdaFunctionRecommendations();

        return CompletableFuture.allOf(ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture)
                .thenApply(v -> Stream.of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }

    @Async("awsTaskExecutor")
    @Cacheable("cloudlistResources")
    public CompletableFuture<List<ResourceDto>> getAllResources() {
        logger.info("Fetching all resources for Cloudlist (flat list)...");

        List<CompletableFuture<List<ResourceDto>>> resourceFutures = List.of(
                fetchEc2InstancesForCloudlist(),
                fetchEbsVolumesForCloudlist(),
                fetchRdsInstancesForCloudlist(),
                fetchLambdaFunctionsForCloudlist(),
                fetchVpcsForCloudlist(),
                fetchSecurityGroupsForCloudlist(),
                fetchS3BucketsForCloudlist(),
                fetchLoadBalancersForCloudlist(),
                fetchAutoScalingGroupsForCloudlist(),
                fetchElastiCacheClustersForCloudlist(),
                fetchDynamoDbTablesForCloudlist(),
                fetchEcrRepositoriesForCloudlist(),
                fetchRoute53HostedZonesForCloudlist(),
                fetchCloudTrailsForCloudlist(),
                fetchAcmCertificatesForCloudlist(),
                fetchCloudWatchLogGroupsForCloudlist(),
                fetchSnsTopicsForCloudlist(),
                fetchSqsQueuesForCloudlist()
        );

        return CompletableFuture.allOf(resourceFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> resourceFutures.stream()
                        .map(future -> future.getNow(Collections.emptyList()))
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList()));
    }

        @CacheEvict(value = {
                "cloudlistResources", "groupedCloudlistResources", // Add the new cache to the evict list
                "wastedResources", "regionStatus", "inventory",
                "cloudwatchStatus", "securityInsights", "ec2Recs", "costAnomalies",
                "ebsRecs", "lambdaRecs", "reservationAnalysis", "reservationPurchaseRecs",
                "billingSummary", "iamResources", "costHistory", "allRecommendations"
        }, allEntries = true)
    public void clearAllCaches() {
        logger.info("All dashboard caches have been evicted.");
    }

        @Async("awsTaskExecutor")
    @Cacheable("groupedCloudlistResources") // Use a new cache name
    public CompletableFuture<List<ServiceGroupDto>> getAllResourcesGrouped() {
        logger.info("Fetching and grouping all resources for Cloudlist...");

        // Call the original method that returns a flat list
        return getAllResources().thenApply(flatResourceList -> {
            logger.info("Grouping {} resources by service type...", flatResourceList.size());

            // Group the flat list of resources by their 'type' property
            Map<String, List<ResourceDto>> groupedByType = flatResourceList.stream()
                    .collect(Collectors.groupingBy(ResourceDto::getType));

            // Convert the grouped map into a list of ServiceGroupDto objects
            return groupedByType.entrySet().stream()
                    .map(entry -> new ServiceGroupDto(entry.getKey(), entry.getValue()))
                     // Optional: Sort the groups alphabetically by service type
                    .sorted((g1, g2) -> g1.getServiceType().compareTo(g2.getServiceType()))
                    .collect(Collectors.toList());
        });
    }


    
    private CompletableFuture<List<ResourceDto>> fetchEc2InstancesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching EC2 Instances...");
                return ec2Client.describeInstances().reservations().stream()
                        .flatMap(r -> r.instances().stream())
                        .map(i -> new ResourceDto(
                                i.instanceId(),
                                getTagName(i.tags(), "N/A"),
                                "EC2 Instance",
                                i.placement().availabilityZone().replaceAll(".$", ""),
                                i.state().nameAsString(),
                                i.launchTime(),
                                Map.of(
                                        "Type", i.instanceTypeAsString(),
                                        "Image ID", i.imageId(),
                                        "VPC ID", i.vpcId(),
                                        "Private IP", i.privateIpAddress())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: EC2 instances.", e);
                return Collections.emptyList();
            }
        });
    }

        private CompletableFuture<List<ResourceDto>> fetchCloudTrailsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching CloudTrails...");
                return cloudTrailClient.describeTrails().trailList().stream()
                        .map(t -> new ResourceDto(
                                t.trailARN(),
                                t.name(),
                                "CloudTrail",
                                t.homeRegion(),
                                "Active", // Trails are generally just active
                                null, // Creation time not in this response
                                Map.of(
                                        "IsMultiRegion", t.isMultiRegionTrail().toString(),
                                        "S3Bucket", t.s3BucketName()
                                )))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: CloudTrails.", e);
                return Collections.emptyList();
            }
        });
    }

        private CompletableFuture<List<ResourceDto>> fetchAcmCertificatesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching ACM Certificates...");
                return acmClient.listCertificates().certificateSummaryList().stream()
                        .map(c -> new ResourceDto(
                                c.certificateArn(),
                                c.domainName(),
                                "Certificate Manager",
                                "Global", // ACM certs in us-east-1 are global for CloudFront
                                c.statusAsString(),
                                c.createdAt(),
                                Map.of(
                                        "Type", c.typeAsString(),
                                        "InUse", c.inUse().toString()
                                )))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: ACM Certificates.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchCloudWatchLogGroupsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching CloudWatch Log Groups...");
                return cloudWatchLogsClient.describeLogGroups().logGroups().stream()
                        .map(lg -> new ResourceDto(
                                lg.arn(),
                                lg.logGroupName(),
                                "CloudWatch Log Group",
                                getRegionFromArn(lg.arn()), // CORRECTED
                                "Active",
                                Instant.ofEpochMilli(lg.creationTime()),
                                Map.of(
                                        "Retention (Days)", lg.retentionInDays() != null ? lg.retentionInDays().toString() : "Never Expire",
                                        "Stored Bytes", String.format("%,d", lg.storedBytes())
                                )))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: CloudWatch Log Groups.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchEbsVolumesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching EBS Volumes...");
                return ec2Client.describeVolumes().volumes().stream()
                        .map(v -> new ResourceDto(
                                v.volumeId(),
                                getTagName(v.tags(), "N/A"),
                                "EBS Volume",
                                v.availabilityZone().replaceAll(".$", ""),
                                v.stateAsString(),
                                v.createTime(),
                                Map.of(
                                        "Size", v.size() + " GiB",
                                        "Type", v.volumeTypeAsString(),
                                        "Attached to",
                                        v.attachments().isEmpty() ? "N/A" : v.attachments().get(0).instanceId())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: EBS volumes. Error: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchRdsInstancesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching RDS Instances...");
                return rdsClient.describeDBInstances().dbInstances().stream()
                        .map(i -> new ResourceDto(
                                i.dbInstanceIdentifier(),
                                i.dbInstanceIdentifier(),
                                "RDS Instance",
                                i.availabilityZone().replaceAll(".$", ""),
                                i.dbInstanceStatus(),
                                i.instanceCreateTime(),
                                Map.of(
                                        "Engine", i.engine() + " " + i.engineVersion(),
                                        "Class", i.dbInstanceClass(),
                                        "Multi-AZ", i.multiAZ().toString())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: RDS Instances.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchLambdaFunctionsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching Lambda Functions...");
                return lambdaClient.listFunctions().functions().stream()
                        .map(f -> {
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                            Instant lastModified = ZonedDateTime.parse(f.lastModified(), formatter).toInstant();

                            return new ResourceDto(
                                    f.functionName(),
                                    f.functionName(),
                                    "Lambda Function",
                                    getRegionFromArn(f.functionArn()), // CORRECTED
                                    "Active",
                                    lastModified,
                                    Map.of(
                                            "Runtime", f.runtimeAsString(),
                                            "Memory", f.memorySize() + " MB",
                                            "Timeout", f.timeout() + "s"));
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: Lambda Functions.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchVpcsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching VPCs...");
                return ec2Client.describeVpcs().vpcs().stream()
                        .map(v -> new ResourceDto(
                                v.vpcId(),
                                getTagName(v.tags(), v.vpcId()),
                                "VPC",
                                this.configuredRegion, // CORRECTED
                                v.stateAsString(),
                                null,
                                Map.of(
                                        "CIDR Block", v.cidrBlock(),
                                        "Is Default", v.isDefault().toString())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: VPCs. Error: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchSecurityGroupsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching Security Groups...");
                return ec2Client.describeSecurityGroups().securityGroups().stream()
                        .map(sg -> new ResourceDto(
                                sg.groupId(),
                                sg.groupName(),
                                "Security Group",
                                this.configuredRegion, // CORRECTED
                                "Available",
                                null,
                                Map.of(
                                        "VPC ID", sg.vpcId(),
                                        "Inbound Rules", String.valueOf(sg.ipPermissions().size()),
                                        "Outbound Rules", String.valueOf(sg.ipPermissionsEgress().size()))))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: Security Groups. Error: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }


     private CompletableFuture<List<ResourceDto>> fetchS3BucketsForCloudlist() {
         return CompletableFuture.supplyAsync(() -> {
             try {
                 logger.info("Cloudlist: Fetching S3 Buckets...");
                 return s3Client.listBuckets().buckets().stream()
                         .map(b -> {
                             // CORRECTED: Fetch the actual bucket location
                             String bucketRegion = "us-east-1"; // Default for older buckets
                             try {
                                 bucketRegion = s3Client.getBucketLocation(req -> req.bucket(b.name())).locationConstraintAsString();
                                 if (bucketRegion == null || bucketRegion.isEmpty()) {
                                     bucketRegion = "us-east-1"; // Null or empty response also means us-east-1
                                 }
                             } catch (Exception e) {
                                 logger.warn("Could not get location for bucket {}: {}", b.name(), e.getMessage());
                             }
                             return new ResourceDto(
                                     b.name(), b.name(), "S3 Bucket", bucketRegion, "Available", b.creationDate(),
                                     Collections.emptyMap());
                         })
                         .collect(Collectors.toList());
             } catch (Exception e) {
                 logger.error("Cloudlist sub-task failed: S3 Buckets.", e);
                 return Collections.emptyList();
             }
         });
     }
    private CompletableFuture<List<ResourceDto>> fetchLoadBalancersForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching Load Balancers...");
                return elbv2Client.describeLoadBalancers().loadBalancers().stream()
                        .map(lb -> new ResourceDto(
                                lb.loadBalancerName(),
                                lb.loadBalancerName(),
                                "Load Balancer",
                                lb.availabilityZones().get(0).zoneName().replaceAll(".$", ""),
                                lb.state().codeAsString(),
                                lb.createdTime(),
                                Map.of(
                                        "Type", lb.typeAsString(),
                                        "Scheme", lb.schemeAsString(),
                                        "VPC ID", lb.vpcId())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: Load Balancers.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchAutoScalingGroupsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching Auto Scaling Groups...");
                return autoScalingClient.describeAutoScalingGroups().autoScalingGroups().stream()
                        .map(asg -> new ResourceDto(
                                asg.autoScalingGroupName(),
                                asg.autoScalingGroupName(),
                                "Auto Scaling Group",
                                asg.availabilityZones().get(0).replaceAll(".$", ""),
                                "Active",
                                asg.createdTime(),
                                Map.of(
                                        "Desired", asg.desiredCapacity().toString(),
                                        "Min", asg.minSize().toString(),
                                        "Max", asg.maxSize().toString())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: Auto Scaling Groups.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchElastiCacheClustersForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching ElastiCache Clusters...");
                return elastiCacheClient.describeCacheClusters().cacheClusters().stream()
                        .map(c -> new ResourceDto(
                                c.cacheClusterId(),
                                c.cacheClusterId(),
                                "ElastiCache Cluster",
                                c.preferredAvailabilityZone().replaceAll(".$", ""),
                                c.cacheClusterStatus(),
                                c.cacheClusterCreateTime(),
                                Map.of(
                                        "Engine", c.engine() + " " + c.engineVersion(),
                                        "NodeType", c.cacheNodeType(),
                                        "Nodes", c.numCacheNodes().toString())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: ElastiCache Clusters.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchDynamoDbTablesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching DynamoDB Tables...");
                return dynamoDbClient.listTables().tableNames().stream()
                        .map(tableName -> {
                            var tableDesc = dynamoDbClient.describeTable(b -> b.tableName(tableName)).table();
                            return new ResourceDto(
                                    tableName,
                                    tableName,
                                    "DynamoDB Table",
                                    getRegionFromArn(tableDesc.tableArn()), // CORRECTED
                                    tableDesc.tableStatusAsString(),
                                    tableDesc.creationDateTime(),
                                    Map.of(
                                            "Items", tableDesc.itemCount().toString(),
                                            "Size (Bytes)", tableDesc.tableSizeBytes().toString()));
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: DynamoDB Tables.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchEcrRepositoriesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching ECR Repositories...");
                return ecrClient.describeRepositories().repositories().stream()
                        .map(r -> new ResourceDto(
                                r.repositoryName(),
                                r.repositoryName(),
                                "ECR Repository",
                                getRegionFromArn(r.repositoryArn()), // CORRECTED
                                "Available",
                                r.createdAt(),
                                Map.of("URI", r.repositoryUri())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: ECR Repositories.", e);
                return Collections.emptyList();
            }
        });
    }

    

    private CompletableFuture<List<ResourceDto>> fetchRoute53HostedZonesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching Route 53 Hosted Zones...");
                return route53Client.listHostedZones().hostedZones().stream()
                        .map(z -> new ResourceDto(
                                z.id(),
                                z.name(),
                                "Route 53 Zone",
                                "Global",
                                "Available",
                                null,
                                Map.of(
                                        "Type", z.config().privateZone() ? "Private" : "Public",
                                        "Record Count", z.resourceRecordSetCount().toString())))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: Route 53 Hosted Zones.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchSnsTopicsForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching SNS Topics...");
                return snsClient.listTopics().topics().stream()
                        .map(t -> new ResourceDto(
                                t.topicArn(),
                                t.topicArn().substring(t.topicArn().lastIndexOf(':') + 1),
                                "SNS Topic",
                                getRegionFromArn(t.topicArn()),
                                "Active",
                                null,
                                Collections.emptyMap()
                        ))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: SNS Topics.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchSqsQueuesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Cloudlist: Fetching SQS Queues...");
                return sqsClient.listQueues().queueUrls().stream()
                        .map(queueUrl -> {
                            String[] arnParts = sqsClient.getQueueAttributes(req -> req.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN).split(":");
                            return new ResourceDto(
                                    queueUrl,
                                    arnParts[5],
                                    "SQS Queue",
                                    arnParts[3],
                                    "Active",
                                    null,
                                    Collections.emptyMap()
                            );
                        })
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: SQS Queues.", e);
                return Collections.emptyList();
            }
        });
    }

    private String getRegionFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return "Unknown";
        }
        try {
            String[] parts = arn.split(":");
            if (parts.length > 3) {
                String region = parts[3];
                return region.isEmpty() ? "Global" : region;
            }
            return "Global";
        } catch (Exception e) {
            logger.warn("Could not parse region from ARN: {}", arn);
            return this.configuredRegion;
        }
    }

    public Map<String, List<MetricDto>> getEc2InstanceMetrics(String instanceId) {
        logger.info("Fetching CloudWatch metrics for instance: {}", instanceId);
        try {
            GetMetricDataRequest cpuRequest = buildMetricDataRequest(instanceId, "CPUUtilization", "AWS/EC2");
            MetricDataResult cpuResult = cloudWatchClient.getMetricData(cpuRequest).metricDataResults().get(0);
            List<MetricDto> cpuDatapoints = buildMetricDtos(cpuResult);

            GetMetricDataRequest networkInRequest = buildMetricDataRequest(instanceId, "NetworkIn", "AWS/EC2");
            MetricDataResult networkInResult = cloudWatchClient.getMetricData(networkInRequest).metricDataResults()
                    .get(0);
            List<MetricDto> networkInDatapoints = buildMetricDtos(networkInResult);

            return Map.of(
                    "CPUUtilization", cpuDatapoints,
                    "NetworkIn", networkInDatapoints);

        } catch (Exception e) {
            logger.error("Failed to fetch metrics for instance {}", instanceId, e);
            return Collections.emptyMap();
        }
    }

    private List<MetricDto> buildMetricDtos(MetricDataResult result) {
        List<Instant> timestamps = result.timestamps();
        List<Double> values = result.values();

        if (timestamps == null || values == null || timestamps.size() != values.size()) {
            return Collections.emptyList();
        }

        return IntStream.range(0, timestamps.size())
                .mapToObj(i -> new MetricDto(timestamps.get(i), values.get(i)))
                .collect(Collectors.toList());
    }

    private GetMetricDataRequest buildMetricDataRequest(String instanceId, String metricName, String namespace) {
        Metric metric = Metric.builder()
                .namespace(namespace)
                .metricName(metricName)
                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                .build();

        MetricStat metricStat = MetricStat.builder()
                .metric(metric)
                .period(86400)
                .stat("Average")
                .build();

        MetricDataQuery metricDataQuery = MetricDataQuery.builder()
                .id(metricName.toLowerCase().replace(" ", ""))
                .metricStat(metricStat)
                .returnData(true)
                .build();

        return GetMetricDataRequest.builder()
                .startTime(Instant.now().minus(30, ChronoUnit.DAYS))
                .endTime(Instant.now())
                .metricDataQueries(metricDataQuery)
                .scanBy(ScanBy.TIMESTAMP_DESCENDING)
                .build();
    }

    @Async("awsTaskExecutor")
    @Cacheable("wastedResources")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources() {
        logger.info("Fetching wasted resources...");
        List<DashboardData.WastedResource> wasted = new ArrayList<>();
        wasted.addAll(findUnattachedEbsVolumes());
        wasted.addAll(findUnusedElasticIps());
        wasted.addAll(findOldSnapshots());
        wasted.addAll(findDeregisteredAmis());
        wasted.addAll(findIdleRdsInstances());
        wasted.addAll(findIdleLoadBalancers());
        wasted.addAll(findUnusedSecurityGroups());
        wasted.addAll(findIdleEc2Instances());
        wasted.addAll(findUnattachedEnis());
        logger.info("... found {} wasted resources.", wasted.size());
        return CompletableFuture.completedFuture(wasted);
    }

    @Async("awsTaskExecutor")
    @Cacheable("inventory")
    public CompletableFuture<DashboardData.ResourceInventory> getResourceInventory() {
        logger.info("Fetching resource inventory...");
        int vpc = 0, ecs = 0, ec2 = 0, k8s = 0, lambdas = 0, ebs = 0, images = 0, snapshots = 0;
        try {
            vpc = ec2Client.describeVpcs().vpcs().size();
        } catch (Exception e) {
            logger.error("Inv check fail: VPCs", e);
        }
        try {
            ecs = ecsClient.listClusters().clusterArns().size();
        } catch (Exception e) {
            logger.error("Inv check fail: ECS", e);
        }
        try {
            ec2 = ec2Client.describeInstances().reservations().stream().mapToInt(r -> r.instances().size()).sum();
        } catch (Exception e) {
            logger.error("Inv check fail: EC2", e);
        }
        try {
            k8s = eksClient.listClusters().clusters().size();
        } catch (Exception e) {
            logger.error("Inv check fail: EKS", e);
        }
        try {
            lambdas = lambdaClient.listFunctions().functions().size();
        } catch (Exception e) {
            logger.error("Inv check fail: Lambda", e);
        }
        try {
            ebs = ec2Client.describeVolumes().volumes().size();
        } catch (Exception e) {
            logger.error("Inv check fail: EBS", e);
        }
        try {
            images = ec2Client.describeImages(r -> r.owners("self")).images().size();
        } catch (Exception e) {
            logger.error("Inv check fail: Images", e);
        }
        try {
            snapshots = ec2Client.describeSnapshots(r -> r.ownerIds("self")).snapshots().size();
        } catch (Exception e) {
            logger.error("Inv check fail: Snapshots", e);
        }
        return CompletableFuture.completedFuture(
                new DashboardData.ResourceInventory(vpc, ecs, ec2, k8s, lambdas, ebs, images, snapshots));
    }

    @Async("awsTaskExecutor")
    @Cacheable("cloudwatchStatus")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus() {
        logger.info("Fetching CloudWatch status...");
        try {
            List<software.amazon.awssdk.services.cloudwatch.model.MetricAlarm> alarms = cloudWatchClient
                    .describeAlarms().metricAlarms();
            long ok = alarms.stream().filter(a -> a.stateValueAsString().equals("OK")).count();
            long alarm = alarms.stream().filter(a -> a.stateValueAsString().equals("ALARM")).count();
            long insufficient = alarms.stream().filter(a -> a.stateValueAsString().equals("INSUFFICIENT_DATA")).count();
            return CompletableFuture.completedFuture(new DashboardData.CloudWatchStatus(ok, alarm, insufficient));
        } catch (Exception e) {
            logger.error("Could not fetch CloudWatch alarms.", e);
            return CompletableFuture.completedFuture(new DashboardData.CloudWatchStatus(0, 0, 0));
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("securityInsights")
    public CompletableFuture<List<DashboardData.SecurityInsight>> getSecurityInsights() {
        logger.info("Fetching security insights...");
        List<DashboardData.SecurityInsight> insights = new ArrayList<>();
        try {
            int oldKeyCount = (int) iamClient.listUsers().users().stream()
                    .flatMap(u -> iamClient.listAccessKeys(r -> r.userName(u.userName())).accessKeyMetadata().stream())
                    .filter(k -> k.createDate().isBefore(Instant.now().minus(90, ChronoUnit.DAYS))).count();
            if (oldKeyCount > 0)
                insights.add(new DashboardData.SecurityInsight("IAM user access key is too old", "", "SECURITY",
                        oldKeyCount));
        } catch (Exception e) {
            logger.error("Could not fetch IAM key age.", e);
        }
        try {
            PasswordPolicy policy = iamClient.getAccountPasswordPolicy().passwordPolicy();
            if (policy.minimumPasswordLength() < 14)
                insights.add(new DashboardData.SecurityInsight("Password policy is too weak",
                        "Min length is " + policy.minimumPasswordLength(), "SECURITY", 1));
        } catch (NoSuchEntityException e) {
            insights.add(new DashboardData.SecurityInsight("Account password policy not set", "", "SECURITY", 1));
        } catch (Exception e) {
            logger.error("Could not fetch password policy.", e);
        }
        return CompletableFuture.completedFuture(insights);
    }

    @Async("awsTaskExecutor")
    @Cacheable("ec2Recs")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEc2InstanceRecommendations() {
        logger.info("Fetching EC2 recommendations...");
        try {
            GetEc2InstanceRecommendationsRequest request = GetEc2InstanceRecommendationsRequest.builder().build();
            List<InstanceRecommendation> recommendations = computeOptimizerClient.getEC2InstanceRecommendations(request)
                    .instanceRecommendations();
            return CompletableFuture.completedFuture(recommendations.stream()
                    .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED")
                            && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
                    .map(r -> new DashboardData.OptimizationRecommendation("EC2", r.instanceArn().split("/")[1],
                            r.currentInstanceType(), r.recommendationOptions().get(0).instanceType(),
                            r.recommendationOptions().get(0).savingsOpportunity() != null
                                    && r.recommendationOptions().get(0).savingsOpportunity()
                                            .estimatedMonthlySavings() != null
                                    && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings()
                                            .value() != null
                                            ? r.recommendationOptions().get(0).savingsOpportunity()
                                                    .estimatedMonthlySavings().value()
                                            : 0.0,
                            r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", "))))
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch EC2 instance recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("costAnomalies")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomalies() {
        logger.info("Fetching cost anomalies...");
        try {
            AnomalyDateInterval dateInterval = AnomalyDateInterval.builder()
                    .startDate(LocalDate.now().minusDays(60).toString())
                    .endDate(LocalDate.now().toString())
                    .build();
            GetAnomaliesRequest request = GetAnomaliesRequest.builder().dateInterval(dateInterval).build();
            List<Anomaly> anomalies = costExplorerClient.getAnomalies(request).anomalies();
            return CompletableFuture.completedFuture(anomalies.stream()
                    .map(a -> new DashboardData.CostAnomaly(
                            a.anomalyId(),
                            getServiceNameFromAnomaly(a),
                            a.impact().totalImpact(),
                            LocalDate.parse(a.anomalyStartDate().substring(0, 10)), // Extract YYYY-MM-DD
                            a.anomalyEndDate() != null ? LocalDate.parse(a.anomalyEndDate().substring(0, 10))
                                    : LocalDate.now() // Extract YYYY-MM-DD
                    ))
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch Cost Anomalies.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("ebsRecs")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEbsVolumeRecommendations() {
        logger.info("Fetching EBS recommendations...");
        try {
            GetEbsVolumeRecommendationsRequest request = GetEbsVolumeRecommendationsRequest.builder().build();
            List<VolumeRecommendation> recommendations = computeOptimizerClient.getEBSVolumeRecommendations(request)
                    .volumeRecommendations();
            return CompletableFuture.completedFuture(recommendations.stream()
                    .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED")
                            && r.volumeRecommendationOptions() != null && !r.volumeRecommendationOptions().isEmpty())
                    .map(r -> {
                        VolumeRecommendationOption opt = r.volumeRecommendationOptions().get(0);
                        return new DashboardData.OptimizationRecommendation("EBS", r.volumeArn().split("/")[1],
                                r.currentConfiguration().volumeType() + " - " + r.currentConfiguration().volumeSize()
                                        + "GiB",
                                opt.configuration().volumeType() + " - " + opt.configuration().volumeSize() + "GiB",
                                opt.savingsOpportunity() != null
                                        && opt.savingsOpportunity().estimatedMonthlySavings() != null
                                        ? opt.savingsOpportunity().estimatedMonthlySavings().value()
                                        : 0.0,
                                r.finding().toString());
                    })
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch EBS volume recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("lambdaRecs")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getLambdaFunctionRecommendations() {
        logger.info("Fetching Lambda recommendations...");
        try {
            GetLambdaFunctionRecommendationsRequest request = GetLambdaFunctionRecommendationsRequest.builder().build();
            List<LambdaFunctionRecommendation> recommendations = computeOptimizerClient
                    .getLambdaFunctionRecommendations(request).lambdaFunctionRecommendations();
            return CompletableFuture.completedFuture(recommendations.stream()
                    .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED")
                            && r.memorySizeRecommendationOptions() != null
                            && !r.memorySizeRecommendationOptions().isEmpty())
                    .map(r -> {
                        LambdaFunctionMemoryRecommendationOption opt = r.memorySizeRecommendationOptions().get(0);
                        return new DashboardData.OptimizationRecommendation("Lambda",
                                r.functionArn().substring(r.functionArn().lastIndexOf(':') + 1),
                                r.currentMemorySize() + " MB", opt.memorySize() + " MB",
                                opt.savingsOpportunity() != null
                                        && opt.savingsOpportunity().estimatedMonthlySavings() != null
                                        ? opt.savingsOpportunity().estimatedMonthlySavings().value()
                                        : 0.0,
                                r.findingReasonCodes().stream().map(Object::toString)
                                        .collect(Collectors.joining(", ")));
                    })
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch Lambda function recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("reservationAnalysis")
    public CompletableFuture<DashboardData.ReservationAnalysis> getReservationAnalysis() {
        logger.info("Fetching reservation analysis...");
        try {
            String today = LocalDate.now().toString();
            String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
            DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();
            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                    .timePeriod(last30Days).build();
            List<UtilizationByTime> utilizations = costExplorerClient.getReservationUtilization(utilRequest)
                    .utilizationsByTime();
            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder().timePeriod(last30Days)
                    .build();
            List<CoverageByTime> coverages = costExplorerClient.getReservationCoverage(covRequest).coveragesByTime();
            double utilizationPercentage = utilizations.isEmpty() || utilizations.get(0).total() == null ? 0.0
                    : Double.parseDouble(utilizations.get(0).total().utilizationPercentage());
            double coveragePercentage = coverages.isEmpty() || coverages.get(0).total() == null ? 0.0
                    : Double.parseDouble(coverages.get(0).total().coverageHours().coverageHoursPercentage());
            return CompletableFuture
                    .completedFuture(new DashboardData.ReservationAnalysis(utilizationPercentage, coveragePercentage));
        } catch (Exception e) {
            logger.error("Could not fetch reservation analysis data.", e);
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(0.0, 0.0));
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("reservationPurchaseRecs")
    public CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> getReservationPurchaseRecommendations() {
        logger.info("Fetching RI purchase recommendations...");
        try {
            GetReservationPurchaseRecommendationRequest request = GetReservationPurchaseRecommendationRequest.builder()
                    .lookbackPeriodInDays(LookbackPeriodInDays.SIXTY_DAYS)
                    .service("Amazon Elastic Compute Cloud - Compute")
                    .build();
            GetReservationPurchaseRecommendationResponse response = costExplorerClient
                    .getReservationPurchaseRecommendation(request);

            return CompletableFuture.completedFuture(response.recommendations().stream()
                    .filter(rec -> rec.recommendationDetails() != null && !rec.recommendationDetails().isEmpty())
                    .flatMap(rec -> rec.recommendationDetails().stream()
                            .map(details -> {
                                try {
                                    return new DashboardData.ReservationPurchaseRecommendation(
                                            getFieldValue(details, "instanceDetails"),
                                            getFieldValue(details, "recommendedNumberOfInstancesToPurchase"),
                                            getFieldValue(details, "recommendedNormalizedUnitsToPurchase"),
                                            getFieldValue(details, "minimumNormalizedUnitsToPurchase"),
                                            getFieldValue(details, "estimatedMonthlySavingsAmount"),
                                            getFieldValue(details, "estimatedMonthlyOnDemandCost"),
                                            getFieldValue(details, "estimatedMonthlyCost"),
                                            getTermValue(rec));
                                } catch (Exception e) {
                                    logger.warn("Failed to process recommendation detail: {}", e.getMessage());
                                    return null;
                                }
                            }))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch reservation purchase recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("billingSummary")
    public CompletableFuture<List<DashboardData.BillingSummary>> getBillingSummary() {
        logger.info("Fetching billing summary...");
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString())
                            .end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build())
                    .build();
            return CompletableFuture.completedFuture(costExplorerClient.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> new DashboardData.BillingSummary(g.keys().get(0),
                            Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                    .filter(s -> s.getMonthToDateCost() > 0.01).collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch billing summary.", e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable("iamResources")
    public CompletableFuture<DashboardData.IamResources> getIamResources() {
        logger.info("Fetching IAM resources...");
        int users = 0, groups = 0, policies = 0, roles = 0;
        try {
            users = iamClient.listUsers().users().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Users", e);
        }
        try {
            groups = iamClient.listGroups().groups().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Groups", e);
        }
        try {
            policies = iamClient.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Policies", e);
        }
        try {
            roles = iamClient.listRoles().roles().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Roles", e);
        }
        return CompletableFuture.completedFuture(new DashboardData.IamResources(users, groups, policies, roles));
    }

    @Async("awsTaskExecutor")
    @Cacheable("costHistory")
    public CompletableFuture<DashboardData.CostHistory> getCostHistory() {
        logger.info("Fetching cost history...");
        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        try {
            for (int i = 5; i >= 0; i--) {
                LocalDate month = LocalDate.now().minusMonths(i);
                labels.add(month.format(DateTimeFormatter.ofPattern("MMM uuuu")));
                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder().start(month.withDayOfMonth(1).toString())
                                .end(month.plusMonths(1).withDayOfMonth(1).toString()).build())
                        .granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();
                costs.add(Double.parseDouble(costExplorerClient.getCostAndUsage(req).resultsByTime().get(0).total()
                        .get("UnblendedCost").amount()));
            }
        } catch (Exception e) {
            logger.error("Could not fetch cost history", e);
        }
        return CompletableFuture.completedFuture(new DashboardData.CostHistory(labels, costs));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary() {
        List<DashboardData.SavingsSuggestion> suggestions = List.of(
                new DashboardData.SavingsSuggestion("Rightsizing", 155.93),
                new DashboardData.SavingsSuggestion("Spots", 211.78));
        return CompletableFuture.completedFuture(new DashboardData.SavingsSummary(
                suggestions.stream().mapToDouble(DashboardData.SavingsSuggestion::getSuggested).sum(), suggestions));
    }

    private List<DashboardData.WastedResource> findUnattachedEbsVolumes() {
        try {
            return ec2Client.describeVolumes(req -> req.filters(f -> f.name("status").values("available")))
                    .volumes().stream()
                    .map(volume -> {
                        String region = volume.availabilityZone().substring(0, volume.availabilityZone().length() - 1);
                        double monthlyCost = calculateEbsMonthlyCost(volume, region);
                        return new DashboardData.WastedResource(
                                volume.volumeId(),
                                getTagName(volume),
                                "EBS Volume",
                                region,
                                monthlyCost,
                                "Unattached Volume");
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: unattached EBS volumes.", e);
            return Collections.emptyList();
        }
    }

    private List<DashboardData.WastedResource> findUnusedElasticIps() {
        try {
            return ec2Client.describeAddresses().addresses().stream()
                    .filter(address -> address.associationId() == null)
                    .map(address -> new DashboardData.WastedResource(address.allocationId(), address.publicIp(),
                            "Elastic IP", "Global", 5.0, "Unassociated EIP"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: unused Elastic IPs.", e);
            return Collections.emptyList();
        }
    }

    private List<DashboardData.WastedResource> findOldSnapshots() {
        try {
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return ec2Client.describeSnapshots(r -> r.ownerIds("self")).snapshots().stream()
                    .filter(s -> s.startTime().isBefore(ninetyDaysAgo))
                    .map(snapshot -> new DashboardData.WastedResource(snapshot.snapshotId(), getTagName(snapshot),
                            "Snapshot", "Regional", calculateSnapshotMonthlyCost(snapshot), "Older than 90 days"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: old snapshots.", e);
            return Collections.emptyList();
        }
    }

    private List<DashboardData.WastedResource> findDeregisteredAmis() {
        try {
            DescribeImagesRequest imagesRequest = DescribeImagesRequest.builder().owners("self").build();
            return ec2Client.describeImages(imagesRequest).images().stream()
                    .filter(image -> image.state() != ImageState.AVAILABLE)
                    .map(image -> new DashboardData.WastedResource(image.imageId(), image.name(), "AMI", "Regional",
                            1.0, "Deregistered or Failed State"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: unused AMIs.", e);
            return Collections.emptyList();
        }
    }

    private List<DashboardData.WastedResource> findIdleRdsInstances() {
        try {
            return rdsClient.describeDBInstances().dbInstances().stream()
                    .filter(this::isRdsInstanceIdle)
                    .map(dbInstance -> new DashboardData.WastedResource(
                            dbInstance.dbInstanceIdentifier(),
                            dbInstance.dbInstanceIdentifier(),
                            "RDS Instance",
                            dbInstance.availabilityZone().replaceAll(".$", ""),
                            20.0, // Placeholder for cost
                            "Idle RDS Instance (no connections)"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: idle RDS instances.", e);
            return Collections.emptyList();
        }
    }

    private boolean isRdsInstanceIdle(software.amazon.awssdk.services.rds.model.DBInstance dbInstance) {
        try {
            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(Instant.now().minus(7, ChronoUnit.DAYS))
                    .endTime(Instant.now())
                    .metricDataQueries(MetricDataQuery.builder()
                            .id("rdsConnections")
                            .metricStat(MetricStat.builder()
                                    .metric(Metric.builder()
                                            .namespace("AWS/RDS")
                                            .metricName("DatabaseConnections")
                                            .dimensions(Dimension.builder().name("DBInstanceIdentifier")
                                                    .value(dbInstance.dbInstanceIdentifier()).build())
                                            .build())
                                    .period(86400) // 1 day
                                    .stat("Maximum")
                                    .build())
                            .returnData(true)
                            .build())
                    .build();

            List<MetricDataResult> results = cloudWatchClient.getMetricData(request).metricDataResults();
            if (!results.isEmpty() && !results.get(0).values().isEmpty()) {
                return results.get(0).values().stream().allMatch(v -> v < 1); // No connections in the last 7 days
            }
        } catch (Exception e) {
            logger.error("Could not get metrics for RDS instance {}: {}", dbInstance.dbInstanceIdentifier(),
                    e.getMessage());
        }
        return false;
    }

    private List<DashboardData.WastedResource> findIdleLoadBalancers() {
        List<DashboardData.WastedResource> wastedLbs = new ArrayList<>();
        try {
            elbv2Client.describeLoadBalancers().loadBalancers().forEach(lb -> {
                boolean isIdle = elbv2Client.describeTargetGroups(req -> req.loadBalancerArn(lb.loadBalancerArn()))
                        .targetGroups().stream()
                        .allMatch(tg -> elbv2Client.describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn()))
                                .targetHealthDescriptions().isEmpty());

                if (isIdle) {
                    wastedLbs.add(new DashboardData.WastedResource(
                            lb.loadBalancerArn(),
                            lb.loadBalancerName(),
                            "Load Balancer",
                            lb.availabilityZones().get(0).zoneName().replaceAll(".$", ""),
                            15.0, // Placeholder for cost
                            "Idle Load Balancer (no targets)"));
                }
            });
            return wastedLbs;
        } catch (Exception e) {
            logger.error("Sub-task failed: idle Load Balancers.", e);
            return Collections.emptyList();
        }
    }

    private List<DashboardData.WastedResource> findUnusedSecurityGroups() {
        try {
            return ec2Client.describeSecurityGroups().securityGroups().stream()
                    .filter(sg -> sg.vpcId() != null && isSecurityGroupUnused(sg.groupId()))
                    .map(sg -> new DashboardData.WastedResource(
                            sg.groupId(),
                            sg.groupName(),
                            "Security Group",
                            "Regional",
                            0.0, // Security groups are free, but are a security risk
                            "Unused Security Group"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: unused security groups.", e);
            return Collections.emptyList();
        }
    }

    private boolean isSecurityGroupUnused(String groupId) {
        try {
            DescribeNetworkInterfacesRequest request = DescribeNetworkInterfacesRequest.builder()
                    .filters(software.amazon.awssdk.services.ec2.model.Filter.builder().name("group-id").values(groupId)
                            .build())
                    .build();
            return ec2Client.describeNetworkInterfaces(request).networkInterfaces().isEmpty();
        } catch (Exception e) {
            logger.error("Could not check usage for security group {}: {}", groupId, e.getMessage());
        }
        return false;
    }

    private List<DashboardData.WastedResource> findIdleEc2Instances() {
        try {
            return ec2Client.describeInstances().reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .filter(this::isEc2InstanceIdle)
                    .map(instance -> new DashboardData.WastedResource(
                            instance.instanceId(),
                            getTagName(instance.tags(), instance.instanceId()),
                            "EC2 Instance",
                            instance.placement().availabilityZone().replaceAll(".$", ""),
                            10.0, // Placeholder for cost
                            "Idle EC2 Instance (low CPU)"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: idle EC2 instances.", e);
            return Collections.emptyList();
        }
    }

    private boolean isEc2InstanceIdle(Instance instance) {
        try {
            GetMetricDataRequest request = buildMetricDataRequest(instance.instanceId(), "CPUUtilization", "AWS/EC2");
            List<MetricDataResult> results = cloudWatchClient.getMetricData(request).metricDataResults();
            if (!results.isEmpty() && !results.get(0).values().isEmpty()) {
                // Check if average CPU utilization over the last 30 days is less than 3%
                return results.get(0).values().stream().mapToDouble(Double::doubleValue).average().orElse(100.0) < 3.0;
            }
        } catch (Exception e) {
            logger.error("Could not get metrics for EC2 instance {}: {}", instance.instanceId(), e.getMessage());
        }
        return false;
    }

    private List<DashboardData.WastedResource> findUnattachedEnis() {
        try {
            return ec2Client.describeNetworkInterfaces(req -> req.filters(f -> f.name("status").values("available")))
                    .networkInterfaces().stream()
                    .map(eni -> new DashboardData.WastedResource(
                            eni.networkInterfaceId(),
                            getTagName(eni.tagSet(), eni.networkInterfaceId()),
                            "ENI",
                            eni.availabilityZone().replaceAll(".$", ""),
                            2.0, // Placeholder for cost
                            "Unattached ENI"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: unattached ENIs.", e);
            return Collections.emptyList();
        }
    }

    private String getTagName(Volume volume) {
        return volume.hasTags()
                ? volume.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value)
                        .orElse(volume.volumeId())
                : volume.volumeId();
    }

    private String getTagName(Snapshot snapshot) {
        return snapshot.hasTags()
                ? snapshot.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value)
                        .orElse(snapshot.snapshotId())
                : snapshot.snapshotId();
    }

    public String getTagName(List<Tag> tags, String defaultName) {
        return tags.stream()
                .filter(t -> t.key().equalsIgnoreCase("Name"))
                .findFirst()
                .map(Tag::value)
                .orElse(defaultName);
    }

    private double calculateEbsMonthlyCost(Volume volume, String region) {
        double gbMonthPrice = pricingService.getEbsGbMonthPrice(region, volume.volumeTypeAsString());
        return volume.size() * gbMonthPrice;
    }

    private double calculateSnapshotMonthlyCost(Snapshot snapshot) {
        if (snapshot.volumeSize() != null) {
            return snapshot.volumeSize() * 0.05;
        }
        return 0.0;
    }

    private DashboardData.OptimizationSummary getOptimizationSummary(
            List<DashboardData.OptimizationRecommendation> ec2Recs,
            List<DashboardData.OptimizationRecommendation> ebsRecs,
            List<DashboardData.OptimizationRecommendation> lambdaRecs,
            List<DashboardData.CostAnomaly> anomalies) {
        double totalSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs).flatMap(List::stream)
                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings).sum();
        long criticalAlerts = anomalies.size() + ec2Recs.size() + ebsRecs.size() + lambdaRecs.size();
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    private String getServiceNameFromAnomaly(Anomaly anomaly) {
        if (anomaly.rootCauses() != null && !anomaly.rootCauses().isEmpty()) {
            RootCause rootCause = anomaly.rootCauses().get(0);
            if (rootCause.service() != null) {
                return rootCause.service();
            }
        }
        return "Unknown Service";
    }

    private String getFieldValue(Object details, String methodName) {
        try {
            Method method = details.getClass().getMethod(methodName);
            Object result = method.invoke(details);
            return result != null ? result.toString() : "0";
        } catch (Exception e) {
            logger.debug("Could not access method {}: {}", methodName, e.getMessage());
            return "N/A";
        }
    }

    private String getTermValue(ReservationPurchaseRecommendation rec) {
        try {
            return rec.termInYears() != null ? rec.termInYears().toString() : "1 Year";
        } catch (Exception e) {
            logger.debug("Could not determine term value", e);
            return "1 Year";
        }
    }

@Async("awsTaskExecutor")
@Cacheable(value = "graphData", key = "#vpcId")
public CompletableFuture<List<Map<String, Object>>> getGraphData(String vpcId) {
    logger.info("Fetching graph data for VPC ID: {}", vpcId);
    return CompletableFuture.supplyAsync(() -> {
        List<Map<String, Object>> elements = new ArrayList<>();

        try {
            // Always add S3 Buckets first, as they are global
            s3Client.listBuckets().buckets().forEach(bucket -> {
                Map<String, Object> bucketNode = new HashMap<>();
                Map<String, Object> bucketData = new HashMap<>();
                bucketData.put("id", bucket.name());
                bucketData.put("label", bucket.name());
                bucketData.put("type", "S3 Bucket");
                bucketNode.put("data", bucketData);
                elements.add(bucketNode);
            });

            if (vpcId == null || vpcId.isBlank()) {
                return elements;
            }

            // Process the selected VPC
            Vpc vpc = ec2Client.describeVpcs(r -> r.vpcIds(vpcId)).vpcs().get(0);
            Map<String, Object> vpcNode = new HashMap<>();
            Map<String, Object> vpcData = new HashMap<>();
            vpcData.put("id", vpc.vpcId());
            vpcData.put("label", getTagName(vpc.tags(), vpc.vpcId()));
            vpcData.put("type", "VPC");
            vpcNode.put("data", vpcData);
            elements.add(vpcNode);

            // Create Availability Zone containers
            DescribeSubnetsRequest subnetsRequest = DescribeSubnetsRequest.builder()
                    .filters(f -> f.name("vpc-id").values(vpcId))
                    .build();
            List<software.amazon.awssdk.services.ec2.model.Subnet> subnets = ec2Client.describeSubnets(subnetsRequest).subnets();

            subnets.stream().map(software.amazon.awssdk.services.ec2.model.Subnet::availabilityZone).distinct().forEach(azName -> {
                Map<String, Object> azNode = new HashMap<>();
                Map<String, Object> azData = new HashMap<>();
                azData.put("id", azName);
                azData.put("label", azName);
                azData.put("type", "Availability Zone");
                azData.put("parent", vpc.vpcId());
                azNode.put("data", azData);
                elements.add(azNode);
            });

            // Add Subnet nodes inside their AZ
            subnets.forEach(subnet -> {
                Map<String, Object> subnetNode = new HashMap<>();
                Map<String, Object> subnetData = new HashMap<>();
                subnetData.put("id", subnet.subnetId());
                subnetData.put("label", getTagName(subnet.tags(), subnet.subnetId()));
                subnetData.put("type", "Subnet");
                subnetData.put("parent", subnet.availabilityZone());
                subnetNode.put("data", subnetData);
                elements.add(subnetNode);
            });

            // Add Internet Gateways to the VPC
            ec2Client.describeInternetGateways(r -> r.filters(f -> f.name("attachment.vpc-id").values(vpcId)))
                .internetGateways().forEach(igw -> {
                    Map<String, Object> igwNode = new HashMap<>();
                    Map<String, Object> igwData = new HashMap<>();
                    igwData.put("id", igw.internetGatewayId());
                    igwData.put("label", getTagName(igw.tags(), igw.internetGatewayId()));
                    igwData.put("type", "Internet Gateway");
                    igwData.put("parent", vpc.vpcId());
                    igwNode.put("data", igwData);
                    elements.add(igwNode);
                });

            // Add NAT Gateways to their Subnet
            ec2Client.describeNatGateways(r -> r.filter(f -> f.name("vpc-id").values(vpcId)))
                .natGateways().forEach(nat -> {
                    Map<String, Object> natNode = new HashMap<>();
                    Map<String, Object> natData = new HashMap<>();
                    natData.put("id", nat.natGatewayId());
                    natData.put("label", getTagName(nat.tags(), nat.natGatewayId()));
                    natData.put("type", "NAT Gateway");
                    natData.put("parent", nat.subnetId());
                    natNode.put("data", natData);
                    elements.add(natNode);
                });

            // Add Security Groups to the VPC
            DescribeSecurityGroupsRequest sgsRequest = DescribeSecurityGroupsRequest.builder()
                    .filters(f -> f.name("vpc-id").values(vpcId))
                    .build();
            ec2Client.describeSecurityGroups(sgsRequest).securityGroups().forEach(sg -> {
                Map<String, Object> sgNode = new HashMap<>();
                Map<String, Object> sgData = new HashMap<>();
                sgData.put("id", sg.groupId());
                sgData.put("label", sg.groupName());
                sgData.put("type", "Security Group");
                sgData.put("parent", vpc.vpcId());
                sgNode.put("data", sgData);
                elements.add(sgNode);
            });

            // Add Auto Scaling Groups to the VPC
            autoScalingClient.describeAutoScalingGroups().autoScalingGroups().stream()
                .filter(asg -> asg.vpcZoneIdentifier().contains(vpcId))
                .forEach(asg -> {
                    Map<String, Object> asgNode = new HashMap<>();
                    Map<String, Object> asgData = new HashMap<>();
                    asgData.put("id", asg.autoScalingGroupARN());
                    asgData.put("label", asg.autoScalingGroupName());
                    asgData.put("type", "Auto Scaling Group");
                    asgData.put("parent", vpc.vpcId());
                    asgNode.put("data", asgData);
                    elements.add(asgNode);
                    
                    asg.instances().forEach(inst -> {
                        Map<String, Object> edge = new HashMap<>();
                        Map<String, Object> edgeData = new HashMap<>();
                        edgeData.put("id", asg.autoScalingGroupARN() + "-" + inst.instanceId());
                        edgeData.put("source", asg.autoScalingGroupARN());
                        edgeData.put("target", inst.instanceId());
                        edge.put("data", edgeData);
                        elements.add(edge);
                    });
                });

            // Add EC2 Instances to their Subnet and create edges to Security Groups
            DescribeInstancesRequest instancesRequest = DescribeInstancesRequest.builder()
                    .filters(f -> f.name("vpc-id").values(vpcId))
                    .build();
            ec2Client.describeInstances(instancesRequest).reservations().stream()
                .flatMap(r -> r.instances().stream())
                .filter(instance -> instance.subnetId() != null)
                .forEach(instance -> {
                    Map<String, Object> instanceNode = new HashMap<>();
                    Map<String, Object> instanceData = new HashMap<>();
                    instanceData.put("id", instance.instanceId());
                    instanceData.put("label", getTagName(instance.tags(), instance.instanceId()));
                    instanceData.put("type", "EC2 Instance");
                    instanceData.put("parent", instance.subnetId());
                    instanceNode.put("data", instanceData);
                    elements.add(instanceNode);
                    
                    // Add edges to security groups
                    instance.securityGroups().forEach(sg -> {
                        Map<String, Object> edge = new HashMap<>();
                        Map<String, Object> edgeData = new HashMap<>();
                        edgeData.put("id", instance.instanceId() + "-" + sg.groupId());
                        edgeData.put("source", instance.instanceId());
                        edgeData.put("target", sg.groupId());
                        edge.put("data", edgeData);
                        elements.add(edge);
                    });
                });
            
            // Add RDS Instances to their Subnet
            rdsClient.describeDBInstances().dbInstances().stream()
                .filter(db -> db.dbSubnetGroup() != null && vpcId.equals(db.dbSubnetGroup().vpcId()))
                .forEach(db -> {
                    if (!db.dbSubnetGroup().subnets().isEmpty()) {
                        Map<String, Object> dbNode = new HashMap<>();
                        Map<String, Object> dbData = new HashMap<>();
                        dbData.put("id", db.dbInstanceArn());
                        dbData.put("label", db.dbInstanceIdentifier());
                        dbData.put("type", "RDS Instance");
                        dbData.put("parent", db.dbSubnetGroup().subnets().get(0).subnetIdentifier());
                        dbNode.put("data", dbData);
                        elements.add(dbNode);
                    }
                });

        } catch (Exception e) {
            logger.error("Failed to build graph data for VPC {}", vpcId, e);
            throw new RuntimeException("Failed to fetch graph data from AWS", e);
        }

        return elements;
    });
}

@Async("awsTaskExecutor")
@Cacheable("vpcList")
public CompletableFuture<List<Vpc>> getVpcList() {
    logger.info("Fetching list of VPCs...");
    return CompletableFuture.supplyAsync(() -> ec2Client.describeVpcs().vpcs());
}
}