package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class EksService {

    private static final Logger logger = LoggerFactory.getLogger(EksService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final String configuredRegion;

    @Autowired
    public EksService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
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
                    boolean isConnected = checkContainerInsightsStatus(account, name, region);
                    return new K8sClusterInfo(name, cluster.statusAsString(), cluster.version(), region, isConnected);
                } catch (Exception e) {
                    logger.error("Failed to describe EKS cluster {}", name, e);
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());
            return CompletableFuture.completedFuture(clusters);
        } catch (Exception e) {
            logger.error("Could not list EKS clusters for account {}", account.getAwsAccountId(), e);
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
            List<V1Pod> pods = api.listNamespacedPod(namespace, null, null, null, null, null, null, null, null, null, false).getItems();
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

    // --- Private Helper Methods ---

    private boolean checkContainerInsightsStatus(CloudAccount account, String clusterName, String region) {
        logger.info("Checking Container Insights status for cluster {} in region {}", clusterName, region);
        try {
            CloudWatchLogsClient logsClient = awsClientProvider.getCloudWatchLogsClient(account, region);
            String logGroupName = "/aws/containerinsights/" + clusterName + "/performance";
            logsClient.describeLogGroups(req -> req.logGroupNamePrefix(logGroupName));
            logger.info("Container Insights is CONNECTED for cluster {}", clusterName);
            return true;
        } catch (Exception e) {
            logger.info("Container Insights is NOT CONNECTED for cluster {}", clusterName);
            return false;
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
        try {
            Cluster cluster = getEksCluster(account, clusterName);
            String region = cluster.arn().split(":")[3];

            ApiClient client = new ClientBuilder()
                    .setBasePath(cluster.endpoint())
                    .setVerifyingSsl(true)
                    .setCertificateAuthority(Base64.getDecoder().decode(cluster.certificateAuthority().data()))
                    .build();

            String token;
            
            AwsCredentialsProvider credentialsProvider = awsClientProvider.getCredentialsProvider(account);
            AwsCredentials credentials = credentialsProvider.resolveCredentials();

            ProcessBuilder processBuilder = new ProcessBuilder("aws", "eks", "get-token", "--cluster-name", clusterName, "--region", region);
            Map<String, String> environment = processBuilder.environment();
            environment.put("AWS_ACCESS_KEY_ID", credentials.accessKeyId());
            environment.put("AWS_SECRET_ACCESS_KEY", credentials.secretAccessKey());
            if (credentials instanceof software.amazon.awssdk.auth.credentials.AwsSessionCredentials) {
                String sessionToken = ((software.amazon.awssdk.auth.credentials.AwsSessionCredentials) credentials).sessionToken();
                environment.put("AWS_SESSION_TOKEN", sessionToken);
            }
            
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(output.toString());
                JsonNode tokenNode = rootNode.path("status").path("token");
                
                if (tokenNode.isMissingNode() || tokenNode.asText().isEmpty()) {
                    throw new IOException("Token not found in AWS CLI JSON output: " + output.toString());
                }
                token = tokenNode.asText();
            } else {
                throw new IOException("Failed to get EKS token via AWS CLI. Exit code: " + exitCode + " Output: " + output);
            }

            client.setApiKeyPrefix("Bearer");
            client.setApiKey(token);

            io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
            return client;

        } catch (Exception e) {
            logger.error("Failed to build Kubernetes ApiClient for cluster {}: {}", clusterName, e.getMessage(), e);
            throw new IOException("Could not configure Kubernetes client", e);
        }
    }
    
    private String formatAge(OffsetDateTime creationTimestamp) {
        if (creationTimestamp == null) return "N/A";
        Duration duration = Duration.between(creationTimestamp, OffsetDateTime.now());
        long days = duration.toDays();
        if (days > 0) return days + "d";
        long hours = duration.toHours();
        if (hours > 0) return hours + "h";
        long minutes = duration.toMinutes();
        if (minutes > 0) return minutes + "m";
        return duration.toSeconds() + "s";
    }

    private String formatResource(Map<String, io.kubernetes.client.custom.Quantity> resources, String type) {
        if (resources == null || !resources.containsKey(type)) {
            return "0";
        }
        return resources.get(type).toSuffixedString();
    }
}