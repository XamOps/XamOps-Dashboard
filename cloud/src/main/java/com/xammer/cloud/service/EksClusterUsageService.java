package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;

@Service
public class EksClusterUsageService {

    private static final Logger logger = LoggerFactory.getLogger(EksClusterUsageService.class);
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public EksClusterUsageService(AwsClientProvider awsClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @Async
    public CompletableFuture<ClusterUsageDto> getClusterUsage(String accountId, String clusterName, String region) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        
        CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
        logger.info("Fetching EKS cluster usage from CloudWatch for account {}, cluster {}", accountId, clusterName);

        try {
            ClusterUsageDto dto = new ClusterUsageDto();
            
            // Fetch metrics
            double nodeCount = getSingleMetricValue(cwClient, clusterName, "cluster_node_count");
            double podCount = getSingleMetricValue(cwClient, clusterName, "cluster_number_of_running_pods");
            
            dto.setNodeCount((int) nodeCount);
            dto.setPodCount((int) podCount);

            dto.setCpuUsage(getSingleMetricValue(cwClient, clusterName, "node_cpu_utilization"));
            dto.setMemoryUsage(getSingleMetricValue(cwClient, clusterName, "node_memory_utilization"));

            // Placeholders for data not available from simple metrics
            dto.setCpuTotal(0);
            dto.setCpuRequests(0);
            dto.setCpuLimits(0);
            dto.setMemoryTotal(0);
            dto.setMemoryRequests(0);
            dto.setMemoryLimits(0);

            return CompletableFuture.completedFuture(dto);

        } catch (Exception e) {
            logger.error("Failed to fetch cluster usage from CloudWatch for cluster {}", clusterName, e);
            return CompletableFuture.completedFuture(new ClusterUsageDto());
        }
    }

    private double getSingleMetricValue(CloudWatchClient cwClient, String clusterName, String metricName) {
        try {
            GetMetricDataRequest request = GetMetricDataRequest.builder()
                .startTime(Instant.now().minus(15, ChronoUnit.MINUTES)) // Widen the time window
                .endTime(Instant.now())
                .metricDataQueries(MetricDataQuery.builder()
                    .id("m1")
                    .metricStat(MetricStat.builder()
                        .metric(Metric.builder()
                            .namespace("ContainerInsights")
                            .metricName(metricName)
                            .dimensions(Dimension.builder().name("ClusterName").value(clusterName).build())
                            .build())
                        .period(300) // 5-minute period
                        .stat("Average")
                        .build())
                    .returnData(true)
                    .build())
                .build();

            GetMetricDataResponse response = cwClient.getMetricData(request);
            if (response.metricDataResults().isEmpty()) {
                logger.warn("No MetricDataResults found for metric '{}' in cluster '{}'", metricName, clusterName);
                return 0.0;
            }

            MetricDataResult result = response.metricDataResults().get(0);
            if (result.values().isEmpty()) {
                logger.warn("Metric data values are empty for metric '{}' in cluster '{}'. The agent may have just started.", metricName, clusterName);
                return 0.0;
            }
            
            logger.info("Successfully fetched metric '{}' for cluster '{}' with value: {}", metricName, clusterName, result.values().get(0));
            return result.values().get(0);

        } catch (Exception e) {
            logger.error("Could not fetch CloudWatch metric '{}' for cluster '{}'. Please check IAM permissions.", metricName, clusterName, e);
            return 0.0;
        }
    }
}