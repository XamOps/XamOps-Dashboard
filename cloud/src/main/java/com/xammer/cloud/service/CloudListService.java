package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CloudListService {

    private static final Logger logger = LoggerFactory.getLogger(CloudListService.class);

    private final String configuredRegion;
    private final CloudAccountRepository cloudAccountRepository;
    public final AwsClientProvider awsClientProvider;
    private final Map<String, double[]> regionCoordinates = new HashMap<>();
    private static final Set<String> SUSTAINABLE_REGIONS = Set.of("eu-west-1", "eu-north-1", "ca-central-1", "us-west-2");


    @Autowired
    public CloudListService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        loadRegionCoordinates();
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    private void loadRegionCoordinates() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            URL url = new URL("https://raw.githubusercontent.com/sunshower-io/provider-lists/master/aws/output/regions.json");
            JsonNode root = mapper.readTree(url);
            JsonNode regionsNode = root.get("regions");
            if (regionsNode != null && regionsNode.isArray()) {
                for (JsonNode regionNode : regionsNode) {
                    String regionKey = regionNode.path("key").asText();
                    JsonNode coords = regionNode.path("coordinates");
                    if (!regionKey.isEmpty() && coords.isObject()) {
                        double latitude = coords.path("latitude").asDouble();
                        double longitude = coords.path("longitude").asDouble();
                        this.regionCoordinates.put(regionKey, new double[]{latitude, longitude});
                    }
                }
                logger.info("Successfully loaded {} region coordinates from external source.", this.regionCoordinates.size());
            }
        } catch (IOException e) {
            logger.error("Failed to load region coordinates from external source. Map data will be unavailable.", e);
        }
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

    @Async("awsTaskExecutor")
    @Cacheable(value = "regionStatus", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForAccount(CloudAccount account) {
        logger.info("Fetching status for all available and active AWS regions for account {}...", account.getAwsAccountId());
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        try {
            Set<String> s3Regions = new HashSet<>();
            S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                for (Bucket bucket : s3.listBuckets().buckets()) {
                    String bucketRegion = "us-east-1";
                    try {
                        bucketRegion = s3.getBucketLocation(req -> req.bucket(bucket.name())).locationConstraintAsString();
                        if (bucketRegion == null || bucketRegion.isEmpty()) {
                            bucketRegion = "us-east-1";
                        }
                    } catch (S3Exception e) {
                        String correctRegion = e.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region").orElse(null);
                        if (correctRegion != null) {
                            bucketRegion = correctRegion;
                        }
                    }
                    s3Regions.add(bucketRegion);
                }
            } catch (Exception e) {
                logger.error("Could not list S3 buckets to determine active regions", e);
            }

            List<Region> allRegions = ec2.describeRegions().regions();
            logger.debug("Found {} total regions available to the account {}. Now checking for activity.", allRegions.size(), account.getAwsAccountId());

            List<DashboardData.RegionStatus> regionStatuses = allRegions.parallelStream()
                    .filter(region -> !"not-opted-in".equals(region.optInStatus()))
                    .map(region -> {
                        software.amazon.awssdk.regions.Region sdkRegion = software.amazon.awssdk.regions.Region.of(region.regionName());
                        Ec2Client regionEc2 = awsClientProvider.getEc2Client(account, sdkRegion.id());
                        RdsClient regionRds = awsClientProvider.getRdsClient(account, sdkRegion.id());
                        LambdaClient regionLambda = awsClientProvider.getLambdaClient(account, sdkRegion.id());
                        EcsClient regionEcs = awsClientProvider.getEcsClient(account, sdkRegion.id());
                        if (isRegionActive(regionEc2, regionRds, regionLambda, regionEcs, sdkRegion) || s3Regions.contains(region.regionName())) {
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

    private DashboardData.RegionStatus mapRegionToStatus(Region region) {
        if (!this.regionCoordinates.containsKey(region.regionName())) {
            logger.warn("No coordinates found for region {}. It will not be displayed on the map.", region.regionName());
            return null;
        }
        double[] geo = this.regionCoordinates.get(region.regionName());
        double lat = geo[0];
        double lon = geo[1];
        String status = "ACTIVE";
        if (SUSTAINABLE_REGIONS.contains(region.regionName())) {
            status = "SUSTAINABLE";
        }
        return new DashboardData.RegionStatus(region.regionName(), region.regionName(), status, lat, lon);
    }

    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
                .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchFunction.apply(regionStatus.getRegionId());
                    }
                    catch (AwsServiceException e) {
                        logger.warn("Cloudlist sub-task failed for account {}: {} in region {}. AWS Error: {}", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e.awsErrorDetails().errorMessage());
                        return Collections.<T>emptyList();
                    }
                    catch (Exception e) {
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
                    .map(i -> new ResourceDto(i.instanceId(), getTagName(i.tags(), i.instanceId()), "EC2 Instance", i.placement().availabilityZone().replaceAll(".$", ""), i.state().nameAsString(), i.launchTime(), Map.of("Type", i.instanceTypeAsString(), "Image ID", i.imageId(), "VPC ID", i.vpcId(), "Private IP", i.privateIpAddress())))
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
            S3Client s3GlobalClient = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                List<Bucket> buckets = s3GlobalClient.listBuckets().buckets();
                logger.debug("Found {} S3 buckets to process for account {}", buckets.size(), account.getAwsAccountId());
                List<ResourceDto> resources = buckets.parallelStream().map(b -> {
                            String bucketRegion = null;
                            try {
                                String locationConstraint = s3GlobalClient.getBucketLocation(req -> req.bucket(b.name())).locationConstraintAsString();
                                bucketRegion = (locationConstraint == null || locationConstraint.isEmpty()) ? "us-east-1" : locationConstraint;
                            } catch (S3Exception e) {
                                bucketRegion = e.awsErrorDetails().sdkHttpResponse()
                                        .firstMatchingHeader("x-amz-bucket-region")
                                        .orElse(null);

                                if (bucketRegion == null) {
                                    String message = e.awsErrorDetails().errorMessage();
                                    if (message != null && message.contains("expecting")) {
                                        String[] parts = message.split("'");
                                        if (parts.length >= 4) {
                                            bucketRegion = parts[3];
                                        }
                                    }
                                }

                                if (bucketRegion == null) {
                                    logger.warn("Could not determine region for bucket {}. Skipping. Error: {}", b.name(), e.getMessage());
                                    return null;
                                }
                            } catch (Exception e) {
                                logger.warn("General error getting location for bucket {}. Skipping. Error: {}", b.name(), e.getMessage());
                                return null;
                            }

                            return new ResourceDto(b.name(), b.name(), "S3 Bucket", bucketRegion, "Available", b.creationDate(), Collections.emptyMap());
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
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

    private String getRegionFromArn(String arn) {
        if (arn == null || arn.isBlank()) return "Unknown";
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

    public String getTagName(List<Tag> tags, String defaultName) {
        if (tags == null || tags.isEmpty()) return defaultName;
        return tags.stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(defaultName);
    }
}