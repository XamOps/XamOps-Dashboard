package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.dto.k8s.*;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.budgets.model.*;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttribute;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.Trail;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
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
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
public class AwsDataService {

    private static final Logger logger = LoggerFactory.getLogger(AwsDataService.class);
    
    private final List<String> requiredTags;
    private final List<String> instanceSizeOrder;
    private final Set<String> keyQuotas;

    private final PricingService pricingService;
    private final String hostAccountId;
    private final String configuredRegion;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    public final AwsClientProvider awsClientProvider;
    private final DashboardUpdateService dashboardUpdateService;
    private final AiAdvisorService aiAdvisorService;


    @Value("${cloudformation.template.s3.url}")
    private String cloudFormationTemplateUrl;

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

    @Autowired
    public AwsDataService(
            PricingService pricingService,
            CloudAccountRepository cloudAccountRepository,
            ClientRepository clientRepository,
            AwsClientProvider awsClientProvider,
            StsClient stsClient,
            DashboardUpdateService dashboardUpdateService,
            @Lazy AiAdvisorService aiAdvisorService,
            @Value("${tagging.compliance.required-tags}") List<String> requiredTags,
            @Value("${rightsizing.instance-size-order}") List<String> instanceSizeOrder,
            @Value("${quotas.key-codes}") Set<String> keyQuotas
    ) {
        this.pricingService = pricingService;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.awsClientProvider = awsClientProvider;
        this.dashboardUpdateService = dashboardUpdateService;
        this.aiAdvisorService = aiAdvisorService;
        this.requiredTags = requiredTags;
        this.instanceSizeOrder = instanceSizeOrder;
        this.keyQuotas = keyQuotas;

        String tmpAccountId;
        try {
            tmpAccountId = stsClient.getCallerIdentity().account();
        } catch (Exception e) {
            logger.error("Could not determine AWS Account ID. Budgets may not work correctly.", e);
            tmpAccountId = "YOUR_ACCOUNT_ID"; // Fallback
        }
        this.hostAccountId = tmpAccountId;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

 public LambdaClient getLambdaClient(String accountId, String region) {
    return awsClientProvider.getLambdaClient(getAccount(accountId), region);
}

    public S3Client getS3Client(String accountId) {
        return awsClientProvider.getS3Client(getAccount(accountId), "us-east-1");
    }

public CloudWatchClient getCloudWatchClient(String accountId, String region) {
    return awsClientProvider.getCloudWatchClient(getAccount(accountId), region);
}
public Ec2Client getEc2Client(String accountId, String region) {
    return awsClientProvider.getEc2Client(getAccount(accountId), region);
}

public RdsClient getRdsClient(String accountId, String region) {
    return awsClientProvider.getRdsClient(getAccount(accountId), region);
}

    public String getCurrentRegion(String accountId) {
        // Get region from account configuration or use default
        return configuredRegion;
    }

    public Map<String, Object> getResourcesList(String accountId) {
        Map<String, Object> resources = new HashMap<>();
        CloudAccount account = getAccount(accountId);
        
        try {
            // Get EC2 instances
            CompletableFuture<List<Map<String, String>>> ec2Future = CompletableFuture.supplyAsync(() -> {
                try {
                    List<Map<String, String>> ec2Instances = new ArrayList<>();
                    Ec2Client ec2Client = awsClientProvider.getEc2Client(account, configuredRegion);
                    DescribeInstancesResponse instancesResponse = ec2Client.describeInstances();
                    
                    instancesResponse.reservations().forEach(reservation -> {
                        reservation.instances().forEach(instance -> {
                            Map<String, String> instanceInfo = new HashMap<>();
                            instanceInfo.put("id", instance.instanceId());
                            instanceInfo.put("name", getTagName(instance.tags(), "N/A"));
                            instanceInfo.put("state", instance.state().nameAsString());
                            instanceInfo.put("type", instance.instanceType().toString());
                            instanceInfo.put("region", instance.placement().availabilityZone());
                            ec2Instances.add(instanceInfo);
                        });
                    });
                    return ec2Instances;
                } catch (Exception e) {
                    logger.error("Error fetching EC2 instances for account: {}", accountId, e);
                    return new ArrayList<>();
                }
            });
            
            // Get RDS instances
            CompletableFuture<List<Map<String, String>>> rdsFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<Map<String, String>> rdsInstances = new ArrayList<>();
                    RdsClient rdsClient = awsClientProvider.getRdsClient(account, configuredRegion);
                    var dbInstancesResponse = rdsClient.describeDBInstances();
                    
                    dbInstancesResponse.dbInstances().forEach(dbInstance -> {
                        Map<String, String> dbInfo = new HashMap<>();
                        dbInfo.put("id", dbInstance.dbInstanceIdentifier());
                        dbInfo.put("name", dbInstance.dbName() != null ? dbInstance.dbName() : dbInstance.dbInstanceIdentifier());
                        dbInfo.put("engine", dbInstance.engine());
                        dbInfo.put("status", dbInstance.dbInstanceStatus());
                        dbInfo.put("class", dbInstance.dbInstanceClass());
                        dbInfo.put("region", dbInstance.availabilityZone());
                        rdsInstances.add(dbInfo);
                    });
                    return rdsInstances;
                } catch (Exception e) {
                    logger.error("Error fetching RDS instances for account: {}", accountId, e);
                    return new ArrayList<>();
                }
            });
            
            // Get Lambda functions
            CompletableFuture<List<Map<String, String>>> lambdaFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    List<Map<String, String>> lambdaFunctions = new ArrayList<>();
                    LambdaClient lambdaClient = awsClientProvider.getLambdaClient(account, configuredRegion);
                    var functionsResponse = lambdaClient.listFunctions();
                    
                    functionsResponse.functions().forEach(function -> {
                        Map<String, String> functionInfo = new HashMap<>();
                        functionInfo.put("name", function.functionName());
                        functionInfo.put("id", function.functionName());
                        functionInfo.put("runtime", function.runtime().toString());
                        functionInfo.put("state", function.state().toString());
                        functionInfo.put("region", getCurrentRegion(accountId));
                        lambdaFunctions.add(functionInfo);
                    });
                    return lambdaFunctions;
                } catch (Exception e) {
                    logger.error("Error fetching Lambda functions for account: {}", accountId, e);
                    return new ArrayList<>();
                }
            });
            
            // Get S3 buckets
            CompletableFuture<List<Map<String, String>>> s3Future = CompletableFuture.supplyAsync(() -> {
                try {
                    List<Map<String, String>> s3Buckets = new ArrayList<>();
                    S3Client s3Client = awsClientProvider.getS3Client(account, "us-east-1");
                    var bucketsResponse = s3Client.listBuckets();
                    
                    bucketsResponse.buckets().forEach(bucket -> {
                        Map<String, String> bucketInfo = new HashMap<>();
                        bucketInfo.put("name", bucket.name());
                        bucketInfo.put("id", bucket.name());
                        bucketInfo.put("created", bucket.creationDate().toString());
                        bucketInfo.put("region", getCurrentRegion(accountId));
                        s3Buckets.add(bucketInfo);
                    });
                    return s3Buckets;
                } catch (Exception e) {
                    logger.error("Error fetching S3 buckets for account: {}", accountId, e);
                    return new ArrayList<>();
                }
            });
            
            // Wait for all futures to complete
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(ec2Future, rdsFuture, lambdaFuture, s3Future);
            allFutures.get();
            
            resources.put("ec2", ec2Future.get());
            resources.put("rds", rdsFuture.get());
            resources.put("lambda", lambdaFuture.get());
            resources.put("s3", s3Future.get());
            
        } catch (Exception e) {
            logger.error("Error fetching resources list for account: {}", accountId, e);
        }
        
        return resources;
    }


    @Cacheable(value = "dashboardData", key = "#accountId")
    public DashboardData getDashboardData(String accountId) throws ExecutionException, InterruptedException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", accountId);
        CloudAccount account = getAccount(accountId);

        CompletableFuture<List<DashboardData.RegionStatus>> regionStatusFuture = getRegionStatusForAccount(account);

        return regionStatusFuture.thenCompose(activeRegions -> {
            logger.info("Active regions fetched for account {}, proceeding with dependent data calls.", accountId);

            CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(account, activeRegions);
            CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account, activeRegions);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = getEc2InstanceRecommendations(account, activeRegions);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = getEbsVolumeRecommendations(account, activeRegions);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = getLambdaFunctionRecommendations(account, activeRegions);
            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = getWastedResources(account, activeRegions);
            CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = getComprehensiveSecurityFindings(account, activeRegions);
            CompletableFuture<List<DashboardData.ServiceQuotaInfo>> serviceQuotasFuture = getServiceQuotaInfo(account, activeRegions);
            CompletableFuture<List<ReservationInventoryDto>> reservationInventoryFuture = getReservationInventory(account, activeRegions);

            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = getCostHistory(account);
            CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = getBillingSummary(account);
            CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account);
            
            CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
                wastedResourcesFuture, 
                ec2RecsFuture, 
                ebsRecsFuture, 
                lambdaRecsFuture
            );

            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = getCostAnomalies(account);
            CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = getReservationAnalysis(account);
            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = getReservationPurchaseRecommendations(account);

            return CompletableFuture.allOf(
                inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
                wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
                iamFuture, savingsFuture, anomaliesFuture, reservationFuture, reservationPurchaseFuture,
                serviceQuotasFuture, reservationInventoryFuture
            ).thenApply(v -> {
                logger.info("--- ALL ASYNC DATA FETCHES COMPLETE for account {}, assembling DTO ---", accountId);

                List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.join();
                List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.isCompletedExceptionally() ? Collections.emptyList() : ec2RecsFuture.join();
                List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.isCompletedExceptionally() ? Collections.emptyList() : ebsRecsFuture.join();
                List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.isCompletedExceptionally() ? Collections.emptyList() : lambdaRecsFuture.join();
                List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.isCompletedExceptionally() ? Collections.emptyList() : anomaliesFuture.join();
                List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.isCompletedExceptionally() ? Collections.emptyList() : securityFindingsFuture.join();
                
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
                        activeRegions, inventoryFuture.join(), cwStatusFuture.join(), securityInsights,
                        costHistoryFuture.join(), billingFuture.join(), iamFuture.join(), savingsFuture.join(),
                        ec2Recs, anomalies, ebsRecs, lambdaRecs,
                        reservationFuture.join(), reservationPurchaseFuture.join(),
                        optimizationSummary, wastedResources, serviceQuotasFuture.join(),
                        securityScore);

                data.setSelectedAccount(mainAccount);

                List<DashboardData.Account> availableAccounts = cloudAccountRepository.findAll().stream()
                    .map(acc -> new DashboardData.Account(acc.getAwsAccountId(), acc.getAccountName(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0))
                    .collect(Collectors.toList());
                data.setAvailableAccounts(availableAccounts);

                return data;
            });
        }).join();
    }

    private boolean isRegionActive(Ec2Client ec2Client, RdsClient rdsClient, LambdaClient lambdaClient, EcsClient ecsClient, software.amazon.awssdk.regions.Region region) {
        logger.debug("Performing activity check for region: {}", region.id());
        try {
            if (ec2Client.describeInstances().hasReservations() && !ec2Client.describeInstances().reservations().isEmpty()) return true;
            if (ec2Client.describeVolumes().hasVolumes() && !ec2Client.describeVolumes().volumes().isEmpty()) return true;
            if (rdsClient.describeDBInstances().hasDbInstances() && !rdsClient.describeDBInstances().dbInstances().isEmpty()) return true;
            if (lambdaClient.listFunctions().hasFunctions() && !lambdaClient.listFunctions().functions().isEmpty()) return true;
            if (ecsClient.listClusters().hasClusterArns() && !ecsClient.listClusters().clusterArns().isEmpty()) return true;
        } catch (AwsServiceException | SdkClientException e) {
            logger.warn("Could not perform active check for region {}: {}. Assuming inactive.", region.id(), e.getMessage());
            return false;
        }
        logger.debug("No activity found in region: {}", region.id());
        return false;
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "regionStatus", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForAccount(CloudAccount account) {
        logger.info("Fetching status for all available and active AWS regions for account {}...", account.getAwsAccountId());
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        try {
            List<Region> allRegions = ec2.describeRegions().regions();
            logger.debug("Found {} total regions available to the account {}. Now checking for activity.", allRegions.size(), account.getAwsAccountId());

            List<DashboardData.RegionStatus> regionStatuses = allRegions.parallelStream()
                .filter(region -> !"not-opted-in".equals(region.optInStatus()))
                .filter(region -> {
                    if (!REGION_GEO.containsKey(region.regionName())) {
                        logger.warn("Region {} is available but has no geographic coordinates defined. It will be excluded from the map.", region.regionName());
                        return false;
                    }
                    return true;
                })
                .map(region -> {
                    software.amazon.awssdk.regions.Region sdkRegion = software.amazon.awssdk.regions.Region.of(region.regionName());
                    Ec2Client regionEc2 = awsClientProvider.getEc2Client(account, sdkRegion.id());
                    RdsClient regionRds = awsClientProvider.getRdsClient(account, sdkRegion.id());
                    LambdaClient regionLambda = awsClientProvider.getLambdaClient(account, sdkRegion.id());
                    EcsClient regionEcs = awsClientProvider.getEcsClient(account, sdkRegion.id());
                    if (isRegionActive(regionEc2, regionRds, regionLambda, regionEcs, sdkRegion)) {
                        return mapRegionToStatus(region);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            logger.debug("Successfully fetched {} active region statuses for account {}", regionStatuses.size(), account.getAwsAccountId());
            return CompletableFuture.completedFuture(regionStatuses);

        } catch (Exception e) {
            logger.error("Could not fetch and process AWS regions for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.ResourceInventory> getResourceInventory(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("Fetching comprehensive resource inventory for account {}...", account.getAwsAccountId());

        // Regional futures
        List<CompletableFuture<Map<String, Integer>>> regionalFutures = activeRegions.stream()
            .map(region -> CompletableFuture.supplyAsync(() -> {
                Map<String, Integer> regionalCounts = new HashMap<>();
                String regionId = region.getRegionId();
                
                try {
                    Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
                    regionalCounts.put("ec2", ec2.describeInstances().reservations().stream().mapToInt(r -> r.instances().size()).sum());
                    regionalCounts.put("ebs", ec2.describeVolumes().volumes().size());
                    regionalCounts.put("images", ec2.describeImages(r -> r.owners("self")).images().size());
                    regionalCounts.put("snapshots", ec2.describeSnapshots(r -> r.ownerIds("self")).snapshots().size());
                    regionalCounts.put("vpc", ec2.describeVpcs().vpcs().size());
                } catch (Exception e) { logger.error("Inventory check failed for EC2-related services in region {} for account {}", regionId, account.getAwsAccountId(), e); }

                try {
                    EcsClient ecs = awsClientProvider.getEcsClient(account, regionId);
                    regionalCounts.put("ecs", ecs.listClusters().clusterArns().size());
                } catch (Exception e) { logger.error("Inventory check failed for ECS in region {} for account {}", regionId, account.getAwsAccountId(), e); }

                try {
                    EksClient eks = awsClientProvider.getEksClient(account, regionId);
                    regionalCounts.put("k8s", eks.listClusters().clusters().size());
                } catch (Exception e) { logger.error("Inventory check failed for EKS in region {} for account {}", regionId, account.getAwsAccountId(), e); }

                try {
                    LambdaClient lambda = awsClientProvider.getLambdaClient(account, regionId);
                    regionalCounts.put("lambdas", lambda.listFunctions().functions().size());
                } catch (Exception e) { logger.error("Inventory check failed for Lambda in region {} for account {}", regionId, account.getAwsAccountId(), e); }

                try {
                    RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
                    regionalCounts.put("rds", rds.describeDBInstances().dbInstances().size());
                } catch (Exception e) { logger.error("Inventory check failed for RDS in region {} for account {}", regionId, account.getAwsAccountId(), e); }

                try {
                    ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, regionId);
                    regionalCounts.put("elb", elbv2.describeLoadBalancers().loadBalancers().size());
                } catch (Exception e) { logger.error("Inventory check failed for ELBv2 in region {} for account {}", regionId, account.getAwsAccountId(), e); }

                return regionalCounts;
            }))
            .collect(Collectors.toList());

        // Global futures
        CompletableFuture<Integer> s3Future = CompletableFuture.supplyAsync(() -> {
            try {
                S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
                return s3.listBuckets().buckets().size();
            } catch (Exception e) {
                logger.error("Inventory check failed for S3 for account {}", account.getAwsAccountId(), e);
                return 0;
            }
        });

        CompletableFuture<Integer> r53Future = CompletableFuture.supplyAsync(() -> {
            try {
                Route53Client r53 = awsClientProvider.getRoute53Client(account);
                return r53.listHostedZones().hostedZones().size();
            } catch (Exception e) {
                logger.error("Inventory check failed for Route53 for account {}", account.getAwsAccountId(), e);
                return 0;
            }
        });
        CompletableFuture<Void> allRegionalFutures = CompletableFuture.allOf(regionalFutures.toArray(new CompletableFuture[0]));
        
        return CompletableFuture.allOf(allRegionalFutures, s3Future, r53Future).thenApply(v -> {
            DashboardData.ResourceInventory totalInventory = new DashboardData.ResourceInventory();
            
            regionalFutures.stream()
                .map(CompletableFuture::join)
                .forEach(regionalCounts -> {
                    totalInventory.setVpc(totalInventory.getVpc() + regionalCounts.getOrDefault("vpc", 0));
                    totalInventory.setEcs(totalInventory.getEcs() + regionalCounts.getOrDefault("ecs", 0));
                    totalInventory.setEc2(totalInventory.getEc2() + regionalCounts.getOrDefault("ec2", 0));
                    totalInventory.setKubernetes(totalInventory.getKubernetes() + regionalCounts.getOrDefault("k8s", 0));
                    totalInventory.setLambdas(totalInventory.getLambdas() + regionalCounts.getOrDefault("lambdas", 0));
                    totalInventory.setEbsVolumes(totalInventory.getEbsVolumes() + regionalCounts.getOrDefault("ebs", 0));
                    totalInventory.setImages(totalInventory.getImages() + regionalCounts.getOrDefault("images", 0));
                    totalInventory.setSnapshots(totalInventory.getSnapshots() + regionalCounts.getOrDefault("snapshots", 0));
                    totalInventory.setRdsInstances(totalInventory.getRdsInstances() + regionalCounts.getOrDefault("rds", 0));
                    totalInventory.setLoadBalancers(totalInventory.getLoadBalancers() + regionalCounts.getOrDefault("elb", 0));
                });

            totalInventory.setS3Buckets(s3Future.join());
            totalInventory.setRoute53Zones(r53Future.join());

            dashboardUpdateService.sendUpdate(account.getAwsAccountId(), "inventory", totalInventory);
            logger.debug("Aggregated total inventory for account {}: {}", account.getAwsAccountId(), totalInventory);
            return totalInventory;
        });
    }
    

    private static final Set<String> SUSTAINABLE_REGIONS = Set.of("eu-west-1", "eu-north-1", "ca-central-1", "us-west-2");
    private DashboardData.RegionStatus mapRegionToStatus(Region region) { double[] coords = REGION_GEO.get(region.regionName()); String status = "ACTIVE"; if (SUSTAINABLE_REGIONS.contains(region.regionName())) { status = "SUSTAINABLE"; } return new DashboardData.RegionStatus(region.regionName(), region.regionName(), status, coords[0], coords[1]); }

    @Async("awsTaskExecutor")
    @Cacheable(value = "allRecommendations", key = "#accountId")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getAllOptimizationRecommendations(String accountId) {
        CloudAccount account = getAccount(accountId);
        return getRegionStatusForAccount(account).thenCompose(activeRegions -> {
            logger.info("Fetching all optimization recommendations for account {}...", account.getAwsAccountId());
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2 = getEc2InstanceRecommendations(account, activeRegions);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebs = getEbsVolumeRecommendations(account, activeRegions);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambda = getLambdaFunctionRecommendations(account, activeRegions);
            return CompletableFuture.allOf(ec2, ebs, lambda).thenApply(v -> {
                List<DashboardData.OptimizationRecommendation> allRecs = Stream.of(ec2.join(), ebs.join(), lambda.join()).flatMap(List::stream).collect(Collectors.toList());
                logger.debug("Fetched a total of {} optimization recommendations for account {}", allRecs.size(), accountId);
                return allRecs;
            });
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "cloudlistResources", key = "#account.awsAccountId")
    public CompletableFuture<List<ResourceDto>> getAllResources(CloudAccount account) {
        return getRegionStatusForAccount(account).thenCompose(activeRegions -> {
            logger.info("Fetching all resources for Cloudlist (flat list) for account {}...", account.getAwsAccountId());

            List<CompletableFuture<List<ResourceDto>>> resourceFutures = List.of(
                    fetchEc2InstancesForCloudlist(account, activeRegions), fetchEbsVolumesForCloudlist(account, activeRegions),
                    fetchRdsInstancesForCloudlist(account, activeRegions), fetchLambdaFunctionsForCloudlist(account, activeRegions),
                    fetchVpcsForCloudlist(account, activeRegions), fetchSecurityGroupsForCloudlist(account, activeRegions),
                    fetchS3BucketsForCloudlist(account),
                    fetchLoadBalancersForCloudlist(account, activeRegions),
                    fetchAutoScalingGroupsForCloudlist(account, activeRegions), fetchElastiCacheClustersForCloudlist(account, activeRegions),
                    fetchDynamoDbTablesForCloudlist(account, activeRegions), fetchEcrRepositoriesForCloudlist(account, activeRegions),
                    fetchRoute53HostedZonesForCloudlist(account),
                    fetchCloudTrailsForCloudlist(account, activeRegions),
                    fetchAcmCertificatesForCloudlist(account, activeRegions), fetchCloudWatchLogGroupsForCloudlist(account, activeRegions),
                    fetchSnsTopicsForCloudlist(account, activeRegions), fetchSqsQueuesForCloudlist(account, activeRegions)
            );

            return CompletableFuture.allOf(resourceFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<ResourceDto> allResources = resourceFutures.stream()
                            .map(future -> future.getNow(Collections.emptyList()))
                            .flatMap(Collection::stream)
                            .collect(Collectors.toList());
                        logger.debug("Fetched a total of {} resources for Cloudlist for account {}", allResources.size(), account.getAwsAccountId());
                        return allResources;
                    });
        });
    }

    @CacheEvict(value = {"dashboardData", "cloudlistResources", "groupedCloudlistResources", "wastedResources", "regionStatus", "inventory", "cloudwatchStatus", "securityInsights", "ec2Recs", "costAnomalies", "ebsRecs", "lambdaRecs", "reservationAnalysis", "reservationPurchaseRecs", "billingSummary", "iamResources", "costHistory", "allRecommendations", "securityFindings", "serviceQuotas", "reservationPageData", "reservationInventory", "historicalReservationData", "reservationModificationRecs", "eksClusters", "k8sNodes", "k8sNamespaces", "k8sDeployments", "k8sPods", "finopsReport", "costByTag", "budgets", "taggingCompliance", "costByRegion"}, allEntries = true)
    public void clearAllCaches() {
        logger.info("All dashboard caches have been evicted.");
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "groupedCloudlistResources", key = "#accountId")
    public CompletableFuture<List<DashboardData.ServiceGroupDto>> getAllResourcesGrouped(String accountId) {
        CloudAccount account = getAccount(accountId);
        return getAllResources(account).thenApply(flatList -> {
            List<DashboardData.ServiceGroupDto> groupedList = flatList.stream()
                .collect(Collectors.groupingBy(ResourceDto::getType))
                .entrySet().stream()
                .map(e -> new DashboardData.ServiceGroupDto(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(DashboardData.ServiceGroupDto::getServiceType))
                .collect(Collectors.toList());
            logger.debug("Grouped Cloudlist resources into {} service groups for account {}", groupedList.size(), accountId);
            return groupedList;
        });
    }
    
    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
            .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchFunction.apply(regionStatus.getRegionId());
                } catch (Exception e) {
                    logger.error("Cloudlist sub-task failed for account {}: {} in region {}.", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                    return Collections.<T>emptyList();
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<T> allResources = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
                    logger.debug("Fetched a total of {} {} resources across all regions for account {}", allResources.size(), serviceName, account.getAwsAccountId());
                    return allResources;
                });
    }

    private CompletableFuture<List<ResourceDto>> fetchEc2InstancesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeInstances().reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .map(i -> new ResourceDto(i.instanceId(), getTagName(i.tags(), "N/A"), "EC2 Instance", i.placement().availabilityZone().replaceAll(".$", ""), i.state().nameAsString(), i.launchTime(), Map.of("Type", i.instanceTypeAsString(), "Image ID", i.imageId(), "VPC ID", i.vpcId(), "Private IP", i.privateIpAddress())))
                    .collect(Collectors.toList());
        }, "EC2 Instances");
    }

    private CompletableFuture<List<ResourceDto>> fetchEbsVolumesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeVolumes().volumes().stream()
                    .map(v -> new ResourceDto(v.volumeId(), getTagName(v.tags(), "N/A"), "EBS Volume", v.availabilityZone().replaceAll(".$", ""), v.stateAsString(), v.createTime(), Map.of("Size", v.size() + " GiB", "Type", v.volumeTypeAsString(), "Attached to", v.attachments().isEmpty() ? "N/A" : v.attachments().get(0).instanceId())))
                    .collect(Collectors.toList());
        }, "EBS Volumes");
    }

    private CompletableFuture<List<ResourceDto>> fetchRdsInstancesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            return rds.describeDBInstances().dbInstances().stream()
                    .map(i -> new ResourceDto(i.dbInstanceIdentifier(), i.dbInstanceIdentifier(), "RDS Instance", i.availabilityZone().replaceAll(".$", ""), i.dbInstanceStatus(), i.instanceCreateTime(), Map.of("Engine", i.engine() + " " + i.engineVersion(), "Class", i.dbInstanceClass(), "Multi-AZ", i.multiAZ().toString())))
                    .collect(Collectors.toList());
        }, "RDS Instances");
    }

    private CompletableFuture<List<ResourceDto>> fetchLambdaFunctionsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            LambdaClient lambda = awsClientProvider.getLambdaClient(account, regionId);
            return lambda.listFunctions().functions().stream()
                    .map(f -> {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                        Instant lastModified = ZonedDateTime.parse(f.lastModified(), formatter).toInstant();
                        return new ResourceDto(f.functionName(), f.functionName(), "Lambda Function", getRegionFromArn(f.functionArn()), "Active", lastModified, Map.of("Runtime", f.runtimeAsString(), "Memory", f.memorySize() + " MB", "Timeout", f.timeout() + "s"));
                    })
                    .collect(Collectors.toList());
        }, "Lambda Functions");
    }

    private CompletableFuture<List<ResourceDto>> fetchVpcsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeVpcs().vpcs().stream()
                    .map(v -> new ResourceDto(v.vpcId(), getTagName(v.tags(), v.vpcId()), "VPC", regionId, v.stateAsString(), null, Map.of("CIDR Block", v.cidrBlock(), "Is Default", v.isDefault().toString())))
                    .collect(Collectors.toList());
        }, "VPCs");
    }

    private CompletableFuture<List<ResourceDto>> fetchSecurityGroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeSecurityGroups().securityGroups().stream()
                    .map(sg -> new ResourceDto(sg.groupId(), sg.groupName(), "Security Group", regionId, "Available", null, Map.of("VPC ID", sg.vpcId(), "Inbound Rules", String.valueOf(sg.ipPermissions().size()), "Outbound Rules", String.valueOf(sg.ipPermissionsEgress().size()))))
                    .collect(Collectors.toList());
        }, "Security Groups");
    }
    
    private CompletableFuture<List<ResourceDto>> fetchLoadBalancersForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, regionId);
            return elbv2.describeLoadBalancers().loadBalancers().stream()
                    .map(lb -> new ResourceDto(lb.loadBalancerName(), lb.loadBalancerName(), "Load Balancer", lb.availabilityZones().isEmpty() ? regionId : lb.availabilityZones().get(0).zoneName().replaceAll(".$", ""), lb.state().codeAsString(), lb.createdTime(), Map.of("Type", lb.typeAsString(), "Scheme", lb.schemeAsString(), "VPC ID", lb.vpcId())))
                    .collect(Collectors.toList());
        }, "Load Balancers");
    }

    private CompletableFuture<List<ResourceDto>> fetchAutoScalingGroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, regionId);
            return asgClient.describeAutoScalingGroups().autoScalingGroups().stream()
                    .map(asg -> new ResourceDto(asg.autoScalingGroupName(), asg.autoScalingGroupName(), "Auto Scaling Group", asg.availabilityZones().isEmpty() ? regionId : asg.availabilityZones().get(0).replaceAll(".$", ""), "Active", asg.createdTime(), Map.of("Desired", asg.desiredCapacity().toString(), "Min", asg.minSize().toString(), "Max", asg.maxSize().toString())))
                    .collect(Collectors.toList());
        }, "Auto Scaling Groups");
    }

    private CompletableFuture<List<ResourceDto>> fetchElastiCacheClustersForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElastiCacheClient elastiCache = awsClientProvider.getElastiCacheClient(account, regionId);
            return elastiCache.describeCacheClusters().cacheClusters().stream()
                    .map(c -> new ResourceDto(c.cacheClusterId(), c.cacheClusterId(), "ElastiCache Cluster", c.preferredAvailabilityZone().replaceAll(".$", ""), c.cacheClusterStatus(), c.cacheClusterCreateTime(), Map.of("Engine", c.engine() + " " + c.engineVersion(), "NodeType", c.cacheNodeType(), "Nodes", c.numCacheNodes().toString())))
                    .collect(Collectors.toList());
        }, "ElastiCache Clusters");
    }

    private CompletableFuture<List<ResourceDto>> fetchDynamoDbTablesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            DynamoDbClient ddb = awsClientProvider.getDynamoDbClient(account, regionId);
            return ddb.listTables().tableNames().stream()
                    .map(tableName -> {
                        var tableDesc = ddb.describeTable(b -> b.tableName(tableName)).table();
                        return new ResourceDto(tableName, tableName, "DynamoDB Table", getRegionFromArn(tableDesc.tableArn()), tableDesc.tableStatusAsString(), tableDesc.creationDateTime(), Map.of("Items", tableDesc.itemCount().toString(), "Size (Bytes)", tableDesc.tableSizeBytes().toString()));
                    })
                    .collect(Collectors.toList());
        }, "DynamoDB Tables");
    }

    private CompletableFuture<List<ResourceDto>> fetchEcrRepositoriesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EcrClient ecr = awsClientProvider.getEcrClient(account, regionId);
            return ecr.describeRepositories().repositories().stream()
                    .map(r -> new ResourceDto(r.repositoryName(), r.repositoryName(), "ECR Repository", getRegionFromArn(r.repositoryArn()), "Available", r.createdAt(), Map.of("URI", r.repositoryUri())))
                    .collect(Collectors.toList());
        }, "ECR Repositories");
    }

    private CompletableFuture<List<ResourceDto>> fetchSnsTopicsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SnsClient sns = awsClientProvider.getSnsClient(account, regionId);
            return sns.listTopics().topics().stream()
                    .map(t -> new ResourceDto(t.topicArn(), t.topicArn().substring(t.topicArn().lastIndexOf(':') + 1), "SNS Topic", getRegionFromArn(t.topicArn()), "Active", null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "SNS Topics");
    }

    private CompletableFuture<List<ResourceDto>> fetchSqsQueuesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SqsClient sqs = awsClientProvider.getSqsClient(account, regionId);
            return sqs.listQueues().queueUrls().stream()
                    .map(queueUrl -> {
                        String[] arnParts = sqs.getQueueAttributes(req -> req.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN).split(":");
                        return new ResourceDto(queueUrl, arnParts[5], "SQS Queue", arnParts[3], "Active", null, Collections.emptyMap());
                    })
                    .collect(Collectors.toList());
        }, "SQS Queues");
    }

    private CompletableFuture<List<ResourceDto>> fetchCloudWatchLogGroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchLogsClient cwLogs = awsClientProvider.getCloudWatchLogsClient(account, regionId);
            return cwLogs.describeLogGroups().logGroups().stream()
                    .map(lg -> new ResourceDto(lg.arn(), lg.logGroupName(), "CloudWatch Log Group", getRegionFromArn(lg.arn()), "Active", Instant.ofEpochMilli(lg.creationTime()), Map.of("Retention (Days)", lg.retentionInDays() != null ? lg.retentionInDays().toString() : "Never Expire", "Stored Bytes", String.format("%,d", lg.storedBytes()))))
                    .collect(Collectors.toList());
        }, "CloudWatch Log Groups");
    }

    private CompletableFuture<List<ResourceDto>> fetchCloudTrailsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudTrailClient cloudTrail = awsClientProvider.getCloudTrailClient(account, regionId);
            return cloudTrail.describeTrails().trailList().stream()
                    .map(t -> new ResourceDto(t.trailARN(), t.name(), "CloudTrail", t.homeRegion(), "Active", null, Map.of("IsMultiRegion", t.isMultiRegionTrail().toString(), "S3Bucket", t.s3BucketName())))
                    .collect(Collectors.toList());
        }, "CloudTrails");
    }
    
    private CompletableFuture<List<ResourceDto>> fetchS3BucketsForCloudlist(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Fetching S3 buckets for account {}", account.getAwsAccountId());
            S3Client s3Lister = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                List<Bucket> buckets = s3Lister.listBuckets().buckets();
                logger.debug("Found {} S3 buckets to process for account {}", buckets.size(), account.getAwsAccountId());
                List<ResourceDto> resources = buckets.parallelStream().map(b -> {
                    String bucketRegion = "us-east-1";
                    try {
                        bucketRegion = s3Lister.getBucketLocation(req -> req.bucket(b.name())).locationConstraintAsString();
                        if (bucketRegion == null || bucketRegion.isEmpty()) bucketRegion = "us-east-1";
                    } catch (S3Exception e) {
                        String correctRegion = e.awsErrorDetails().sdkHttpResponse()
                                                .firstMatchingHeader("x-amz-bucket-region")
                                                .orElse(null);
                        if (correctRegion != null) {
                            bucketRegion = correctRegion;
                        } else {
                            logger.warn("Could not determine region for bucket {} from S3Exception. Sticking with default. Error: {}", b.name(), e.getMessage());
                        }
                    }
                    return new ResourceDto(b.name(), b.name(), "S3 Bucket", bucketRegion, "Available", b.creationDate(), Collections.emptyMap());
                }).collect(Collectors.toList());
                logger.debug("Successfully processed {} S3 buckets for account {}", resources.size(), account.getAwsAccountId());
                return resources;
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed for account {}: S3 Buckets.", account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchRoute53HostedZonesForCloudlist(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Route53Client r53 = awsClientProvider.getRoute53Client(account);
                List<ResourceDto> resources = r53.listHostedZones().hostedZones().stream()
                        .map(z -> new ResourceDto(z.id(), z.name(), "Route 53 Zone", "Global", "Available", null, Map.of("Type", z.config().privateZone() ? "Private" : "Public", "Record Count", z.resourceRecordSetCount().toString())))
                        .collect(Collectors.toList());
                logger.debug("Fetched {} Route 53 Hosted Zones for account {}", resources.size(), account.getAwsAccountId());
                return resources;
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed for account {}: Route 53 Zones.", account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchAcmCertificatesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AcmClient acm = awsClientProvider.getAcmClient(account, regionId);
            return acm.listCertificates().certificateSummaryList().stream()
                    .map(c -> new ResourceDto(c.certificateArn(), c.domainName(), "Certificate Manager", regionId, c.statusAsString(), c.createdAt(), Map.of("Type", c.typeAsString(), "InUse", c.inUse().toString())))
                    .collect(Collectors.toList());
        }, "ACM Certificates");
    }

    private String getRegionFromArn(String arn) { if (arn == null || arn.isBlank()) return "Unknown"; try { String[] parts = arn.split(":"); if (parts.length > 3) { String region = parts[3]; return region.isEmpty() ? "Global" : region; } return "Global"; } catch (Exception e) { logger.warn("Could not parse region from ARN: {}", arn); return this.configuredRegion; } }

    public Map<String, List<MetricDto>> getEc2InstanceMetrics(String accountId, String instanceId) {
        CloudAccount account = getAccount(accountId);
        String instanceRegion = findInstanceRegion(account, instanceId);
        if (instanceRegion == null) {
            logger.error("Could not determine region for EC2 instance {}. Cannot fetch metrics.", instanceId);
            return Collections.emptyMap();
        }
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, instanceRegion);
        logger.info("Fetching CloudWatch metrics for instance: {} in region {} for account {}", instanceId, instanceRegion, accountId);
        try {
            GetMetricDataRequest cpuRequest = buildMetricDataRequest(instanceId, "CPUUtilization", "AWS/EC2", "InstanceId");
            MetricDataResult cpuResult = cwClient.getMetricData(cpuRequest).metricDataResults().get(0);
            List<MetricDto> cpuDatapoints = buildMetricDtos(cpuResult);

            GetMetricDataRequest networkInRequest = buildMetricDataRequest(instanceId, "NetworkIn", "AWS/EC2", "InstanceId");
            MetricDataResult networkInResult = cwClient.getMetricData(networkInRequest).metricDataResults().get(0);
            List<MetricDto> networkInDatapoints = buildMetricDtos(networkInResult);

            return Map.of("CPUUtilization", cpuDatapoints, "NetworkIn", networkInDatapoints);
        } catch (Exception e) {
            logger.error("Failed to fetch metrics for instance {} in account {}", instanceId, accountId, e);
            return Collections.emptyMap();
        }
    }

    public Map<String, List<MetricDto>> getRdsInstanceMetrics(String accountId, String instanceId) {
        CloudAccount account = getAccount(accountId);
        String rdsRegion = findResourceRegion(account, "RDS", instanceId);
        if (rdsRegion == null) {
            logger.error("Could not determine region for RDS instance {}. Cannot fetch metrics.", instanceId);
            return Collections.emptyMap();
        }
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, rdsRegion);
        logger.info("Fetching CloudWatch metrics for RDS instance: {} in region {} for account {}", instanceId, rdsRegion, accountId);
        try {
            GetMetricDataRequest connectionsRequest = buildMetricDataRequest(instanceId, "DatabaseConnections", "AWS/RDS", "DBInstanceIdentifier");
            MetricDataResult connectionsResult = cwClient.getMetricData(connectionsRequest).metricDataResults().get(0);
            List<MetricDto> connectionsDatapoints = buildMetricDtos(connectionsResult);

            GetMetricDataRequest readIopsRequest = buildMetricDataRequest(instanceId, "ReadIOPS", "AWS/RDS", "DBInstanceIdentifier");
            MetricDataResult readIopsResult = cwClient.getMetricData(readIopsRequest).metricDataResults().get(0);
            List<MetricDto> readIopsDatapoints = buildMetricDtos(readIopsResult);

            GetMetricDataRequest writeIopsRequest = buildMetricDataRequest(instanceId, "WriteIOPS", "AWS/RDS", "DBInstanceIdentifier");
            MetricDataResult writeIopsResult = cwClient.getMetricData(writeIopsRequest).metricDataResults().get(0);
            List<MetricDto> writeIopsDatapoints = buildMetricDtos(writeIopsResult);

            return Map.of(
                "DatabaseConnections", connectionsDatapoints,
                "ReadIOPS", readIopsDatapoints,
                "WriteIOPS", writeIopsDatapoints
            );
        } catch (Exception e) {
            logger.error("Failed to fetch metrics for RDS instance {} in account {}", instanceId, accountId, e);
            return Collections.emptyMap();
        }
    }

    public Map<String, List<MetricDto>> getS3BucketMetrics(String accountId, String bucketName, String region) {
        CloudAccount account = getAccount(accountId);
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
        logger.info("Fetching CloudWatch metrics for S3 bucket: {} in region {} for account {}", bucketName, region, accountId);
        try {
            GetMetricDataRequest sizeRequest = buildMetricDataRequest(bucketName, "BucketSizeBytes", "AWS/S3", "BucketName");
            MetricDataResult sizeResult = cwClient.getMetricData(sizeRequest).metricDataResults().get(0);
            List<MetricDto> sizeDatapoints = buildMetricDtos(sizeResult);

            GetMetricDataRequest objectsRequest = buildMetricDataRequest(bucketName, "NumberOfObjects", "AWS/S3", "BucketName");
            MetricDataResult objectsResult = cwClient.getMetricData(objectsRequest).metricDataResults().get(0);
            List<MetricDto> objectsDatapoints = buildMetricDtos(objectsResult);

            return Map.of("BucketSizeBytes", sizeDatapoints, "NumberOfObjects", objectsDatapoints);
        } catch (Exception e) {
            logger.error("Failed to fetch metrics for S3 bucket {} in account {}", bucketName, accountId, e);
            return Collections.emptyMap();
        }
    }

    public Map<String, List<MetricDto>> getLambdaFunctionMetrics(String accountId, String functionName, String region) {
        CloudAccount account = getAccount(accountId);
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
        logger.info("Fetching CloudWatch metrics for Lambda function: {} in region {} for account {}", functionName, region, accountId);
        try {
            GetMetricDataRequest invocationsRequest = buildMetricDataRequest(functionName, "Invocations", "AWS/Lambda", "FunctionName");
            MetricDataResult invocationsResult = cwClient.getMetricData(invocationsRequest).metricDataResults().get(0);
            List<MetricDto> invocationsDatapoints = buildMetricDtos(invocationsResult);

            GetMetricDataRequest errorsRequest = buildMetricDataRequest(functionName, "Errors", "AWS/Lambda", "FunctionName");
            MetricDataResult errorsResult = cwClient.getMetricData(errorsRequest).metricDataResults().get(0);
            List<MetricDto> errorsDatapoints = buildMetricDtos(errorsResult);
            
            GetMetricDataRequest durationRequest = buildMetricDataRequest(functionName, "Duration", "AWS/Lambda", "FunctionName");
            MetricDataResult durationResult = cwClient.getMetricData(durationRequest).metricDataResults().get(0);
            List<MetricDto> durationDatapoints = buildMetricDtos(durationResult);

            return Map.of(
                "Invocations", invocationsDatapoints,
                "Errors", errorsDatapoints,
                "Duration", durationDatapoints
            );
        } catch (Exception e) {
            logger.error("Failed to fetch metrics for Lambda function {} in account {}", functionName, accountId, e);
            return Collections.emptyMap();
        }
    }


    private List<MetricDto> buildMetricDtos(MetricDataResult result) { List<Instant> timestamps = result.timestamps(); List<Double> values = result.values(); if (timestamps == null || values == null || timestamps.size() != values.size()) { return Collections.emptyList(); } return IntStream.range(0, timestamps.size()).mapToObj(i -> new MetricDto(timestamps.get(i), values.get(i))).collect(Collectors.toList()); }
    private GetMetricDataRequest buildMetricDataRequest(String resourceId, String metricName, String namespace, String dimensionName) { 
        Metric metric = Metric.builder().namespace(namespace).metricName(metricName).dimensions(Dimension.builder().name(dimensionName).value(resourceId).build()).build(); 
        MetricStat metricStat = MetricStat.builder().metric(metric).period(300).stat("Average").build(); 
        MetricDataQuery metricDataQuery = MetricDataQuery.builder().id(metricName.toLowerCase().replace(" ", "")).metricStat(metricStat).returnData(true).build(); 
        return GetMetricDataRequest.builder().startTime(Instant.now().minus(1, ChronoUnit.DAYS)).endTime(Instant.now()).metricDataQueries(metricDataQuery).scanBy(ScanBy.TIMESTAMP_DESCENDING).build(); 
    }

@Async("awsTaskExecutor")
    @Cacheable(value = "wastedResources", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("Fetching wasted resources for account {}...", account.getAwsAccountId());
        List<CompletableFuture<List<DashboardData.WastedResource>>> futures = List.of(
                findUnattachedEbsVolumes(account, activeRegions),
                findUnusedElasticIps(account, activeRegions),
                findOldSnapshots(account, activeRegions),
                findDeregisteredAmis(account, activeRegions),
                findIdleRdsInstances(account, activeRegions),
                findIdleLoadBalancers(account, activeRegions),
                findUnusedSecurityGroups(account, activeRegions),
                findIdleEc2Instances(account, activeRegions),
                findUnattachedEnis(account, activeRegions),
                // --- ADD THE NEW METHOD CALLS HERE ---
                findIdleEksClusters(account, activeRegions),
                findIdleEcsClusters(account, activeRegions),
                findUnderutilizedLambdaFunctions(account, activeRegions),
                findOldDbSnapshots(account, activeRegions),
                findUnusedCloudWatchLogGroups(account, activeRegions)
                // You can continue to add more methods here for VPC, RDS Proxy etc.
        );

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<DashboardData.WastedResource> allWasted = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
                    logger.debug("... found {} total wasted resources for account {}.", allWasted.size(), account.getAwsAccountId());
                    return allWasted;
                });
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnattachedEbsVolumes(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeVolumes(req -> req.filters(f -> f.name("status").values("available")))
                    .volumes().stream()
                    .map(volume -> {
                        double monthlyCost = calculateEbsMonthlyCost(volume, regionId);
                        return new DashboardData.WastedResource(volume.volumeId(), getTagName(volume), "EBS Volume", regionId, monthlyCost, "Unattached Volume");
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
                    .map(snapshot -> new DashboardData.WastedResource(snapshot.snapshotId(), getTagName(snapshot), "Snapshot", regionId, calculateSnapshotMonthlyCost(snapshot), "Older than 90 days"))
                    .collect(Collectors.toList());
        }, "Old Snapshots");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findDeregisteredAmis(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            DescribeImagesRequest imagesRequest = DescribeImagesRequest.builder().owners("self").build();
            return ec2.describeImages(imagesRequest).images().stream()
                    .filter(image -> image.state() != ImageState.AVAILABLE)
                    .map(image -> new DashboardData.WastedResource(image.imageId(), image.name(), "AMI", regionId, 0.5, "Deregistered or Failed State")) // Nominal cost
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
                                    .metric(Metric.builder().namespace("AWS/RDS").metricName("DatabaseConnections")
                                            .dimensions(Dimension.builder().name("DBInstanceIdentifier").value(dbInstance.dbInstanceIdentifier()).build()).build())
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
                .map(lb -> new DashboardData.WastedResource(lb.loadBalancerArn(), lb.loadBalancerName(), "Load Balancer", regionId, 17.0, "Idle Load Balancer (no targets)")) // Approx cost
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
                    // Simplified check: Assumes idle if node CPU is consistently low.
                    // A more robust check would aggregate pod metrics.
                    GetMetricDataRequest request = GetMetricDataRequest.builder()
                        .startTime(Instant.now().minus(14, ChronoUnit.DAYS))
                        .endTime(Instant.now())
                        .metricDataQueries(MetricDataQuery.builder()
                            .id("cpu")
                            .metricStat(MetricStat.builder()
                                .metric(Metric.builder()
                                    .namespace("ContainerInsights")
                                    .metricName("node_cpu_utilization")
                                    .dimensions(Dimension.builder().name("ClusterName").value(clusterName).build())
                                    .build())
                                .period(86400) // Daily average
                                .stat("Average")
                                .build())
                            .returnData(true)
                            .build())
                        .build();

                    List<MetricDataResult> results = cw.getMetricData(request).metricDataResults();
                    if (!results.isEmpty() && !results.get(0).values().isEmpty()) {
                        double avgCpu = results.get(0).values().stream().mapToDouble(Double::doubleValue).average().orElse(100.0);
                        if (avgCpu < 5.0) { // If average daily CPU over 14 days is < 5%
                            findings.add(new DashboardData.WastedResource(clusterName, clusterName, "EKS Cluster", regionId, 73.0, "Idle Cluster (low CPU)")); // Using fixed cost for NAT gateway as proxy
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
                        .namespace("AWS/Lambda")
                        .metricName("Invocations")
                        .dimensions(Dimension.builder().name("FunctionName").value(func.functionName()).build())
                        .startTime(Instant.now().minus(30, ChronoUnit.DAYS))
                        .endTime(Instant.now())
                        .period(2592000) // 30 days in seconds
                        .statistics(Statistic.SUM)
                        .build();
                    
                    List<Datapoint> datapoints = cw.getMetricStatistics(request).datapoints();
                    if (datapoints.isEmpty() || datapoints.get(0).sum() < 10) {
                        findings.add(new DashboardData.WastedResource(func.functionArn(), func.functionName(), "Lambda", regionId, 0.50, "Low Invocations (<10 in 30d)")); // Nominal cost
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
                // Initial cheap checks: created a while ago and is empty.
                if (lg.creationTime() < sixtyDaysAgoMillis && lg.storedBytes() == 0) {
                    findings.add(new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(), "Log Group", regionId, 0.0, "Empty and created over 60 days ago"));
                    continue;
                }

                // More expensive check for log groups that are not empty but might be unused
                try {
                    DescribeLogStreamsResponse response = logs.describeLogStreams(req -> req
                        .logGroupName(lg.logGroupName())
                        .orderBy("LastEventTime")
                        .descending(true)
                        .limit(1));

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
            logger.error("Could not get metrics for EC2 instance {} in account {}: {}", instance.instanceId(), account.getAwsAccountId(), e.getMessage());
        }
        return false;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnattachedEnis(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeNetworkInterfaces(req -> req.filters(f -> f.name("status").values("available")))
                    .networkInterfaces().stream()
                    .map(eni -> new DashboardData.WastedResource(eni.networkInterfaceId(), getTagName(eni.tagSet(), eni.networkInterfaceId()), "ENI", regionId, 0.0, "Unattached ENI"))
                    .collect(Collectors.toList());
        }, "Unattached ENIs");
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "cloudwatchStatus", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, regionId);
            return cwClient.describeAlarms().metricAlarms();
        }, "CloudWatch Alarms")
        .thenApply(allAlarms -> {
            long ok = allAlarms.stream().filter(a -> a.stateValueAsString().equals("OK")).count();
            long alarm = allAlarms.stream().filter(a -> a.stateValueAsString().equals("ALARM")).count();
            long insufficient = allAlarms.stream().filter(a -> a.stateValueAsString().equals("INSUFFICIENT_DATA")).count();
            DashboardData.CloudWatchStatus status = new DashboardData.CloudWatchStatus(ok, alarm, insufficient);
            
            dashboardUpdateService.sendUpdate(account.getAwsAccountId(), "cloudwatch", status);

            return status;
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "ec2Recs", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEc2InstanceRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            GetEc2InstanceRecommendationsRequest request = GetEc2InstanceRecommendationsRequest.builder().build();
            return co.getEC2InstanceRecommendations(request).instanceRecommendations().stream()
                    .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
                    .map(r -> {
                        InstanceRecommendationOption opt = r.recommendationOptions().get(0);
                        double savings = opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null && opt.savingsOpportunity().estimatedMonthlySavings().value() != null
                                ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0;
                        
                        double recommendedCost = pricingService.getEc2InstanceMonthlyPrice(opt.instanceType(), regionId);
                        double currentCost = recommendedCost + savings;

                        return new DashboardData.OptimizationRecommendation(
                            "EC2", 
                            r.instanceArn().split("/")[1], 
                            r.currentInstanceType(), 
                            opt.instanceType(),
                            savings,
                            r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", ")),
                            currentCost,
                            recommendedCost
                        );
                    })
                    .collect(Collectors.toList());
        }, "EC2 Recommendations");
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "costAnomalies", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomalies(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching cost anomalies for account {}...", account.getAwsAccountId());
        try {
            AnomalyDateInterval dateInterval = AnomalyDateInterval.builder()
                    .startDate(LocalDate.now().minusDays(60).toString())
                    .endDate(LocalDate.now().minusDays(1).toString()).build();
            GetAnomaliesRequest request = GetAnomaliesRequest.builder().dateInterval(dateInterval).build();
            List<Anomaly> anomalies = ce.getAnomalies(request).anomalies();
            return CompletableFuture.completedFuture(anomalies.stream()
                    .map(a -> new DashboardData.CostAnomaly(
                            a.anomalyId(), getServiceNameFromAnomaly(a), a.impact().totalImpact(),
                            LocalDate.parse(a.anomalyStartDate().substring(0, 10)),
                            a.anomalyEndDate() != null ? LocalDate.parse(a.anomalyEndDate().substring(0, 10)) : LocalDate.now()
                    ))
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch Cost Anomalies for account {}. This might be a permissions issue or the service is not enabled.", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "ebsRecs", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEbsVolumeRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            GetEbsVolumeRecommendationsRequest request = GetEbsVolumeRecommendationsRequest.builder().build();
            return co.getEBSVolumeRecommendations(request).volumeRecommendations().stream()
                    .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.volumeRecommendationOptions() != null && !r.volumeRecommendationOptions().isEmpty())
                    .map(r -> {
                        VolumeRecommendationOption opt = r.volumeRecommendationOptions().get(0);
                        double savings = opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0;
                        
                        double recommendedCost = pricingService.getEbsGbMonthPrice(regionId, opt.configuration().volumeType()) * opt.configuration().volumeSize();
                        double currentCost = savings + recommendedCost;

                        return new DashboardData.OptimizationRecommendation("EBS", r.volumeArn().split("/")[1],
                                r.currentConfiguration().volumeType() + " - " + r.currentConfiguration().volumeSize() + "GiB",
                                opt.configuration().volumeType() + " - " + opt.configuration().volumeSize() + "GiB",
                                savings,
                                r.finding().toString(),
                                currentCost,
                                recommendedCost
                                );
                    })
                    .collect(Collectors.toList());
        }, "EBS Recommendations");
    }
    
    @Async("awsTaskExecutor")
    @Cacheable(value = "lambdaRecs", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getLambdaFunctionRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        try {
            return fetchAllRegionalResources(account, activeRegions, regionId -> {
                ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
                GetLambdaFunctionRecommendationsRequest request = GetLambdaFunctionRecommendationsRequest.builder().build();
                return co.getLambdaFunctionRecommendations(request).lambdaFunctionRecommendations().stream()
                        .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.memorySizeRecommendationOptions() != null && !r.memorySizeRecommendationOptions().isEmpty())
                        .map(r -> {
                            LambdaFunctionMemoryRecommendationOption opt = r.memorySizeRecommendationOptions().get(0);
                            double savings = opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0;
                            // Lambda pricing is complex, this remains an estimate
                            double recommendedCost = savings > 0 ? savings * 1.5 : 0; 
                            double currentCost = recommendedCost + savings;

                            return new DashboardData.OptimizationRecommendation("Lambda",
                                    r.functionArn().substring(r.functionArn().lastIndexOf(':') + 1),
                                    r.currentMemorySize() + " MB", opt.memorySize() + " MB",
                                    savings,
                                    r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", ")),
                                    currentCost,
                                    recommendedCost);
                        })
                        .collect(Collectors.toList());
            }, "Lambda Recommendations");
        } catch (Exception e) {
            logger.error("Could not fetch Lambda Recommendations for account {}. This might be a permissions issue or Compute Optimizer is not enabled.", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationAnalysis", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.ReservationAnalysis> getReservationAnalysis(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching reservation analysis for account {}...", account.getAwsAccountId());
        try {
            String today = LocalDate.now().toString();
            String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
            DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();
            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder().timePeriod(last30Days).build();
            List<UtilizationByTime> utilizations = ce.getReservationUtilization(utilRequest).utilizationsByTime();
            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder().timePeriod(last30Days).build();
            List<CoverageByTime> coverages = ce.getReservationCoverage(covRequest).coveragesByTime();
            double utilizationPercentage = utilizations.isEmpty() || utilizations.get(0).total() == null ? 0.0 : Double.parseDouble(utilizations.get(0).total().utilizationPercentage());
            double coveragePercentage = coverages.isEmpty() || coverages.get(0).total() == null ? 0.0 : Double.parseDouble(coverages.get(0).total().coverageHours().coverageHoursPercentage());
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(utilizationPercentage, coveragePercentage));
        } catch (Exception e) {
            logger.error("Could not fetch reservation analysis data for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(0.0, 0.0));
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationPurchaseRecs", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> getReservationPurchaseRecommendations(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching RI purchase recommendations for account {}...", account.getAwsAccountId());
        try {
            GetReservationPurchaseRecommendationRequest request = GetReservationPurchaseRecommendationRequest.builder()
                    .lookbackPeriodInDays(LookbackPeriodInDays.SIXTY_DAYS).service("Amazon Elastic Compute Cloud - Compute").build();
            GetReservationPurchaseRecommendationResponse response = ce.getReservationPurchaseRecommendation(request);

            return CompletableFuture.completedFuture(response.recommendations().stream()
                    .filter(rec -> rec.recommendationDetails() != null && !rec.recommendationDetails().isEmpty())
                    .flatMap(rec -> rec.recommendationDetails().stream()
                            .map(details -> {
                                try {
                                    return new DashboardData.ReservationPurchaseRecommendation(
                                            getFieldValue(details, "instanceDetails"), getFieldValue(details, "recommendedNumberOfInstancesToPurchase"),
                                            getFieldValue(details, "recommendedNormalizedUnitsToPurchase"), getFieldValue(details, "minimumNormalizedUnitsToPurchase"),
                                            getFieldValue(details, "estimatedMonthlySavingsAmount"), getFieldValue(details, "estimatedMonthlyOnDemandCost"),
                                            getFieldValue(details, "estimatedMonthlyCost"), getTermValue(rec));
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
    @Cacheable(value = "billingSummary", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.BillingSummary>> getBillingSummary(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching billing summary for account {}...", account.getAwsAccountId());
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build()).build();
            return CompletableFuture.completedFuture(ce.getCostAndUsage(request).resultsByTime()
                    .stream().flatMap(r -> r.groups().stream())
                    .map(g -> new DashboardData.BillingSummary(g.keys().get(0), Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                    .filter(s -> s.getMonthToDateCost() > 0.01).collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch billing summary for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
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
    @Cacheable(value = "costHistory", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.CostHistory> getCostHistory(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching cost history for account {}...", account.getAwsAccountId());
        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        List<Boolean> anomalies = new ArrayList<>();
        try {
            for (int i = 5; i >= 0; i--) {
                LocalDate month = LocalDate.now().minusMonths(i);
                labels.add(month.format(DateTimeFormatter.ofPattern("MMM uuuu")));
                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder().start(month.withDayOfMonth(1).toString()).end(month.plusMonths(1).withDayOfMonth(1).toString()).build())
                        .granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();
                double currentCost = Double.parseDouble(ce.getCostAndUsage(req).resultsByTime().get(0).total().get("UnblendedCost").amount());
                costs.add(currentCost);

                boolean isAnomaly = false;
                if (costs.size() > 1) {
                    double previousCost = costs.get(costs.size() - 2);
                    if (previousCost > 100) {
                        double changePercent = ((currentCost - previousCost) / previousCost) * 100;
                        if (changePercent > 20) {
                            isAnomaly = true;
                        }
                    }
                }
                anomalies.add(isAnomaly);
            }
        } catch (Exception e) {
            logger.error("Could not fetch cost history for account {}", account.getAwsAccountId(), e);
        }
        
        return CompletableFuture.completedFuture(new DashboardData.CostHistory(labels, costs, anomalies));
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
    
    // --- HELPER METHODS ---
    private String getTagName(Volume volume) { return volume.hasTags() ? volume.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(volume.volumeId()) : volume.volumeId(); }
    private String getTagName(Snapshot snapshot) { return snapshot.hasTags() ? snapshot.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(snapshot.snapshotId()) : snapshot.snapshotId();  }
    public String getTagName(List<Tag> tags, String defaultName) { return tags.stream().filter(t -> t.key().equalsIgnoreCase("Name")).findFirst().map(Tag::value).orElse(defaultName); }
    private double calculateEbsMonthlyCost(Volume volume, String region) { double gbMonthPrice = pricingService.getEbsGbMonthPrice(region, volume.volumeTypeAsString()); return volume.size() * gbMonthPrice; }
    private double calculateSnapshotMonthlyCost(Snapshot snapshot) { if (snapshot.volumeSize() != null) return snapshot.volumeSize() * 0.05; return 0.0; }
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
    private String getServiceNameFromAnomaly(Anomaly anomaly) { if (anomaly.rootCauses() != null && !anomaly.rootCauses().isEmpty()) { RootCause rootCause = anomaly.rootCauses().get(0); if (rootCause.service() != null) return rootCause.service(); } return "Unknown Service"; }
    private String getFieldValue(Object details, String methodName) { try { Method method = details.getClass().getMethod(methodName); Object result = method.invoke(details); return result != null ? result.toString() : "0"; } catch (Exception e) { logger.debug("Could not access method {}: {}", methodName, e.getMessage()); return "N/A"; } }
    private String getTermValue(ReservationPurchaseRecommendation rec) { try { return rec.termInYears() != null ? rec.termInYears().toString() : "1 Year"; } catch (Exception e) { logger.debug("Could not determine term value", e); return "1 Year"; } }

    @Async("awsTaskExecutor")
    @Cacheable(value = "vpcListForCloudmap", key = "#accountId")
    public CompletableFuture<List<ResourceDto>> getVpcListForCloudmap(String accountId) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching list of VPCs for Cloudmap for account {}...", accountId);
        return getRegionStatusForAccount(account).thenCompose(activeRegions -> 
            fetchAllRegionalResources(account, activeRegions, regionId -> {
                Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
                return ec2.describeVpcs().vpcs().stream()
                        .map(v -> new ResourceDto(v.vpcId(), getTagName(v.tags(), v.vpcId()), "VPC", regionId, v.stateAsString(), null, Map.of("CIDR Block", v.cidrBlock(), "Is Default", v.isDefault().toString())))
                        .collect(Collectors.toList());
            }, "VPCs List for Cloudmap")
        );
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "graphData", key = "#accountId + '-' + #vpcId")
    public CompletableFuture<List<Map<String, Object>>> getGraphData(String accountId, String vpcId, String region) {
        CloudAccount account = getAccount(accountId);
        
        String effectiveRegion = (region != null && !region.isBlank()) ? region : this.configuredRegion;

        Ec2Client ec2 = awsClientProvider.getEc2Client(account, effectiveRegion);
        S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
        AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, effectiveRegion);
        RdsClient rds = awsClientProvider.getRdsClient(account, effectiveRegion);
        ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, effectiveRegion);

        logger.info("Fetching graph data for VPC ID: {} in region {} for account {}", vpcId, effectiveRegion, accountId);
        
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> elements = new ArrayList<>();
            try {
                if (vpcId == null || vpcId.isBlank()) {
                    logger.debug("No VPC selected, fetching S3 buckets for global view.");
                    s3.listBuckets().buckets().forEach(bucket -> {
                        Map<String, Object> bucketNode = new HashMap<>();
                        Map<String, Object> bucketData = new HashMap<>();
                        bucketData.put("id", bucket.name());
                        bucketData.put("label", bucket.name());
                        bucketData.put("type", "S3 Bucket");
                        bucketNode.put("data", bucketData);
                        elements.add(bucketNode);
                    });
                    return elements;
                }

                logger.debug("Fetching details for VPC: {}", vpcId);
                Vpc vpc = ec2.describeVpcs(r -> r.vpcIds(vpcId)).vpcs().get(0);
                Map<String, Object> vpcNode = new HashMap<>();
                Map<String, Object> vpcData = new HashMap<>();
                vpcData.put("id", vpc.vpcId());
                vpcData.put("label", getTagName(vpc.tags(), vpc.vpcId()));
                vpcData.put("type", "VPC");
                vpcNode.put("data", vpcData);
                elements.add(vpcNode);

                DescribeSubnetsRequest subnetsRequest = DescribeSubnetsRequest.builder().filters(f -> f.name("vpc-id").values(vpcId)).build();
                List<software.amazon.awssdk.services.ec2.model.Subnet> subnets = ec2.describeSubnets(subnetsRequest).subnets();
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

                ec2.describeInternetGateways(r -> r.filters(f -> f.name("attachment.vpc-id").values(vpcId)))
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

                ec2.describeNatGateways(r -> r.filter(f -> f.name("vpc-id").values(vpcId)))
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

                DescribeSecurityGroupsRequest sgsRequest = DescribeSecurityGroupsRequest.builder().filters(f -> f.name("vpc-id").values(vpcId)).build();
                ec2.describeSecurityGroups(sgsRequest).securityGroups().forEach(sg -> {
                    Map<String, Object> sgNode = new HashMap<>();
                    Map<String, Object> sgData = new HashMap<>();
                    sgData.put("id", sg.groupId());
                    sgData.put("label", sg.groupName());
                    sgData.put("type", "Security Group");
                    sgData.put("parent", vpc.vpcId());
                    sgNode.put("data", sgData);
                    elements.add(sgNode);
                });
                
                elbv2.describeLoadBalancers().loadBalancers().stream()
                    .filter(lb -> vpcId.equals(lb.vpcId()))
                    .forEach(lb -> {
                        Map<String, Object> lbNode = new HashMap<>();
                        Map<String, Object> lbData = new HashMap<>();
                        lbData.put("id", lb.loadBalancerArn());
                        lbData.put("label", lb.loadBalancerName());
                        lbData.put("type", "Load Balancer");
                        if (!lb.availabilityZones().isEmpty() && lb.availabilityZones().get(0).subnetId() != null) {
                            lbData.put("parent", lb.availabilityZones().get(0).subnetId());
                        } else {
                            lbData.put("parent", vpc.vpcId());
                        }
                        lbNode.put("data", lbData);
                        elements.add(lbNode);
                    });


                DescribeInstancesRequest instancesRequest = DescribeInstancesRequest.builder().filters(f -> f.name("vpc-id").values(vpcId)).build();
                ec2.describeInstances(instancesRequest).reservations().stream()
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

                rds.describeDBInstances().dbInstances().stream()
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
    @Cacheable(value = "vpcList", key = "#accountId")
    public CompletableFuture<List<Vpc>> getVpcList(String accountId) {
        CloudAccount account = getAccount(accountId);
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        logger.info("Fetching list of VPCs for account {}...", accountId);
        return CompletableFuture.supplyAsync(() -> ec2.describeVpcs().vpcs());
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "securityFindings", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.SecurityFinding>> getComprehensiveSecurityFindings(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("Starting comprehensive security scan for account {}...", account.getAwsAccountId());
        List<CompletableFuture<List<DashboardData.SecurityFinding>>> futures = List.of(
            findUsersWithoutMfa(account), findPublicS3Buckets(account), findUnrestrictedSecurityGroups(account, activeRegions),
            findVpcsWithoutFlowLogs(account, activeRegions), checkCloudTrailStatus(account, activeRegions), findUnusedIamRoles(account)
        );
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).flatMap(List::stream).collect(Collectors.toList()));
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
        
        return Math.max(0, (int) Math.round(score * 100 / 100.0));
    }


    private CompletableFuture<List<DashboardData.SecurityFinding>> findUsersWithoutMfa(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for IAM users without MFA...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            IamClient iam = awsClientProvider.getIamClient(account);
            try {
                iam.listUsers().users().forEach(user -> {
                    try {
                        if (user.passwordLastUsed() != null || iam.getLoginProfile(r -> r.userName(user.userName())).sdkHttpResponse().isSuccessful()) {
                            software.amazon.awssdk.services.iam.model.ListMfaDevicesResponse mfaDevicesResponse = iam.listMFADevices(r -> r.userName(user.userName()));
                            if (!mfaDevicesResponse.hasMfaDevices() || mfaDevicesResponse.mfaDevices().isEmpty()) {
                                findings.add(new DashboardData.SecurityFinding(user.userName(), "Global", "IAM", "High", "User has console access but MFA is not enabled.", "CIS AWS Foundations", "1.2"));
                            }
                        }
                    } catch (NoSuchEntityException e) {
                        // This is expected for users without a login profile, so we can ignore it.
                    }
                });
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not check for MFA on users.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findPublicS3Buckets(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for public S3 buckets...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            S3Client s3Lister = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                List<Bucket> buckets = s3Lister.listBuckets().buckets();
                buckets.parallelStream().forEach(bucket -> {
                    try {
                        String bucketName = bucket.name();
                        String bucketRegion = "us-east-1";
                        try {
                            bucketRegion = s3Lister.getBucketLocation(r -> r.bucket(bucketName)).locationConstraintAsString();
                            if (bucketRegion == null || bucketRegion.isEmpty()) {
                                bucketRegion = "us-east-1";
                            }
                        } catch (S3Exception e) {
                            bucketRegion = e.awsErrorDetails().sdkHttpResponse()
                                            .firstMatchingHeader("x-amz-bucket-region")
                                            .orElse("us-east-1");
                            logger.warn("Could not directly get location for bucket {}, falling back to region '{}' from exception.", bucketName, bucketRegion);
                        }
        
                        S3Client regionalS3Client = awsClientProvider.getS3Client(account, bucketRegion);
                        boolean isPublic = false;
                        String reason = "";
        
                        try {
                            GetPublicAccessBlockRequest pabRequest = GetPublicAccessBlockRequest.builder().bucket(bucketName).build();
                            PublicAccessBlockConfiguration pab = regionalS3Client.getPublicAccessBlock(pabRequest).publicAccessBlockConfiguration();
                            if (!pab.blockPublicAcls() || !pab.ignorePublicAcls() || !pab.blockPublicPolicy() || !pab.restrictPublicBuckets()) {
                                isPublic = true;
                                reason = "Public Access Block is not fully enabled.";
                            }
                        } catch (Exception e) {
                            logger.debug("Could not get Public Access Block for bucket {}. It might not be set. Checking ACLs. Error: {}", bucketName, e.getMessage());
                        }
        
                        if (!isPublic) {
                            try {
                                boolean hasPublicAcl = regionalS3Client.getBucketAcl(r -> r.bucket(bucketName)).grants().stream()
                                    .anyMatch(grant -> {
                                        String granteeUri = grant.grantee().uri();
                                        return (granteeUri != null && (granteeUri.endsWith("AllUsers") || granteeUri.endsWith("AuthenticatedUsers")))
                                            && (grant.permission() == Permission.READ || grant.permission() == Permission.WRITE || grant.permission() == Permission.FULL_CONTROL);
                                    });
                                if (hasPublicAcl) {
                                    isPublic = true;
                                    reason = "Bucket ACL grants public access.";
                                }
                            } catch (Exception e) {
                                 logger.warn("Could not check ACL for bucket {}: {}", bucketName, e.getMessage());
                            }
                        }
        
                        if (isPublic) {
                            findings.add(new DashboardData.SecurityFinding(bucketName, bucketRegion, "S3", "Critical", reason, "CIS AWS Foundations", "2.1.2"));
                        }
                    } catch (Exception e) {
                        logger.error("Failed to process S3 bucket security check for bucket: {}", bucket.name(), e);
                    }
                });
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not list S3 buckets.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findUnrestrictedSecurityGroups(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            logger.info("Security Scan for account {}: Checking for unrestricted security groups in {}...", account.getAwsAccountId(), regionId);
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            try {
                ec2.describeSecurityGroups().securityGroups().forEach(sg -> {
                    sg.ipPermissions().forEach(perm -> {
                        boolean openToWorld = perm.ipRanges().stream().anyMatch(ip -> "0.0.0.0/0".equals(ip.cidrIp()));
                        if (openToWorld) {
                            String description = String.format("Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) %s",
                                perm.fromPort() == null ? "ALL" : (Objects.equals(perm.fromPort(), perm.toPort()) ? perm.fromPort().toString() : perm.fromPort() + "-" + perm.toPort()));
                            findings.add(new DashboardData.SecurityFinding(sg.groupId(), regionId, "VPC", "Critical", description, "CIS AWS Foundations", "4.1"));
                        }
                    });
                });
            } catch (Exception e) {
                logger.error("Failed to check security groups in region {} for account {}", regionId, account.getAwsAccountId(), e);
            }
            return findings;
        }, "Unrestricted Security Groups");
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findVpcsWithoutFlowLogs(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            logger.info("Security Scan for account {}: Checking for VPCs without Flow Logs in {}...", account.getAwsAccountId(), regionId);
            try {
                Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
                Set<String> vpcsWithFlowLogs = ec2.describeFlowLogs().flowLogs().stream().map(FlowLog::resourceId).collect(Collectors.toSet());
                return ec2.describeVpcs().vpcs().stream()
                        .filter(vpc -> !vpcsWithFlowLogs.contains(vpc.vpcId()))
                        .map(vpc -> new DashboardData.SecurityFinding(vpc.vpcId(), regionId, "VPC", "Medium", "VPC does not have Flow Logs enabled.", "CIS AWS Foundations", "2.9"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Failed to check for VPC Flow Logs in region {} for account {}", regionId, account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        }, "VPCs without Flow Logs");
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> checkCloudTrailStatus(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking CloudTrail status...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            if (activeRegions.isEmpty()) {
                logger.warn("No active regions found for account {}, skipping CloudTrail check.", account.getAwsAccountId());
                return findings;
            }
            try {
                CloudTrailClient cloudTrail = awsClientProvider.getCloudTrailClient(account, activeRegions.get(0).getRegionId());
                List<Trail> trails = cloudTrail.describeTrails().trailList();
                if (trails.isEmpty()) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "CloudTrail", "Critical", "No CloudTrail trails are configured for the account.", "CIS AWS Foundations", "2.1"));
                    return findings;
                }
                boolean hasActiveMultiRegionTrail = trails.stream().anyMatch(t -> {
                    try {
                        boolean isLogging = cloudTrail.getTrailStatus(r -> r.name(t.name())).isLogging();
                        return t.isMultiRegionTrail() && isLogging;
                    } catch (Exception e) {
                        logger.warn("Could not get status for trail {}, assuming not logging.", t.name());
                        return false;
                    }
                });
                if (!hasActiveMultiRegionTrail) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "CloudTrail", "High", "No active, multi-region CloudTrail trail found.", "CIS AWS Foundations", "2.1"));
                }
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not check CloudTrail status.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findUnusedIamRoles(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for unused IAM roles...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            IamClient iam = awsClientProvider.getIamClient(account);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            try {
                iam.listRoles().roles().stream()
                        .filter(role -> !role.path().startsWith("/aws-service-role/"))
                        .forEach(role -> {
                            try {
                                Role lastUsed = iam.getRole(r -> r.roleName(role.roleName())).role();
                                if (lastUsed.roleLastUsed() == null || lastUsed.roleLastUsed().lastUsedDate() == null) {
                                    if (role.createDate().isBefore(ninetyDaysAgo)) {
                                        findings.add(new DashboardData.SecurityFinding(role.roleName(), "Global", "IAM", "Medium", "Role has never been used and was created over 90 days ago.", "Custom Best Practice", "IAM-001"));
                                    }
                                } else if (lastUsed.roleLastUsed().lastUsedDate().isBefore(ninetyDaysAgo)) {
                                    findings.add(new DashboardData.SecurityFinding(role.roleName(), "Global", "IAM", "Low", "Role has not been used in over 90 days.", "Custom Best Practice", "IAM-002"));
                                }
                            } catch (Exception e) {
                                logger.warn("Could not get last used info for role {} in account {}: {}", role.roleName(), account.getAwsAccountId(), e.getMessage());
                            }
                        });
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not check for unused IAM roles.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "finopsReport", key = "#accountId")
    public CompletableFuture<FinOpsReportDto> getFinOpsReport(String accountId) {
        CloudAccount account = getAccount(accountId);
        return getRegionStatusForAccount(account).thenCompose(activeRegions -> {
            logger.info("--- LAUNCHING ASYNC DATA FETCH FOR FINOPS REPORT for account {}---", account.getAwsAccountId());

            CompletableFuture<List<DashboardData.BillingSummary>> billingSummaryFuture = getBillingSummary(account);
            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = getWastedResources(account, activeRegions);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> rightsizingFuture = getAllOptimizationRecommendations(accountId);
            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = getCostAnomalies(account);
            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = getCostHistory(account);
            CompletableFuture<DashboardData.TaggingCompliance> taggingComplianceFuture = getTaggingCompliance(account, activeRegions);
            CompletableFuture<List<DashboardData.BudgetDetails>> budgetsFuture = getAccountBudgets(account);

            return CompletableFuture.allOf(billingSummaryFuture, wastedResourcesFuture, rightsizingFuture, anomaliesFuture, costHistoryFuture, taggingComplianceFuture, budgetsFuture)
                    .thenApply(v -> {
                        logger.info("--- ALL FINOPS DATA FETCHES COMPLETE, AGGREGATING NOW ---");

                        List<DashboardData.BillingSummary> billingSummary = billingSummaryFuture.join();
                        List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.join();
                        List<DashboardData.OptimizationRecommendation> rightsizingRecommendations = rightsizingFuture.join();
                         List<DashboardData.CostAnomaly> costAnomalies = anomaliesFuture.join();
                        DashboardData.CostHistory costHistory = costHistoryFuture.join();
                        DashboardData.TaggingCompliance taggingCompliance = taggingComplianceFuture.join();
                        List<DashboardData.BudgetDetails> budgets = budgetsFuture.join();

                        double mtdSpend = billingSummary.stream().mapToDouble(DashboardData.BillingSummary::getMonthToDateCost).sum();
                        double lastMonthSpend = 0.0;
                        if (costHistory.getLabels() != null && costHistory.getLabels().size() > 1) {
                            int lastMonthIndex = costHistory.getLabels().size() - 2;
                            if (lastMonthIndex >= 0 && lastMonthIndex < costHistory.getCosts().size()) {
                                lastMonthSpend = costHistory.getCosts().get(lastMonthIndex);
                            }
                        }
                        double daysInMonth = YearMonth.now().lengthOfMonth();
                        double currentDayOfMonth = LocalDate.now().getDayOfMonth();
                        double forecastedSpend = (currentDayOfMonth > 0) ? (mtdSpend / currentDayOfMonth) * daysInMonth : 0;
                        double rightsizingSavings = rightsizingRecommendations.stream().mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings).sum();
                        double wasteSavings = wastedResources.stream().mapToDouble(DashboardData.WastedResource::getMonthlySavings).sum();
                        double totalPotentialSavings = rightsizingSavings + wasteSavings;
                        FinOpsReportDto.Kpis kpis = new FinOpsReportDto.Kpis(mtdSpend, lastMonthSpend, forecastedSpend, totalPotentialSavings);
                        List<Map<String, Object>> costByService = billingSummary.stream().sorted(Comparator.comparingDouble(DashboardData.BillingSummary::getMonthToDateCost).reversed()).limit(10).map(s -> Map.<String, Object>of("service", s.getServiceName(), "cost", s.getMonthToDateCost())).collect(Collectors.toList());
                        List<Map<String, Object>> costByRegion = new ArrayList<>();
                        try { costByRegion = getCostByRegion(account).join(); }
                        catch (Exception e) { logger.error("Could not fetch cost by region data for FinOps report.", e); }
                        FinOpsReportDto.CostBreakdown costBreakdown = new FinOpsReportDto.CostBreakdown(costByService, costByRegion);

                        return new FinOpsReportDto(kpis, costBreakdown, rightsizingRecommendations, wastedResources, costAnomalies, taggingCompliance, budgets);
                    });
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "budgets", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.BudgetDetails>> getAccountBudgets(CloudAccount account) {
        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);
        logger.info("FinOps Scan: Fetching account budgets for account {}...", account.getAwsAccountId());
        try {
            DescribeBudgetsRequest request = DescribeBudgetsRequest.builder().accountId(account.getAwsAccountId()).build();
            List<Budget> budgets = budgetsClient.describeBudgets(request).budgets();

            return CompletableFuture.completedFuture(
                budgets.stream().map(b -> new DashboardData.BudgetDetails(
                    b.budgetName(), b.budgetLimit().amount(), b.budgetLimit().unit(),
                    b.calculatedSpend() != null ? b.calculatedSpend().actualSpend().amount() : BigDecimal.ZERO,
                    b.calculatedSpend() != null && b.calculatedSpend().forecastedSpend() != null ? b.calculatedSpend().forecastedSpend().amount() : BigDecimal.ZERO
                )).collect(Collectors.toList())
            );
        } catch (Exception e) {
            logger.error("Failed to fetch AWS Budgets for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    public void createBudget(String accountId, DashboardData.BudgetDetails budgetDetails) {
        CloudAccount account = getAccount(accountId);
        BudgetsClient budgetsClient = awsClientProvider.getBudgetsClient(account);
        logger.info("Creating new budget: {} for account {}", budgetDetails.getBudgetName(), account.getAwsAccountId());
        try {
            Budget budget = Budget.builder()
                .budgetName(budgetDetails.getBudgetName()).budgetType(BudgetType.COST).timeUnit("MONTHLY")
                .timePeriod(TimePeriod.builder().start(Instant.now()).build())
                .budgetLimit(Spend.builder().amount(budgetDetails.getBudgetLimit()).unit(budgetDetails.getBudgetUnit()).build()).build();
            CreateBudgetRequest request = CreateBudgetRequest.builder().accountId(account.getAwsAccountId()).budget(budget).build();
            budgetsClient.createBudget(request);
            clearFinOpsReportCache(accountId);
        } catch (Exception e) {
            logger.error("Failed to create AWS Budget '{}' for account {}", budgetDetails.getBudgetName(), account.getAwsAccountId(), e);
            throw new RuntimeException("Failed to create budget", e);
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "taggingCompliance", key = "#account.awsAccountId")
    public CompletableFuture<DashboardData.TaggingCompliance> getTaggingCompliance(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("FinOps Scan: Checking tagging compliance for account {}...", account.getAwsAccountId());

        CompletableFuture<List<ResourceDto>> allResourcesFuture = getAllResources(account);

        return allResourcesFuture.thenApply(allResources -> {
            List<DashboardData.UntaggedResource> untaggedList = new ArrayList<>();
            int taggedCount = 0;

            for (ResourceDto resource : allResources) {
                List<String> missingTags = new ArrayList<>(this.requiredTags);
                
                Map<String, String> resourceTags = resource.getDetails() != null ?
                    resource.getDetails().entrySet().stream()
                        .filter(e -> e.getKey().toLowerCase().startsWith("tag:"))
                        .collect(Collectors.toMap(e -> e.getKey().substring(4), Map.Entry::getValue))
                    : Collections.emptyMap();
                
                missingTags.removeAll(resourceTags.keySet());

                if (missingTags.isEmpty()) {
                    taggedCount++;
                } else {
                    untaggedList.add(new DashboardData.UntaggedResource(resource.getId(), resource.getType(), resource.getRegion(), missingTags));
                }
            }

            int totalScanned = allResources.size();
            double percentage = (totalScanned > 0) ? ((double) taggedCount / totalScanned) * 100.0 : 100.0;
            return new DashboardData.TaggingCompliance(percentage, totalScanned, untaggedList.size(), untaggedList.stream().limit(20).collect(Collectors.toList()));
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "costByTag", key = "#accountId + '-' + #tagKey")
    public CompletableFuture<List<Map<String, Object>>> getCostByTag(String accountId, String tagKey) {
        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching month-to-date cost by tag: {} for account {}", tagKey, accountId);
        if (tagKey == null || tagKey.isBlank()) return CompletableFuture.completedFuture(Collections.emptyList());
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(software.amazon.awssdk.services.costexplorer.model.Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build()).build();

            return CompletableFuture.completedFuture(ce.getCostAndUsage(request).resultsByTime()
                .stream().flatMap(r -> r.groups().stream())
                .map(g -> {
                    String tagValue = g.keys().get(0).isEmpty() ? "Untagged" : g.keys().get(0);
                    double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                    return Map.<String, Object>of("tagValue", tagValue, "cost", cost);
                })
                .filter(map -> (double) map.get("cost") > 0.01)
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch cost by tag key '{}' for account {}. This tag may not be activated in the billing console.", tagKey, accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "costByRegion", key = "#account.awsAccountId")
    public CompletableFuture<List<Map<String, Object>>> getCostByRegion(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching month-to-date cost by region for account {}...", account.getAwsAccountId());
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build()).build();
            List<Map<String, Object>> result = ce.getCostAndUsage(request).resultsByTime()
                .stream().flatMap(r -> r.groups().stream())
                .map(g -> {
                    String region = g.keys().get(0).isEmpty() ? "Unknown" : g.keys().get(0);
                    double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                    return Map.<String, Object>of("region", region, "cost", cost);
                })
                .filter(map -> (double) map.get("cost") > 0.01)
                .collect(Collectors.toList());
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            logger.error("Could not fetch cost by region for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @CacheEvict(value = {"finopsReport", "budgets"}, key = "#accountId")
    public void clearFinOpsReportCache(String accountId) {
        logger.info("FinOps-related caches for account {} have been evicted.", accountId);
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "serviceQuotas", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getServiceQuotaInfo(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
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
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .mapToInt(Integer::intValue)
                .sum());
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
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .mapToInt(Integer::intValue)
                .sum());
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
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .mapToInt(Integer::intValue)
                .sum());
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
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .mapToInt(Integer::intValue)
                .sum());
    }


    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationPageData", key = "#accountId")
    public CompletableFuture<ReservationDto> getReservationPageData(String accountId) {
        CloudAccount account = getAccount(accountId);
        return getRegionStatusForAccount(account).thenCompose(activeRegions -> {
            logger.info("--- LAUNCHING ASYNC DATA FETCH FOR RESERVATION PAGE for account {}---", account.getAwsAccountId());
            CompletableFuture<DashboardData.ReservationAnalysis> analysisFuture = getReservationAnalysis(account);
            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> purchaseRecsFuture = getReservationPurchaseRecommendations(account);
            CompletableFuture<List<ReservationInventoryDto>> inventoryFuture = getReservationInventory(account, activeRegions);
            CompletableFuture<HistoricalReservationDataDto> historicalDataFuture = getHistoricalReservationData(account);
            CompletableFuture<List<ReservationModificationRecommendationDto>> modificationRecsFuture = getReservationModificationRecommendations(account, activeRegions);

            return CompletableFuture.allOf(analysisFuture, purchaseRecsFuture, inventoryFuture, historicalDataFuture, modificationRecsFuture).thenApply(v -> {
                logger.info("--- RESERVATION PAGE DATA FETCH COMPLETE, COMBINING NOW ---");
                DashboardData.ReservationAnalysis analysis = analysisFuture.join();
                List<DashboardData.ReservationPurchaseRecommendation> recommendations = purchaseRecsFuture.join();
                List<ReservationInventoryDto> inventory = inventoryFuture.join();
                HistoricalReservationDataDto historicalData = historicalDataFuture.join();
                List<ReservationModificationRecommendationDto> modificationRecs = modificationRecsFuture.join();
                return new ReservationDto(analysis, recommendations, inventory, historicalData, modificationRecs);
            });
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationInventory", key = "#account.awsAccountId")
    public CompletableFuture<List<ReservationInventoryDto>> getReservationInventory(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            software.amazon.awssdk.services.ec2.model.Filter activeFilter = software.amazon.awssdk.services.ec2.model.Filter.builder().name("state").values("active").build();
            DescribeReservedInstancesRequest request = DescribeReservedInstancesRequest.builder()
                    .filters(activeFilter)
                    .build();
            return ec2.describeReservedInstances(request).reservedInstances().stream()
                    .map(ri -> new ReservationInventoryDto(
                        ri.reservedInstancesId(),
                        ri.offeringTypeAsString(),
                        ri.instanceTypeAsString(),
                        ri.scopeAsString(),
                        ri.availabilityZone(),
                        ri.duration(),
                        ri.start(),
                        ri.end(),
                        ri.instanceCount(),
                        ri.stateAsString()
                    ))
                    .collect(Collectors.toList());
        }, "Reservation Inventory");
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "historicalReservationData", key = "#account.awsAccountId")
    public CompletableFuture<HistoricalReservationDataDto> getHistoricalReservationData(CloudAccount account) {
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching historical reservation data for the last 6 months for account {}...", account.getAwsAccountId());
        try {
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(6).withDayOfMonth(1);
            DateInterval period = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.toString())
                    .build();

            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                    .timePeriod(period)
                    .granularity(Granularity.MONTHLY)
                    .build();
            List<UtilizationByTime> utilizations = ce.getReservationUtilization(utilRequest).utilizationsByTime();

            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder()
                    .timePeriod(period)
                    .granularity(Granularity.MONTHLY)
                    .build();
            List<CoverageByTime> coverages = ce.getReservationCoverage(covRequest).coveragesByTime();

            List<String> labels = utilizations.stream()
                    .map(u -> LocalDate.parse(u.timePeriod().start()).format(DateTimeFormatter.ofPattern("MMM uuuu")))
                    .collect(Collectors.toList());

            List<Double> utilPercentages = utilizations.stream()
                    .map(u -> Double.parseDouble(u.total().utilizationPercentage()))
                    .collect(Collectors.toList());

            List<Double> covPercentages = coverages.stream()
                    .map(c -> Double.parseDouble(c.total().coverageHours().coverageHoursPercentage()))
                    .collect(Collectors.toList());

            return CompletableFuture.completedFuture(new HistoricalReservationDataDto(labels, utilPercentages, covPercentages));

        } catch (Exception e) {
            logger.error("Could not fetch historical reservation data for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new HistoricalReservationDataDto(Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationModificationRecs", key = "#account.awsAccountId")
    public CompletableFuture<List<ReservationModificationRecommendationDto>> getReservationModificationRecommendations(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("Fetching reservation modification recommendations based on utilization for account {}...", account.getAwsAccountId());

        return getReservationInventory(account, activeRegions).thenCompose(activeReservations -> {
            if (activeReservations.isEmpty()) {
                logger.info("No active reservations found for account {}. Skipping modification check.", account.getAwsAccountId());
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            Map<String, ReservationInventoryDto> activeReservationsMap = activeReservations.stream()
                .collect(Collectors.toMap(ReservationInventoryDto::getReservationId, Function.identity()));

            CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
            DateInterval last30Days = DateInterval.builder()
                .start(LocalDate.now().minusDays(30).toString())
                .end(LocalDate.now().toString())
                .build();

            GroupDefinition groupByRiId = GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("RESERVATION_ID").build();

            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                .timePeriod(last30Days)
                .groupBy(groupByRiId)
                .build();

            try {
                List<ReservationUtilizationGroup> utilizationGroups = ce.getReservationUtilization(utilRequest).utilizationsByTime().get(0).groups();
                List<ReservationModificationRecommendationDto> recommendations = new ArrayList<>();
                for (ReservationUtilizationGroup group : utilizationGroups) {
                    String reservationId = group.attributes().get("reservationId");
                    double utilizationPercentage = Double.parseDouble(group.utilization().utilizationPercentage());

                    if (utilizationPercentage < 80.0 && activeReservationsMap.containsKey(reservationId)) {
                        ReservationInventoryDto ri = activeReservationsMap.get(reservationId);

                        if ("Convertible".equalsIgnoreCase(ri.getOfferingType())) {
                            String currentType = ri.getInstanceType();
                            String recommendedType = suggestSmallerInstanceType(currentType);

                            if (recommendedType != null && !recommendedType.equals(currentType)) {
                                    recommendations.add(new ReservationModificationRecommendationDto(
                                    ri.getReservationId(),
                                    currentType,
                                    recommendedType,
                                    String.format("Low Utilization (%.1f%%)", utilizationPercentage),
                                    50.0
                                ));
                            }
                        }
                    }
                }
                logger.info("Generated {} RI modification recommendations for account {}.", recommendations.size(), account.getAwsAccountId());
                return CompletableFuture.completedFuture(recommendations);
            } catch (Exception e) {
                logger.error("Could not generate reservation modification recommendations for account {}", account.getAwsAccountId(), e);
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
        });
    }

    private String suggestSmallerInstanceType(String instanceType) {
        String[] parts = instanceType.split("\\.");
        if (parts.length != 2) return null;
        String family = parts[0];
        String size = parts[1];

        int currentIndex = this.instanceSizeOrder.indexOf(size);
        if (currentIndex > 0) {
            return family + "." + this.instanceSizeOrder.get(currentIndex - 1);
        }
        return null;
    }

    public String applyReservationModification(String accountId, ReservationModificationRequestDto request) {
        CloudAccount account = getAccount(accountId);
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        logger.info("Attempting to modify reservation {} to type {} for account {}", request.getReservationId(), request.getTargetInstanceType(), accountId);

        DescribeReservedInstancesResponse riResponse = ec2.describeReservedInstances(r -> r.reservedInstancesIds(request.getReservationId()));
        if (riResponse.reservedInstances().isEmpty()) {
            throw new IllegalArgumentException("Reservation ID not found: " + request.getReservationId());
        }
        ReservedInstances originalRi = riResponse.reservedInstances().get(0);

        if (!"Convertible".equalsIgnoreCase(originalRi.offeringTypeAsString())) {
            throw new IllegalArgumentException("Cannot modify a non-convertible reservation.");
        }

        DescribeReservedInstancesOfferingsRequest offeringsRequest = DescribeReservedInstancesOfferingsRequest.builder()
                .instanceType(request.getTargetInstanceType())
                .productDescription(originalRi.productDescription())
                .offeringType("Convertible")
                .offeringClass(originalRi.offeringClass())
                .minDuration(originalRi.duration())
                .maxDuration(originalRi.duration())
                .includeMarketplace(false)
                .instanceTenancy(originalRi.instanceTenancy())
                .build();

        Optional<ReservedInstancesOffering> targetOffering = ec2.describeReservedInstancesOfferings(offeringsRequest).reservedInstancesOfferings()
                .stream().findFirst();

        if (targetOffering.isEmpty()) {
            throw new RuntimeException("Could not find a matching RI offering for type: " + request.getTargetInstanceType());
        }

        ReservedInstancesConfiguration targetConfig = ReservedInstancesConfiguration.builder()
                .instanceType(request.getTargetInstanceType())
                .instanceCount(request.getInstanceCount())
                .platform(originalRi.productDescriptionAsString())
                .availabilityZone(originalRi.availabilityZone())
                .build();

        ModifyReservedInstancesRequest modifyRequest = ModifyReservedInstancesRequest.builder()
                .clientToken(UUID.randomUUID().toString())
                .reservedInstancesIds(request.getReservationId())
                .targetConfigurations(targetConfig)
                .build();

        try {
            ModifyReservedInstancesResponse modifyResponse = ec2.modifyReservedInstances(modifyRequest);
            logger.info("Successfully submitted modification request for RI {}. Transaction ID: {}", request.getReservationId(), modifyResponse.reservedInstancesModificationId());

            clearAllCaches();

            return modifyResponse.reservedInstancesModificationId();
        } catch (Exception e) {
            logger.error("Failed to execute RI modification for ID {}: {}", request.getReservationId(), e.getMessage());
            throw new RuntimeException("AWS API call to modify reservation failed.", e);
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "reservationCostByTag", key = "#accountId + '-' + #tagKey")
    public CompletableFuture<List<CostByTagDto>> getReservationCostByTag(String accountId, String tagKey) {
        CloudAccount account = getAccount(accountId);
        CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);
        logger.info("Fetching reservation cost by tag: {} for account {}", tagKey, accountId);
        if (tagKey == null || tagKey.isBlank()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try {
            LocalDate start = LocalDate.now().withDayOfMonth(1);
            LocalDate end = LocalDate.now().plusMonths(1).withDayOfMonth(1);
            DateInterval period = DateInterval.builder().start(start.toString()).end(end.toString()).build();

            Expression filter = Expression.builder().dimensions(DimensionValues.builder()
                .key(software.amazon.awssdk.services.costexplorer.model.Dimension.PURCHASE_TYPE)
                .values("Reserved Instances")
                .build()).build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(period)
                .granularity(Granularity.MONTHLY)
                .metrics("AmortizedCost")
                .filter(filter)
                .groupBy(GroupDefinition.builder().type(GroupDefinitionType.TAG).key(tagKey).build())
                .build();

            List<ResultByTime> results = ce.getCostAndUsage(request).resultsByTime();

            return CompletableFuture.completedFuture(
                results.stream()
                    .flatMap(r -> r.groups().stream())
                    .map(g -> {
                        String tagValue = g.keys().isEmpty() || g.keys().get(0).isEmpty() ? "Untagged" : g.keys().get(0);
                        double cost = Double.parseDouble(g.metrics().get("AmortizedCost").amount());
                        return new CostByTagDto(tagValue, cost);
                    })
                    .filter(dto -> dto.getCost() > 0.01)
                    .collect(Collectors.toList())
            );
        } catch (Exception e) {
            logger.error("Could not fetch reservation cost by tag key '{}' for account {}", tagKey, accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "eksClusters", key = "#accountId")
    public CompletableFuture<List<K8sClusterInfo>> getEksClusterInfo(String accountId) {
        CloudAccount account = getAccount(accountId);
        EksClient eks = awsClientProvider.getEksClient(account, configuredRegion);
        logger.info("Fetching EKS cluster list for account {}...", account.getAwsAccountId());
        try {
            List<String> clusterNames = eks.listClusters().clusters();
            List<K8sClusterInfo> clusters = clusterNames.parallelStream().map(name -> {
                try {
                    Cluster cluster = getEksCluster(account, name);
                    String region = cluster.arn().split(":")[3];
                    return new K8sClusterInfo(name, cluster.statusAsString(), cluster.version(), region);
                } catch (Exception e) {
                    logger.error("Failed to describe EKS cluster {}", name, e);
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
            return CompletableFuture.completedFuture(clusters);
        } catch (Exception e) {
            logger.error("Could not list EKS clusters for account {}", accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "k8sNodes", key = "#accountId + '-' + #clusterName")
    public CompletableFuture<List<K8sNodeInfo>> getK8sNodes(String accountId, String clusterName) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching nodes for K8s cluster: {} in account {}", clusterName, accountId);
        try {
            CoreV1Api api = getCoreV1Api(account, clusterName);
            List<V1Node> nodeList = api.listNode(null, null, null, null, null, null, null, null, null, null).getItems();
            
            Cluster cluster = getEksCluster(account, clusterName);
            String region = cluster.arn().split(":")[3];

            return CompletableFuture.completedFuture(nodeList.stream().map(node -> {
                String status = node.getStatus().getConditions().stream()
                        .filter(c -> "Ready".equals(c.getType()))
                        .findFirst()
                        .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady")
                        .orElse("Unknown");
                
                Map<String, Map<String, Double>> metrics = getK8sNodeMetrics(account, clusterName, node.getMetadata().getName(), region);

                return new K8sNodeInfo(
                        node.getMetadata().getName(),
                        status,
                        node.getMetadata().getLabels().get("node.kubernetes.io/instance-type"),
                        node.getMetadata().getLabels().get("topology.kubernetes.io/zone"),
                        formatAge(node.getMetadata().getCreationTimestamp()),
                        node.getStatus().getNodeInfo().getKubeletVersion(),
                        metrics.get("cpu"),
                        metrics.get("memory")
                );
            }).collect(Collectors.toList()));
        } catch (ApiException e) {
            logger.error("Kubernetes API error while fetching nodes for cluster {}: {} - {}", clusterName, e.getCode(), e.getResponseBody(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        } catch (Exception e) {
            logger.error("Failed to get nodes for cluster {} in account {}", clusterName, accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private Map<String, Map<String, Double>> getK8sNodeMetrics(CloudAccount account, String clusterName, String nodeName, String region) {
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
        
        Map<String, Map<String, Double>> results = new HashMap<>();
        results.put("cpu", new HashMap<>());
        results.put("memory", new HashMap<>());

        try {
            List<String> metricNames = List.of("node_cpu_utilization", "node_memory_utilization", "node_cpu_limit", "node_memory_limit");
            
            List<MetricDataQuery> queries = metricNames.stream().map(metricName -> 
                MetricDataQuery.builder()
                    .id(metricName.replace("_", ""))
                    .metricStat(MetricStat.builder()
                        .metric(Metric.builder()
                            .namespace("ContainerInsights")
                            .metricName(metricName)
                            .dimensions(
                                Dimension.builder().name("ClusterName").value(clusterName).build(),
                                Dimension.builder().name("NodeName").value(nodeName).build()
                            )
                            .build())
                        .period(60)
                        .stat("Average")
                        .build())
                    .returnData(true)
                    .build()
            ).collect(Collectors.toList());

            GetMetricDataRequest request = GetMetricDataRequest.builder()
                .startTime(Instant.now().minus(5, ChronoUnit.MINUTES))
                .endTime(Instant.now())
                .metricDataQueries(queries)
                .build();

            GetMetricDataResponse response = cwClient.getMetricData(request);
            
            for (MetricDataResult result : response.metricDataResults()) {
                if (!result.values().isEmpty()) {
                    double value = result.values().get(0);
                    if ("nodecpuutilization".equals(result.id())) {
                        results.get("cpu").put("current", value);
                    } else if ("nodememoryutilization".equals(result.id())) {
                        results.get("memory").put("current", value);
                    } else if ("nodecpulimit".equals(result.id())) {
                        results.get("cpu").put("total", value / 1000); // Convert millicores to cores
                    } else if ("nodememorylimit".equals(result.id())) {
                        results.get("memory").put("total", value / (1024*1024*1024)); // Convert bytes to GiB
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Could not fetch Container Insights metrics for node {} in cluster {}", nodeName, clusterName, e);
        }
        return results;
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "k8sNamespaces", key = "#accountId + '-' + #clusterName")
    public CompletableFuture<List<String>> getK8sNamespaces(String accountId, String clusterName) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching namespaces for K8s cluster: {} in account {}", clusterName, accountId);
        try {
            CoreV1Api api = getCoreV1Api(account, clusterName);
            return CompletableFuture.completedFuture(api.listNamespace(null, null, null, null, null, null, null, null, null, null)
                    .getItems().stream()
                    .map(ns -> ns.getMetadata().getName())
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Failed to get namespaces for cluster {} in account {}", clusterName, accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "k8sDeployments", key = "#accountId + '-' + #clusterName + '-' + #namespace")
    public CompletableFuture<List<K8sDeploymentInfo>> getK8sDeployments(String accountId, String clusterName, String namespace) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching deployments in namespace {} for K8s cluster: {} in account {}", namespace, clusterName, accountId);
        try {
            AppsV1Api api = getAppsV1Api(account, clusterName);
            List<V1Deployment> deployments = api.listNamespacedDeployment(namespace, null, null, null, null, null, null, null, null, null, null).getItems();
            return CompletableFuture.completedFuture(deployments.stream().map(d -> {
                int available = d.getStatus().getAvailableReplicas() != null ? d.getStatus().getAvailableReplicas() : 0;
                int upToDate = d.getStatus().getUpdatedReplicas() != null ? d.getStatus().getUpdatedReplicas() : 0;
                String ready = (d.getStatus().getReadyReplicas() != null ? d.getStatus().getReadyReplicas() : 0) + "/" + d.getSpec().getReplicas();
                return new K8sDeploymentInfo(
                        d.getMetadata().getName(),
                        ready,
                        upToDate,
                        available,
                        formatAge(d.getMetadata().getCreationTimestamp())
                );
            }).collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Failed to get deployments for cluster {} in account {}", clusterName, accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "k8sPods", key = "#accountId + '-' + #clusterName + '-' + #namespace")
    public CompletableFuture<List<K8sPodInfo>> getK8sPods(String accountId, String clusterName, String namespace) {
        logger.info("Fetching pods in namespace {} for K8s cluster: {} in account {}", namespace, clusterName, accountId);
        CloudAccount account = getAccount(accountId);
        try {
            CoreV1Api api = getCoreV1Api(account, clusterName);
            List<V1Pod> pods = api.listNamespacedPod(namespace, null, null, null, null, null, null, null, null, null, null).getItems();
            return CompletableFuture.completedFuture(pods.stream().map(p -> {
                long readyContainers = p.getStatus() != null && p.getStatus().getContainerStatuses() != null ? p.getStatus().getContainerStatuses().stream().filter(cs -> cs.getReady()).count() : 0;
                int totalContainers = p.getSpec() != null && p.getSpec().getContainers() != null ? p.getSpec().getContainers().size() : 0;
                int restarts = p.getStatus() != null && p.getStatus().getContainerStatuses() != null ? p.getStatus().getContainerStatuses().stream().mapToInt(cs -> cs.getRestartCount()).sum() : 0;
                
                String cpu = "0 / 0";
                String memory = "0 / 0";

                if (p.getSpec() != null && p.getSpec().getContainers() != null && !p.getSpec().getContainers().isEmpty()) {
                    V1Container mainContainer = p.getSpec().getContainers().get(0);
                    if (mainContainer.getResources() != null) {
                        String cpuReq = formatResource(mainContainer.getResources().getRequests(), "cpu");
                        String cpuLim = formatResource(mainContainer.getResources().getLimits(), "cpu");
                        String memReq = formatResource(mainContainer.getResources().getRequests(), "memory");
                        String memLim = formatResource(mainContainer.getResources().getLimits(), "memory");
                        cpu = String.format("%s / %s", cpuReq, cpuLim);
                        memory = String.format("%s / %s", memReq, memLim);
                    }
                }

                return new K8sPodInfo(
                        p.getMetadata() != null ? p.getMetadata().getName() : "N/A",
                        readyContainers + "/" + totalContainers,
                        p.getStatus() != null ? p.getStatus().getPhase() : "Unknown",
                        restarts,
                        p.getMetadata() != null ? formatAge(p.getMetadata().getCreationTimestamp()) : "N/A",
                        p.getSpec() != null ? p.getSpec().getNodeName() : "N/A",
                        cpu,
                        memory
                );
            }).collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Failed to get pods for cluster {} in account {}", clusterName, accountId, e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private String formatResource(Map<String, io.kubernetes.client.custom.Quantity> resources, String type) {
        if (resources == null || !resources.containsKey(type)) {
            return "0";
        }
        return resources.get(type).toSuffixedString();
    }
    
    private Cluster getEksCluster(CloudAccount account, String clusterName) {
        EksClient eks = awsClientProvider.getEksClient(account, configuredRegion);
        return eks.describeCluster(r -> r.name(clusterName)).cluster();
    }

    private CoreV1Api getCoreV1Api(CloudAccount account, String clusterName) throws IOException {
        ApiClient apiClient = buildK8sApiClient(account, clusterName);
        return new CoreV1Api(apiClient);
    }

    private AppsV1Api getAppsV1Api(CloudAccount account, String clusterName) throws IOException {
        ApiClient apiClient = buildK8sApiClient(account, clusterName);
        return new AppsV1Api(apiClient);
    }

    private ApiClient buildK8sApiClient(CloudAccount account, String clusterName) throws IOException {
        Cluster cluster = getEksCluster(account, clusterName);
        ApiClient apiClient = ClientBuilder
                .kubeconfig(KubeConfig.loadKubeConfig(new FileReader(System.getProperty("user.home") + "/.kube/config")))
                .setBasePath(cluster.endpoint())
                .setVerifyingSsl(true)
                .setCertificateAuthority(Base64.getDecoder().decode(cluster.certificateAuthority().data()))
                .build();
        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(apiClient);
        return apiClient;
    }

    private String formatAge(OffsetDateTime creationTimestamp) { if (creationTimestamp == null) return "N/A"; Duration duration = Duration.between(creationTimestamp, OffsetDateTime.now()); long days = duration.toDays(); if (days > 0) return days + "d"; long hours = duration.toHours(); if (hours > 0) return hours + "h"; long minutes = duration.toMinutes(); if (minutes > 0) return minutes + "m"; return duration.toSeconds() + "s"; }

    public URL generateCloudFormationUrl(String accountName, String accessType, Long clientId) throws Exception {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found with id: " + clientId));

        String externalId = UUID.randomUUID().toString();
        CloudAccount newAccount = new CloudAccount(accountName, externalId, accessType, client);
        cloudAccountRepository.save(newAccount);
        String stackName = "XamOps-Connection-" + accountName.replaceAll("[^a-zA-Z0-9-]", "");
        String xamopsAccountId = this.hostAccountId;
        String urlString = String.format("https://console.aws.amazon.com/cloudformation/home#/stacks/create/review?templateURL=%s&stackName=%s&param_XamOpsAccountId=%s&param_ExternalId=%s", cloudFormationTemplateUrl, stackName, xamopsAccountId, externalId);
        return new URL(urlString);
    }
    
    public CloudAccount verifyAccount(VerifyAccountRequest request) {
        CloudAccount account = cloudAccountRepository.findByExternalId(request.getExternalId()).orElseThrow(() -> new RuntimeException("No pending account found for the given external ID."));
        if (!"PENDING".equals(account.getStatus())) {
            throw new RuntimeException("Account is not in a PENDING state.");
        }
        String roleArn = String.format("arn:aws:iam::%s:role/%s", request.getAwsAccountId(), request.getRoleName());
        account.setAwsAccountId(request.getAwsAccountId());
        account.setRoleArn(roleArn);
        try {
            Ec2Client testClient = awsClientProvider.getEc2Client(account, configuredRegion);
            testClient.describeRegions();
            account.setStatus("CONNECTED");
            logger.info("Successfully verified and connected to account: {}", account.getAccountName());
        } catch (Exception e) {
            account.setStatus("FAILED");
            logger.error("Failed to verify account {}: {}", account.getAccountName(), e.getMessage());
            throw new RuntimeException("Role assumption failed. Please check the role ARN and external ID.", e);
        }
        return cloudAccountRepository.save(account);
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "resourceDetail", key = "#accountId + '-' + #service + '-' + #resourceId")
    public CompletableFuture<ResourceDetailDto> getResourceDetails(String accountId, String service, String resourceId) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching LIVE details for resource: {} (Service: {}) in account {}", resourceId, service, accountId);

        switch (service) {
            case "EC2 Instance":
                return getEc2InstanceDetails(account, resourceId);
            case "RDS Instance":
                return getRdsInstanceDetails(account, resourceId);
            case "S3 Bucket":
                return getS3BucketDetails(account, resourceId);
            case "EBS Volume":
                 return getEbsVolumeDetails(account, resourceId);
            default:
                logger.warn("Live data fetching is not implemented for service: {}", service);
                CompletableFuture<ResourceDetailDto> future = new CompletableFuture<>();
                future.completeExceptionally(new UnsupportedOperationException("Live data fetching is not yet implemented for service: " + service));
                return future;
        }
    }

    private CompletableFuture<ResourceDetailDto> getEc2InstanceDetails(CloudAccount account, String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String instanceRegion = findInstanceRegion(account, resourceId);
                if (instanceRegion == null) {
                    throw new RuntimeException("Could not find region for instance: " + resourceId);
                }

                Ec2Client ec2 = awsClientProvider.getEc2Client(account, instanceRegion);
                DescribeInstancesRequest request = DescribeInstancesRequest.builder().instanceIds(resourceId).build();
                Instance instance = ec2.describeInstances(request).reservations().get(0).instances().get(0);

                CompletableFuture<Map<String, List<MetricDto>>> metricsFuture = CompletableFuture.supplyAsync(() -> getEc2InstanceMetrics(account.getAwsAccountId(), resourceId, instanceRegion));
                CompletableFuture<List<ResourceDetailDto.CloudTrailEventDto>> eventsFuture = CompletableFuture.supplyAsync(() -> getCloudTrailEventsForResource(account, resourceId, instanceRegion));

                Map<String, String> details = new HashMap<>();
                details.put("Instance Type", instance.instanceTypeAsString());
                details.put("VPC ID", instance.vpcId());
                details.put("Subnet ID", instance.subnetId());
                details.put("AMI ID", instance.imageId());
                details.put("Private IP", instance.privateIpAddress());
                details.put("Public IP", instance.publicIpAddress());

                List<Map.Entry<String, String>> tags = instance.tags().stream()
                        .map(tag -> Map.entry(tag.key(), tag.value()))
                        .collect(Collectors.toList());

                return new ResourceDetailDto(
                        instance.instanceId(),
                        getTagName(instance.tags(), instance.instanceId()),
                        "EC2 Instance",
                        instanceRegion,
                        instance.state().nameAsString(),
                        instance.launchTime(),
                        details,
                        tags,
                        metricsFuture.join(),
                        eventsFuture.join()
                );
            } catch (Exception e) {
                logger.error("Failed to fetch live details for EC2 instance {}", resourceId, e);
                throw new CompletionException(e);
            }
        });
    }

    private CompletableFuture<ResourceDetailDto> getRdsInstanceDetails(CloudAccount account, String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String rdsRegion = findResourceRegion(account, "RDS", resourceId);
                 if (rdsRegion == null) {
                    throw new RuntimeException("Could not find region for RDS instance: " + resourceId);
                }

                RdsClient rds = awsClientProvider.getRdsClient(account, rdsRegion);
                software.amazon.awssdk.services.rds.model.DBInstance dbInstance = rds.describeDBInstances(r -> r.dbInstanceIdentifier(resourceId)).dbInstances().get(0);
                List<software.amazon.awssdk.services.rds.model.Tag> rdsTags = rds.listTagsForResource(r -> r.resourceName(dbInstance.dbInstanceArn())).tagList();

                Map<String, String> details = new HashMap<>();
                details.put("DB Engine", dbInstance.engine() + " " + dbInstance.engineVersion());
                details.put("Instance Class", dbInstance.dbInstanceClass());
                details.put("Multi-AZ", dbInstance.multiAZ().toString());
                details.put("Storage", dbInstance.allocatedStorage() + " GiB");
                details.put("Endpoint", dbInstance.endpoint() != null ? dbInstance.endpoint().address() : "N/A");

                List<Map.Entry<String, String>> tags = rdsTags.stream()
                        .map(tag -> Map.entry(tag.key(), tag.value()))
                        .collect(Collectors.toList());
                
                return new ResourceDetailDto(
                    dbInstance.dbInstanceIdentifier(), dbInstance.dbInstanceIdentifier(), "RDS Instance",
                    rdsRegion, dbInstance.dbInstanceStatus(), dbInstance.instanceCreateTime(),
                    details, tags, Collections.emptyMap(), Collections.emptyList()
                );
            } catch (Exception e) {
                logger.error("Failed to fetch live details for RDS instance {}", resourceId, e);
                throw new CompletionException(e);
            }
        });
    }

    private CompletableFuture<ResourceDetailDto> getS3BucketDetails(CloudAccount account, String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                String bucketRegion = s3.getBucketLocation(r -> r.bucket(resourceId)).locationConstraintAsString();
                if (bucketRegion == null) bucketRegion = "us-east-1";

                HeadBucketResponse head = s3.headBucket(r -> r.bucket(resourceId));
                Instant creationDate = head.sdkHttpResponse().headers().get("Date").stream().findFirst()
                    .map(d -> ZonedDateTime.parse(d, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                    .orElse(null);


                Map<String, String> details = new HashMap<>();
                details.put("Region", bucketRegion);
                
                return new ResourceDetailDto(
                    resourceId, resourceId, "S3 Bucket",
                    bucketRegion, "Available", creationDate,
                    details, Collections.emptyList(), 
                    Collections.emptyMap(), Collections.emptyList()
                );

            } catch (Exception e) {
                logger.error("Failed to fetch live details for S3 bucket {}", resourceId, e);
                throw new CompletionException(e);
            }
        });
    }

    private CompletableFuture<ResourceDetailDto> getEbsVolumeDetails(CloudAccount account, String resourceId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String ebsRegion = findResourceRegion(account, "EBS", resourceId);
                 if (ebsRegion == null) {
                    throw new RuntimeException("Could not find region for EBS volume: " + resourceId);
                }

                Ec2Client ec2 = awsClientProvider.getEc2Client(account, ebsRegion);
                DescribeVolumesResponse response = ec2.describeVolumes(r -> r.volumeIds(resourceId));
                if (!response.hasVolumes() || response.volumes().isEmpty()) {
                     throw new RuntimeException("EBS Volume not found: " + resourceId);
                }
                Volume volume = response.volumes().get(0);

                Map<String, String> details = new HashMap<>();
                details.put("Size", volume.size() + " GiB");
                details.put("Type", volume.volumeTypeAsString());
                details.put("IOPS", volume.iops() != null ? volume.iops().toString() : "N/A");
                details.put("Throughput", volume.throughput() != null ? volume.throughput().toString() : "N/A");
                details.put("Attached To", volume.attachments().isEmpty() ? "N/A" : volume.attachments().get(0).instanceId());

                List<Map.Entry<String, String>> tags = volume.tags().stream()
                        .map(tag -> Map.entry(tag.key(), tag.value()))
                        .collect(Collectors.toList());

                return new ResourceDetailDto(
                    volume.volumeId(),
                    getTagName(volume.tags(), volume.volumeId()),
                    "EBS Volume",
                    ebsRegion, 
                    volume.stateAsString(), 
                    volume.createTime(),
                    details, 
                    tags, 
                    Collections.emptyMap(), 
                    Collections.emptyList()
                );
            } catch (Exception e) {
                logger.error("Failed to fetch live details for EBS volume {}", resourceId, e);
                throw new CompletionException(e);
            }
        });
    }

    private String findResourceRegion(CloudAccount account, String serviceType, String resourceId) {
        logger.debug("Attempting to find region for {} resource {}", serviceType, resourceId);
        List<DashboardData.RegionStatus> activeRegions = getRegionStatusForAccount(account).join();

        for (DashboardData.RegionStatus region : activeRegions) {
            try {
                switch (serviceType) {
                    case "RDS":
                        RdsClient rds = awsClientProvider.getRdsClient(account, region.getRegionId());
                        if (rds.describeDBInstances(r -> r.dbInstanceIdentifier(resourceId)).hasDbInstances()) {
                             logger.info("Found RDS instance {} in region {}", resourceId, region.getRegionId());
                            return region.getRegionId();
                        }
                        break;
                    case "EBS":
                         Ec2Client ec2 = awsClientProvider.getEc2Client(account, region.getRegionId());
                         if (ec2.describeVolumes(r -> r.volumeIds(resourceId)).hasVolumes()) {
                            logger.info("Found EBS volume {} in region {}", resourceId, region.getRegionId());
                            return region.getRegionId();
                         }
                         break;
                }
            } catch (Exception e) {
                logger.trace("{} {} not found in region {}: {}", serviceType, resourceId, region.getRegionId(), e.getMessage());
            }
        }
        logger.warn("Could not find {} resource {} in any active region.", serviceType, resourceId);
        return configuredRegion;
    }

    private String findInstanceRegion(CloudAccount account, String instanceId) {
        logger.debug("Attempting to find region for instance {}", instanceId);
        
        Ec2Client baseEc2Client = awsClientProvider.getEc2Client(account, "us-east-1");
        List<Region> allPossibleRegions = baseEc2Client.describeRegions().regions();

        for (Region region : allPossibleRegions) {
            if ("not-opted-in".equals(region.optInStatus())) {
                continue;
            }
            try {
                Ec2Client regionEc2Client = awsClientProvider.getEc2Client(account, region.regionName());
                DescribeInstancesRequest request = DescribeInstancesRequest.builder().instanceIds(instanceId).build();
                DescribeInstancesResponse response = regionEc2Client.describeInstances(request);

                if (response.hasReservations() && !response.reservations().isEmpty()) {
                    logger.info("Found instance {} in region {}", instanceId, region.regionName());
                    return region.regionName();
                }
            } catch (Exception e) {
                logger.trace("Instance {} not found in region {}: {}", instanceId, region.regionName(), e.getMessage());
            }
        }
        
        logger.warn("Could not find instance {} in any region.", instanceId);
        return configuredRegion; 
    }

    public Map<String, List<MetricDto>> getEc2InstanceMetrics(String accountId, String instanceId, String region) {
        CloudAccount account = getAccount(accountId);
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
        logger.info("Fetching CloudWatch metrics for instance: {} in region {} for account {}", instanceId, region, accountId);
        try {
            GetMetricDataRequest cpuRequest = buildMetricDataRequest(instanceId, "CPUUtilization", "AWS/EC2", "InstanceId");
            MetricDataResult cpuResult = cwClient.getMetricData(cpuRequest).metricDataResults().get(0);
            List<MetricDto> cpuDatapoints = buildMetricDtos(cpuResult);

            GetMetricDataRequest networkInRequest = buildMetricDataRequest(instanceId, "NetworkIn", "AWS/EC2", "InstanceId");
            MetricDataResult networkInResult = cwClient.getMetricData(networkInRequest).metricDataResults().get(0);
            List<MetricDto> networkInDatapoints = buildMetricDtos(networkInResult);

            return Map.of("CPUUtilization", cpuDatapoints, "NetworkIn", networkInDatapoints);
        } catch (Exception e) {
            logger.error("Failed to fetch metrics for instance {} in account {}", instanceId, accountId, e);
            return Collections.emptyMap();
        }
    }

    private List<ResourceDetailDto.CloudTrailEventDto> getCloudTrailEventsForResource(CloudAccount account, String resourceId, String region) {
        logger.info("Fetching CloudTrail events for resource {} in region {}", resourceId, region);
        CloudTrailClient trailClient = awsClientProvider.getCloudTrailClient(account, region);
        try {
            LookupAttribute lookupAttribute = LookupAttribute.builder()
                    .attributeKey("ResourceName")
                    .attributeValue(resourceId)
                    .build();

            LookupEventsRequest request = LookupEventsRequest.builder()
                    .lookupAttributes(lookupAttribute)
                    .maxResults(10)
                    .build();

            return trailClient.lookupEvents(request).events().stream()
                    .map(this::mapToCloudTrailEventDto)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Could not fetch CloudTrail events for resource {}", resourceId, e);
            return new ArrayList<>();
        }
    }
    
    private ResourceDetailDto.CloudTrailEventDto mapToCloudTrailEventDto(Event event) {
        return new ResourceDetailDto.CloudTrailEventDto(
                event.eventId(),
                event.eventName(),
                event.eventTime(),
                event.username(),
                "N/A", 
event.readOnly() != null && Boolean.parseBoolean(event.readOnly()) 
       );
    }

    
}