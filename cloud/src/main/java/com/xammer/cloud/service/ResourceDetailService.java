package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.dto.ResourceDetailDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttribute;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ResourceDetailService {

    private static final Logger logger = LoggerFactory.getLogger(ResourceDetailService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService; // To find regions

    @Autowired
    public ResourceDetailService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
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
                if (bucketRegion == null || bucketRegion.isEmpty()) bucketRegion = "us-east-1";

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

    public String findResourceRegion(CloudAccount account, String serviceType, String resourceId) {
        logger.debug("Attempting to find region for {} resource {}", serviceType, resourceId);
        List<DashboardData.RegionStatus> activeRegions = cloudListService.getRegionStatusForAccount(account).join();

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
        return null;
    }

    public String findInstanceRegion(CloudAccount account, String instanceId) {
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
        return null;
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

    
    private GetMetricDataRequest buildMetricDataRequest(String resourceId, String metricName, String namespace, String dimensionName) { 
        Metric metric = Metric.builder().namespace(namespace).metricName(metricName).dimensions(Dimension.builder().name(dimensionName).value(resourceId).build()).build(); 
        MetricStat metricStat = MetricStat.builder().metric(metric).period(300).stat("Average").build(); 
        MetricDataQuery metricDataQuery = MetricDataQuery.builder().id(metricName.toLowerCase().replace(" ", "").replace("/", "")).metricStat(metricStat).returnData(true).build(); 
        return GetMetricDataRequest.builder().startTime(Instant.now().minus(1, ChronoUnit.DAYS)).endTime(Instant.now()).metricDataQueries(metricDataQuery).scanBy(ScanBy.TIMESTAMP_DESCENDING).build(); 
    }

    private List<MetricDto> buildMetricDtos(MetricDataResult result) {
        List<Instant> timestamps = result.timestamps();
        List<Double> values = result.values();
        if (timestamps == null || values == null || timestamps.size() != values.size()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, timestamps.size()).mapToObj(i -> new MetricDto(timestamps.get(i), values.get(i))).collect(Collectors.toList());
    }
    
    private String getTagName(List<software.amazon.awssdk.services.ec2.model.Tag> tags, String defaultName) {
        if (tags == null || tags.isEmpty()) return defaultName;
        return tags.stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(software.amazon.awssdk.services.ec2.model.Tag::value).orElse(defaultName);
    }
}