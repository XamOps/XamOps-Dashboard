package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.rds.RdsClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class MetricsService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService; // Used for finding regions

    @Autowired
    public MetricsService(
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
    public CompletableFuture<Map<String, List<MetricDto>>> getEc2InstanceMetrics(String accountId, String instanceId) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, List<MetricDto>>> getRdsInstanceMetrics(String accountId, String instanceId) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, List<MetricDto>>> getS3BucketMetrics(String accountId, String bucketName, String region) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<Map<String, List<MetricDto>>> getLambdaFunctionMetrics(String accountId, String functionName, String region) {
        return CompletableFuture.supplyAsync(() -> {
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
        });
    }

    private String findInstanceRegion(CloudAccount account, String instanceId) {
        logger.debug("Attempting to find region for instance {}", instanceId);
        Ec2Client baseEc2Client = awsClientProvider.getEc2Client(account, "us-east-1");
        List<Region> allPossibleRegions = baseEc2Client.describeRegions().regions();

        for (Region region : allPossibleRegions) {
            if ("not-opted-in".equals(region.optInStatus())) continue;
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

    private String findResourceRegion(CloudAccount account, String serviceType, String resourceId) {
        logger.debug("Attempting to find region for {} resource {}", serviceType, resourceId);
        List<DashboardData.RegionStatus> activeRegions = cloudListService.getRegionStatusForAccount(account).join();

        for (DashboardData.RegionStatus region : activeRegions) {
            try {
                if ("RDS".equals(serviceType)) {
                    RdsClient rds = awsClientProvider.getRdsClient(account, region.getRegionId());
                    if (rds.describeDBInstances(r -> r.dbInstanceIdentifier(resourceId)).hasDbInstances()) {
                        logger.info("Found RDS instance {} in region {}", resourceId, region.getRegionId());
                        return region.getRegionId();
                    }
                }
            } catch (Exception e) {
                logger.trace("{} {} not found in region {}: {}", serviceType, resourceId, region.getRegionId(), e.getMessage());
            }
        }
        logger.warn("Could not find {} resource {} in any active region.", serviceType, resourceId);
        return null;
    }

    private List<MetricDto> buildMetricDtos(MetricDataResult result) {
        List<Instant> timestamps = result.timestamps();
        List<Double> values = result.values();
        if (timestamps == null || values == null || timestamps.size() != values.size()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, timestamps.size()).mapToObj(i -> new MetricDto(timestamps.get(i), values.get(i))).collect(Collectors.toList());
    }

    private GetMetricDataRequest buildMetricDataRequest(String resourceId, String metricName, String namespace, String dimensionName) {
        Metric metric = Metric.builder().namespace(namespace).metricName(metricName).dimensions(Dimension.builder().name(dimensionName).value(resourceId).build()).build();
        MetricStat metricStat = MetricStat.builder().metric(metric).period(300).stat("Average").build();
        MetricDataQuery metricDataQuery = MetricDataQuery.builder().id(metricName.toLowerCase().replace(" ", "").replace("/", "")).metricStat(metricStat).returnData(true).build();
        return GetMetricDataRequest.builder().startTime(Instant.now().minus(1, ChronoUnit.DAYS)).endTime(Instant.now()).metricDataQueries(metricDataQuery).scanBy(ScanBy.TIMESTAMP_DESCENDING).build();
    }
}