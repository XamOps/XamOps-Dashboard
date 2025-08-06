package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class EksService {

    private static final Logger logger = LoggerFactory.getLogger(EksService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final String configuredRegion;

    @Autowired
    public EksService(CloudAccountRepository cloudAccountRepository, AwsClientProvider awsClientProvider) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    private KubernetesClient getClientFromKubeconfig(String kubeConfigYaml) {
        Config config = Config.fromKubeconfig(kubeConfigYaml);
        return new DefaultKubernetesClient(config);
    }

    @Async
    @Cacheable(value = "eksClusters", key = "#accountId")
    public CompletableFuture<List<K8sClusterInfo>> getEksClusterInfo(String accountId) {
        CloudAccount account = getAccount(accountId);
        EksClient eks = awsClientProvider.getEksClient(account, configuredRegion);
        logger.info("Fetching EKS cluster list for account {}...", account.getAwsAccountId());

        try {
            List<String> clusterNames = eks.listClusters().clusters();
            List<K8sClusterInfo> clusters = clusterNames.stream().map(name -> {
                try {
                    Cluster cluster = eks.describeCluster(b -> b.name(name)).cluster();
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

    @Async
    public CompletableFuture<List<String>> getK8sNamespaces(String kubeConfigYaml) {
        if (kubeConfigYaml == null || kubeConfigYaml.isBlank()) {
            logger.warn("Kubeconfig is empty, cannot fetch namespaces.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try (KubernetesClient client = getClientFromKubeconfig(kubeConfigYaml)) {
            return CompletableFuture.completedFuture(
                client.namespaces().list().getItems().stream()
                    .map(ns -> ns.getMetadata().getName()).collect(Collectors.toList())
            );
        } catch (Exception e) {
            logger.error("Failed to get namespaces", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async
    public CompletableFuture<List<K8sPodInfo>> getK8sPods(String kubeConfigYaml, String namespace) {
        if (kubeConfigYaml == null || kubeConfigYaml.isBlank()) {
            logger.warn("Kubeconfig is empty, cannot fetch pods.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try (KubernetesClient client = getClientFromKubeconfig(kubeConfigYaml)) {
            List<Pod> podList = client.pods().inNamespace(namespace).list().getItems();
            List<K8sPodInfo> out = new ArrayList<>();
            for (Pod pod : podList) {
                K8sPodInfo info = new K8sPodInfo();
                info.setName(pod.getMetadata().getName());
                info.setNodeName(pod.getSpec().getNodeName());
                info.setStatus(pod.getStatus().getPhase());
                info.setRestarts(pod.getStatus().getContainerStatuses() != null
                        ? pod.getStatus().getContainerStatuses().stream().mapToInt(s -> s.getRestartCount()).sum() : 0);
                out.add(info);
            }
            return CompletableFuture.completedFuture(out);
        } catch (Exception e) {
            logger.error("Failed to get pods", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async
    public CompletableFuture<List<K8sDeploymentInfo>> getK8sDeployments(String kubeConfigYaml, String namespace) {
        if (kubeConfigYaml == null || kubeConfigYaml.isBlank()) {
            logger.warn("Kubeconfig is empty, cannot fetch deployments.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try (KubernetesClient client = getClientFromKubeconfig(kubeConfigYaml)) {
            List<Deployment> deployments = client.apps().deployments().inNamespace(namespace).list().getItems();
            List<K8sDeploymentInfo> out = new ArrayList<>();
            for (Deployment dep : deployments) {
                K8sDeploymentInfo info = new K8sDeploymentInfo();
                info.setName(dep.getMetadata().getName());
                info.setReady(
                  (dep.getStatus().getReadyReplicas() == null ? 0 : dep.getStatus().getReadyReplicas())
                  + "/" + (dep.getStatus().getReplicas() == null ? 0 : dep.getStatus().getReplicas())
                );
                info.setUpToDate(dep.getStatus().getUpdatedReplicas() == null ? 0 : dep.getStatus().getUpdatedReplicas());
                info.setAvailable(dep.getStatus().getAvailableReplicas() == null ? 0 : dep.getStatus().getAvailableReplicas());
                info.setAge(dep.getMetadata().getCreationTimestamp());
                out.add(info);
            }
            return CompletableFuture.completedFuture(out);
        } catch (Exception e) {
            logger.error("Failed to get deployments", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async
    public CompletableFuture<List<K8sEventInfo>> getK8sEvents(String kubeConfigYaml, String namespace) {
        if (kubeConfigYaml == null || kubeConfigYaml.isBlank()) {
            logger.warn("Kubeconfig is empty, cannot fetch events.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        try (KubernetesClient client = getClientFromKubeconfig(kubeConfigYaml)) {
            List<Event> events = client.v1().events().inNamespace(namespace).list().getItems();
            List<K8sEventInfo> out = new ArrayList<>();
            for (Event evt : events) {
                K8sEventInfo info = new K8sEventInfo();
                info.setLastSeen(evt.getLastTimestamp());
                info.setType(evt.getType());
                info.setReason(evt.getReason());
                info.setObject(evt.getInvolvedObject().getName());
                info.setMessage(evt.getMessage());
                out.add(info);
            }
            return CompletableFuture.completedFuture(out);
        } catch (Exception e) {
            logger.error("Failed to get events", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async
    public CompletableFuture<String> getPodLogs(String kubeConfigYaml, String namespace, String podName) {
        if (kubeConfigYaml == null || kubeConfigYaml.isBlank()) {
            logger.warn("Kubeconfig is empty, cannot fetch logs.");
            return CompletableFuture.completedFuture("Kubeconfig not provided.");
        }
        try (KubernetesClient client = getClientFromKubeconfig(kubeConfigYaml)) {
            String logs = client.pods().inNamespace(namespace).withName(podName).getLog();
            return CompletableFuture.completedFuture(logs != null ? logs : "No logs found");
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error fetching logs: " + e.getMessage());
        }
    }

    // --- Existing methods remain unchanged ---

    // 2. Fetch nodes and metrics (CPU %, Memory %)
    @Async
    @Cacheable(value = "k8sNodes", key = "#accountId + '-' + #clusterName")
    public CompletableFuture<List<K8sNodeInfo>> getK8sNodes(String accountId, String clusterName) {
        logger.info("Fetching nodes for cluster: {} in account: {}", clusterName, accountId);
        CloudAccount account = getAccount(accountId);
        String region;
        List<String> nodeNames;

        try {
            EksClient eks = awsClientProvider.getEksClient(account, configuredRegion);
            Cluster cluster = eks.describeCluster(c -> c.name(clusterName)).cluster();
            region = cluster.arn().split(":")[3];
            nodeNames = fetchNodeNames(account, clusterName, region);
        } catch (Exception e) {
            logger.error("Error fetching nodes for cluster {}: {}", clusterName, e.getMessage(), e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<K8sNodeInfo> nodeInfos = nodeNames.stream().map(nodeName -> {
            Map<String, Double> cpuMemory = fetchCpuMemoryMetrics(account, clusterName, nodeName, region);
            return new K8sNodeInfo(
                    nodeName,
                    "Ready",
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    formatPercentage(cpuMemory.getOrDefault("cpu", 0.0)),
                    formatPercentage(cpuMemory.getOrDefault("memory", 0.0))
            );
        }).collect(Collectors.toList());

        return CompletableFuture.completedFuture(nodeInfos);
    }
    
    // Add this new method
    @Async
    public CompletableFuture<ClusterUsageDto> getClusterUsageFromKubeconfig(String kubeConfigYaml) {
        if (kubeConfigYaml == null || kubeConfigYaml.isBlank()) {
            logger.warn("Kubeconfig is empty, cannot fetch cluster usage.");
            return CompletableFuture.completedFuture(new ClusterUsageDto());
        }
        ClusterUsageDto dto = new ClusterUsageDto();
        try (KubernetesClient client = getClientFromKubeconfig(kubeConfigYaml)) {
            // 1. Fetch Nodes and sum CPU/Memory capacity
            List<Node> nodes = client.nodes().list().getItems();
            double cpuTotal = 0;
            double memTotal = 0;
            for (Node node : nodes) {
                Map<String, Quantity> capacity = node.getStatus().getCapacity();
                cpuTotal += parseCpuCores(capacity.get("cpu"));
                memTotal += parseMemoryMi(capacity.get("memory"));
            }
            dto.setCpuTotal(cpuTotal);
            dto.setMemoryTotal(memTotal);
            dto.setNodeCount(nodes.size());

            // 2. Fetch Pods and count, sum requests and limits
            List<Pod> pods = client.pods().inAnyNamespace().list().getItems();
            dto.setPodCount(pods.size());
            double cpuRequests = 0;
            double cpuLimits = 0;
            double memRequests = 0;
            double memLimits = 0;
            for (Pod pod : pods) {
                if (pod.getSpec() == null) continue;
                for (Container container : pod.getSpec().getContainers()) {
                    if (container.getResources() == null) continue;
                    Map<String, Quantity> req = container.getResources().getRequests();
                    Map<String, Quantity> lim = container.getResources().getLimits();
                    cpuRequests += parseCpuCores(req != null ? req.get("cpu") : null);
                    cpuLimits += parseCpuCores(lim != null ? lim.get("cpu") : null);
                    memRequests += parseMemoryMi(req != null ? req.get("memory") : null);
                    memLimits += parseMemoryMi(lim != null ? lim.get("memory") : null);
                }
            }
            dto.setCpuRequests(cpuRequests);
            dto.setCpuLimits(cpuLimits);
            dto.setMemoryRequests(memRequests);
            dto.setMemoryLimits(memLimits);

            // 3. Fetch live CPU/Memory usage from metrics.k8s.io API via raw custom resources
            try {
                ResourceDefinitionContext nodeMetricsContext = new ResourceDefinitionContext.Builder()
                        .withGroup("metrics.k8s.io")
                        .withVersion("v1beta1")
                        .withPlural("nodes")
                        .build();
                var genericResource = client.genericKubernetesResources(nodeMetricsContext);
                var nodeMetricsList = genericResource.list();
                double cpuUsage = 0;
                double memUsage = 0;
                if (nodeMetricsList != null && nodeMetricsList.getItems() != null) {
                    for (var item : nodeMetricsList.getItems()) {
                        Map<String, Object> usage = (Map<String, Object>) item.getAdditionalProperties().get("usage");
                        if (usage != null) {
                            cpuUsage += parseCpuCores(new Quantity((String) usage.get("cpu")));
                            memUsage += parseMemoryMi(new Quantity((String) usage.get("memory")));
                        }
                    }
                }
                dto.setCpuUsage(cpuUsage);
                dto.setMemoryUsage(memUsage);
            } catch (Exception e) {
                // If metrics API is missing or error occurs, usage remains zero
                dto.setCpuUsage(0);
                dto.setMemoryUsage(0);
            }
        } catch (Exception e) {
            // Log error and return empty dto or null
            // logger.error("Error fetching cluster usage", e);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(dto);
    }

    private double parseCpuCores(Quantity q) {
        if (q == null || q.getAmount() == null) return 0.0;
        String s = q.getAmount();
        if (s.endsWith("m")) {
            return Double.parseDouble(s.substring(0, s.length() - 1)) / 1000.0;
        }
        return Double.parseDouble(s);
    }

    private double parseMemoryMi(Quantity q) {
        if (q == null || q.getAmount() == null) return 0.0;
        String s = q.getAmount();
        if (s.endsWith("Ki")) return Double.parseDouble(s.replace("Ki", "")) / 1024.0;
        else if (s.endsWith("Mi")) return Double.parseDouble(s.replace("Mi", ""));
        else if (s.endsWith("Gi")) return Double.parseDouble(s.replace("Gi", "")) * 1024;
        else return Double.parseDouble(s)/1024/1024;
    }

    // --- Utilities ---

    // Fetch node names from CloudWatch ContainerInsights metrics
    private List<String> fetchNodeNames(CloudAccount account, String clusterName, String region) {
        try {
            CloudWatchClient cloudWatch = awsClientProvider.getCloudWatchClient(account, region);
            ListMetricsResponse metricsResponse = cloudWatch.listMetrics(b -> b
                    .namespace("ContainerInsights")
                    .metricName("node_cpu_utilization")
                    .dimensions(DimensionFilter.builder().name("ClusterName").value(clusterName).build())
            );
            return metricsResponse.metrics().stream()
                    .flatMap(metric -> metric.dimensions().stream())
                    .filter(dim -> "NodeName".equals(dim.name()))
                    .map(Dimension::value)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching node names from CloudWatch for cluster {}: {}", clusterName, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // Fetch CPU and Memory utilization percentages for a node
    private Map<String, Double> fetchCpuMemoryMetrics(CloudAccount account, String clusterName, String nodeName, String region) {
        try {
            CloudWatchClient cloudWatch = awsClientProvider.getCloudWatchClient(account, region);

            Instant now = Instant.now();
            Instant start = now.minus(5, ChronoUnit.MINUTES);

            MetricDataQuery cpuQuery = MetricDataQuery.builder()
                    .id("cpu")
                    .metricStat(MetricStat.builder()
                            .metric(Metric.builder()
                                    .namespace("ContainerInsights")
                                    .metricName("node_cpu_utilization")
                                    .dimensions(
                                            Dimension.builder().name("ClusterName").value(clusterName).build(),
                                            Dimension.builder().name("NodeName").value(nodeName).build()
                                    )
                                    .build())
                            .period(60)
                            .stat("Average")
                            .build())
                    .returnData(true)
                    .build();

            MetricDataQuery memQuery = MetricDataQuery.builder()
                    .id("memory")
                    .metricStat(MetricStat.builder()
                            .metric(Metric.builder()
                                    .namespace("ContainerInsights")
                                    .metricName("node_memory_utilization")
                                    .dimensions(
                                            Dimension.builder().name("ClusterName").value(clusterName).build(),
                                            Dimension.builder().name("NodeName").value(nodeName).build()
                                    )
                                    .build())
                            .period(60)
                            .stat("Average")
                            .build())
                    .returnData(true)
                    .build();

            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(start)
                    .endTime(now)
                    .metricDataQueries(cpuQuery, memQuery)
                    .build();

            GetMetricDataResponse response = cloudWatch.getMetricData(request);

            Map<String, Double> results = new HashMap<>();
            for (MetricDataResult result : response.metricDataResults()) {
                if (result.values() != null && !result.values().isEmpty()) {
                    results.put(result.id(), result.values().get(0));
                }
            }
            return results;
        } catch (Exception e) {
            logger.error("Error fetching CPU/Memory metrics from CloudWatch: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    // Check if Container Insights logs exist for cluster -> determines connection state
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

    private String formatPercentage(Double value) {
        if (value == null) return "0.00 %";
        return String.format("%.2f %%", value);
    }
}