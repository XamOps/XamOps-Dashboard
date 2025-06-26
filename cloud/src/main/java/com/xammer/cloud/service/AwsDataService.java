package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.dto.ResourceDto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.PasswordPolicy;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

    private static final Set<String> SUSTAINABLE_REGIONS = Set.of("us-east-1", "us-west-2", "eu-west-1", "eu-central-1", "ca-central-1");
    private static final Map<String, double[]> REGION_COORDINATES = Map.ofEntries(
            Map.entry("us-east-1", new double[]{38, 23}), Map.entry("us-east-2", new double[]{40, 20}),
            Map.entry("us-west-1", new double[]{38, 16}), Map.entry("us-west-2", new double[]{44, 15}),
            Map.entry("ca-central-1", new double[]{50, 22}), Map.entry("eu-west-1", new double[]{52, 6}),
            Map.entry("eu-west-2", new double[]{51, 8}), Map.entry("eu-west-3", new double[]{48, 10}),
            Map.entry("eu-central-1", new double[]{50, 18}), Map.entry("eu-north-1", new double[]{60, 20}),
            Map.entry("ap-southeast-1", new double[]{88, 70}), Map.entry("ap-southeast-2", new double[]{92, 85}),
            Map.entry("ap-northeast-1", new double[]{40, 85}), Map.entry("ap-northeast-2", new double[]{37, 80}),
            Map.entry("ap-northeast-3", new double[]{34, 83}), Map.entry("ap-south-1", new double[]{70, 60}),
            Map.entry("sa-east-1", new double[]{83, 35})
    );
    public AwsDataService(Ec2Client ec2, IamClient iam, EcsClient ecs, EksClient eks, LambdaClient lambda, CloudWatchClient cw, CostExplorerClient ce, ComputeOptimizerClient co, PricingService pricingService) {
        this.ec2Client = ec2; this.iamClient = iam; this.ecsClient = ecs; this.eksClient = eks; this.lambdaClient = lambda; this.cloudWatchClient = cw; this.costExplorerClient = ce;
        this.computeOptimizerClient = co;
        this.pricingService = pricingService;
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

        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(ec2Recs, ebsRecs, lambdaRecs, anomalies);

        DashboardData data = new DashboardData();
        DashboardData.Account mainAccount = new DashboardData.Account(
                "123456789012", "MachaDalo",
                regionStatusFuture.get(), inventoryFuture.get(), cwStatusFuture.get(), insightsFuture.get(),
                costHistoryFuture.get(), billingFuture.get(), iamFuture.get(), savingsFuture.get(),
                ec2Recs, anomalies, ebsRecs,
                lambdaRecs, reservationFuture.get(), reservationPurchaseFuture.get(),
                optimizationSummary,
                null
        );
        
        data.setAvailableAccounts(List.of(mainAccount, new DashboardData.Account("987654321098", "Xammer", new ArrayList<>(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        data.setSelectedAccount(mainAccount);
        return data;
    }


        @Async("awsTaskExecutor")
    @Cacheable("cloudlistResources")
    public CompletableFuture<List<ResourceDto>> getAllResources() {
        logger.info("Fetching all resources for Cloudlist...");

        CompletableFuture<List<ResourceDto>> ec2Future = fetchEc2InstancesForCloudlist();
        CompletableFuture<List<ResourceDto>> ebsFuture = fetchEbsVolumesForCloudlist();

        return ec2Future.thenCombine(ebsFuture, (ec2List, ebsList) -> {
            List<ResourceDto> allResources = new ArrayList<>();
            allResources.addAll(ec2List);
            allResources.addAll(ebsList);
            logger.info("... found {} total resources for Cloudlist.", allResources.size());
            return allResources;
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchEc2InstancesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ec2Client.describeInstances().reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .map(i -> new ResourceDto(
                        i.instanceId(),
                        i.tags().stream().filter(t -> t.key().equals("Name")).findFirst().map(Tag::value).orElse("N/A"),
                        "EC2 Instance",
                        i.placement().availabilityZone().replaceAll(".$", ""),
                        i.state().nameAsString(),
                        i.launchTime(),
                        Map.of("Type", i.instanceTypeAsString(), "Image ID", i.imageId())
                    ))
                    .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: EC2 instances.", e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchEbsVolumesForCloudlist() {
        return CompletableFuture.supplyAsync(() -> {
             try {
                return ec2Client.describeVolumes().volumes().stream()
                    .map(v -> new ResourceDto(
                        v.volumeId(),
                        v.tags().stream().filter(t -> t.key().equals("Name")).findFirst().map(Tag::value).orElse("N/A"),
                        "EBS Volume",
                        v.availabilityZone().replaceAll(".$", ""),
                        v.stateAsString(),
                        v.createTime(),
                        Map.of("Size", v.size() + " GiB", "Type", v.volumeTypeAsString())
                    ))
                    .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed: EBS volumes.", e);
                return Collections.emptyList();
            }
        });
    }

    // FIXED: Correctly reconstruct Datapoint objects from timestamps and values.
    public Map<String, List<MetricDto>> getEc2InstanceMetrics(String instanceId) {
        logger.info("Fetching CloudWatch metrics for instance: {}", instanceId);
        try {
            GetMetricDataRequest cpuRequest = buildMetricDataRequest(instanceId, "CPUUtilization", "AWS/EC2");
            MetricDataResult cpuResult = cloudWatchClient.getMetricData(cpuRequest).metricDataResults().get(0);
            List<MetricDto> cpuDatapoints = buildMetricDtos(cpuResult);

            GetMetricDataRequest networkInRequest = buildMetricDataRequest(instanceId, "NetworkIn", "AWS/EC2");
            MetricDataResult networkInResult = cloudWatchClient.getMetricData(networkInRequest).metricDataResults().get(0);
            List<MetricDto> networkInDatapoints = buildMetricDtos(networkInResult);

            return Map.of(
                "CPUUtilization", cpuDatapoints,
                "NetworkIn", networkInDatapoints
            );

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


    // --- Helper for reconstructing Datapoint objects ---
    private List<Datapoint> buildDatapoints(MetricDataResult result) {
        List<Instant> timestamps = result.timestamps();
        List<Double> values = result.values();
        
        if (timestamps == null || values == null || timestamps.size() != values.size()) {
            return Collections.emptyList();
        }
        
        // Using IntStream for a concise way to iterate over both lists
        return IntStream.range(0, timestamps.size())
                .mapToObj(i -> Datapoint.builder()
                        .timestamp(timestamps.get(i))
                        .average(values.get(i)) // We requested Average, so we build with average.
                        .build())
                .collect(Collectors.toList());
    }
    
    // --- Helper for building the metric request ---
    private GetMetricDataRequest buildMetricDataRequest(String instanceId, String metricName, String namespace) {
        Metric metric = Metric.builder()
                .namespace(namespace)
                .metricName(metricName)
                .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                .build();

        MetricStat metricStat = MetricStat.builder()
                .metric(metric)
                .period(86400) // 1 day period
                .stat("Average")
                .build();

        MetricDataQuery metricDataQuery = MetricDataQuery.builder()
                .id(metricName.toLowerCase().replace(" ", ""))
                .metricStat(metricStat)
                .returnData(true)
                .build();

        return GetMetricDataRequest.builder()
                .startTime(Instant.now().minus(14, ChronoUnit.DAYS))
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
        logger.info("... found {} wasted resources.", wasted.size());
        return CompletableFuture.completedFuture(wasted);
    }
    
    @Async("awsTaskExecutor") @Cacheable("regionStatus")
    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForAccount() {
        logger.info("Fetching region status...");
        try {
            return CompletableFuture.completedFuture(ec2Client.describeRegions().regions().stream()
                .filter(region -> REGION_COORDINATES.containsKey(region.regionName()))
                .map(this::mapRegionToStatus)
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch EC2 regions.", e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor") @Cacheable("inventory")
    public CompletableFuture<DashboardData.ResourceInventory> getResourceInventory() {
        logger.info("Fetching resource inventory...");
        int vpc=0, ecs=0, ec2=0, k8s=0, lambdas=0, ebs=0, images=0, snapshots=0;
        try { vpc = ec2Client.describeVpcs().vpcs().size(); } catch (Exception e) { logger.error("Inv check fail: VPCs", e); }
        try { ecs = ecsClient.listClusters().clusterArns().size(); } catch (Exception e) { logger.error("Inv check fail: ECS", e); }
        try { ec2 = ec2Client.describeInstances().reservations().stream().mapToInt(r -> r.instances().size()).sum(); } catch (Exception e) { logger.error("Inv check fail: EC2", e); }
        try { k8s = eksClient.listClusters().clusters().size(); } catch (Exception e) { logger.error("Inv check fail: EKS", e); }
        try { lambdas = lambdaClient.listFunctions().functions().size(); } catch (Exception e) { logger.error("Inv check fail: Lambda", e); }
        try { ebs = ec2Client.describeVolumes().volumes().size(); } catch (Exception e) { logger.error("Inv check fail: EBS", e); }
        try { images = ec2Client.describeImages(r -> r.owners("self")).images().size(); } catch (Exception e) { logger.error("Inv check fail: Images", e); }
        try { snapshots = ec2Client.describeSnapshots(r -> r.ownerIds("self")).snapshots().size(); } catch (Exception e) { logger.error("Inv check fail: Snapshots", e); }
        return CompletableFuture.completedFuture(new DashboardData.ResourceInventory(vpc, ecs, ec2, k8s, lambdas, ebs, images, snapshots));
    }
    
    @Async("awsTaskExecutor") @Cacheable("cloudwatchStatus")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus() {
        logger.info("Fetching CloudWatch status...");
        try {
            List<software.amazon.awssdk.services.cloudwatch.model.MetricAlarm> alarms = cloudWatchClient.describeAlarms().metricAlarms();
            long ok = alarms.stream().filter(a -> a.stateValueAsString().equals("OK")).count();
            long alarm = alarms.stream().filter(a -> a.stateValueAsString().equals("ALARM")).count();
            long insufficient = alarms.stream().filter(a -> a.stateValueAsString().equals("INSUFFICIENT_DATA")).count();
            return CompletableFuture.completedFuture(new DashboardData.CloudWatchStatus(ok, alarm, insufficient));
        } catch (Exception e) { logger.error("Could not fetch CloudWatch alarms.", e); return CompletableFuture.completedFuture(new DashboardData.CloudWatchStatus(0,0,0)); }
    }

    @Async("awsTaskExecutor") @Cacheable("securityInsights")
    public CompletableFuture<List<DashboardData.SecurityInsight>> getSecurityInsights() {
        logger.info("Fetching security insights...");
        List<DashboardData.SecurityInsight> insights = new ArrayList<>();
        try {
            int oldKeyCount = (int) iamClient.listUsers().users().stream().flatMap(u -> iamClient.listAccessKeys(r -> r.userName(u.userName())).accessKeyMetadata().stream()).filter(k -> k.createDate().isBefore(Instant.now().minus(90, ChronoUnit.DAYS))).count();
            if (oldKeyCount > 0) insights.add(new DashboardData.SecurityInsight("IAM user access key is too old", "", "SECURITY", oldKeyCount));
        } catch (Exception e) { logger.error("Could not fetch IAM key age.", e); }
        try {
            PasswordPolicy policy = iamClient.getAccountPasswordPolicy().passwordPolicy();
            if (policy.minimumPasswordLength() < 14) insights.add(new DashboardData.SecurityInsight("Password policy is too weak", "Min length is " + policy.minimumPasswordLength(), "SECURITY", 1));
        } catch (NoSuchEntityException e) { insights.add(new DashboardData.SecurityInsight("Account password policy not set", "", "SECURITY", 1)); }
        catch (Exception e) { logger.error("Could not fetch password policy.", e); }
        return CompletableFuture.completedFuture(insights);
    }
    
    @Async("awsTaskExecutor") @Cacheable("ec2Recs")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEc2InstanceRecommendations() {
        logger.info("Fetching EC2 recommendations...");
        try {
            GetEc2InstanceRecommendationsRequest request = GetEc2InstanceRecommendationsRequest.builder().build();
            List<InstanceRecommendation> recommendations = computeOptimizerClient.getEC2InstanceRecommendations(request).instanceRecommendations();
            return CompletableFuture.completedFuture(recommendations.stream()
                .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
                .map(r -> new DashboardData.OptimizationRecommendation("EC2", r.instanceArn().split("/")[1], r.currentInstanceType(), r.recommendationOptions().get(0).instanceType(), r.recommendationOptions().get(0).savingsOpportunity() != null && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings() != null && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings().value() != null ? r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings().value() : 0.0, r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", "))))
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch EC2 instance recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
    
    @Async("awsTaskExecutor") @Cacheable("costAnomalies")
    public CompletableFuture<List<DashboardData.CostAnomaly>> getCostAnomalies() {
        logger.info("Fetching cost anomalies...");
        try {
            AnomalyDateInterval dateInterval = AnomalyDateInterval.builder().startDate(LocalDate.now().minusDays(60).toString()).endDate(LocalDate.now().toString()).build();
            GetAnomaliesRequest request = GetAnomaliesRequest.builder().dateInterval(dateInterval).build();
            List<Anomaly> anomalies = costExplorerClient.getAnomalies(request).anomalies();
            return CompletableFuture.completedFuture(anomalies.stream()
                .map(a -> new DashboardData.CostAnomaly(a.anomalyId(), getServiceNameFromAnomaly(a), a.impact().totalImpact(), LocalDate.parse(a.anomalyStartDate()), a.anomalyEndDate() != null ? LocalDate.parse(a.anomalyEndDate()) : LocalDate.now()))
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch Cost Anomalies.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
    
    @Async("awsTaskExecutor") @Cacheable("ebsRecs")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEbsVolumeRecommendations() {
        logger.info("Fetching EBS recommendations...");
        try {
            GetEbsVolumeRecommendationsRequest request = GetEbsVolumeRecommendationsRequest.builder().build();
            List<VolumeRecommendation> recommendations = computeOptimizerClient.getEBSVolumeRecommendations(request).volumeRecommendations();
            return CompletableFuture.completedFuture(recommendations.stream()
                .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.volumeRecommendationOptions() != null && !r.volumeRecommendationOptions().isEmpty())
                .map(r -> {
                    VolumeRecommendationOption opt = r.volumeRecommendationOptions().get(0);
                    return new DashboardData.OptimizationRecommendation("EBS", r.volumeArn().split("/")[1], r.currentConfiguration().volumeType() + " - " + r.currentConfiguration().volumeSize() + "GiB", opt.configuration().volumeType() + " - " + opt.configuration().volumeSize() + "GiB", opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0, r.finding().toString());
                })
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch EBS volume recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
    
    @Async("awsTaskExecutor") @Cacheable("lambdaRecs")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getLambdaFunctionRecommendations() {
        logger.info("Fetching Lambda recommendations...");
        try {
            GetLambdaFunctionRecommendationsRequest request = GetLambdaFunctionRecommendationsRequest.builder().build();
            List<LambdaFunctionRecommendation> recommendations = computeOptimizerClient.getLambdaFunctionRecommendations(request).lambdaFunctionRecommendations();
            return CompletableFuture.completedFuture(recommendations.stream()
                .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.memorySizeRecommendationOptions() != null && !r.memorySizeRecommendationOptions().isEmpty())
                .map(r -> {
                    LambdaFunctionMemoryRecommendationOption opt = r.memorySizeRecommendationOptions().get(0);
                    return new DashboardData.OptimizationRecommendation("Lambda", r.functionArn().substring(r.functionArn().lastIndexOf(':') + 1), r.currentMemorySize() + " MB", opt.memorySize() + " MB", opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0, r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", ")));
                })
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch Lambda function recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
    
    @Async("awsTaskExecutor") @Cacheable("reservationAnalysis")
    public CompletableFuture<DashboardData.ReservationAnalysis> getReservationAnalysis() {
        logger.info("Fetching reservation analysis...");
        try {
            String today = LocalDate.now().toString();
            String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
            DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();
            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder().timePeriod(last30Days).build();
            List<UtilizationByTime> utilizations = costExplorerClient.getReservationUtilization(utilRequest).utilizationsByTime();
            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder().timePeriod(last30Days).build();
            List<CoverageByTime> coverages = costExplorerClient.getReservationCoverage(covRequest).coveragesByTime();
            double utilizationPercentage = utilizations.isEmpty() || utilizations.get(0).total() == null ? 0.0 : Double.parseDouble(utilizations.get(0).total().utilizationPercentage());
            double coveragePercentage = coverages.isEmpty() || coverages.get(0).total() == null ? 0.0 : Double.parseDouble(coverages.get(0).total().coverageHours().coverageHoursPercentage());
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(utilizationPercentage, coveragePercentage));
        } catch (Exception e) {
            logger.error("Could not fetch reservation analysis data.", e);
            return CompletableFuture.completedFuture(new DashboardData.ReservationAnalysis(0.0, 0.0));
        }
    }

    @Async("awsTaskExecutor") @Cacheable("reservationPurchaseRecs")
    public CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> getReservationPurchaseRecommendations() {
        logger.info("Fetching RI purchase recommendations...");
        try {
            GetReservationPurchaseRecommendationRequest request = GetReservationPurchaseRecommendationRequest.builder()
                .lookbackPeriodInDays(LookbackPeriodInDays.SIXTY_DAYS)
                .service("Amazon Elastic Compute Cloud - Compute")
                .build();
            GetReservationPurchaseRecommendationResponse response = costExplorerClient.getReservationPurchaseRecommendation(request);
            
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
                                getTermValue(rec)
                            );
                        } catch (Exception e) {
                            logger.warn("Failed to process recommendation detail: {}", e.getMessage());
                            return null;
                        }
                    })
                )
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        } catch (Exception e) {
            logger.error("Could not fetch reservation purchase recommendations.", e);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Async("awsTaskExecutor") @Cacheable("billingSummary")
    public CompletableFuture<List<DashboardData.BillingSummary>> getBillingSummary() {
        logger.info("Fetching billing summary...");
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build()).build();
            return CompletableFuture.completedFuture(costExplorerClient.getCostAndUsage(request).resultsByTime().stream().flatMap(r -> r.groups().stream())
                .map(g -> new DashboardData.BillingSummary(g.keys().get(0), Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                .filter(s -> s.getMonthToDateCost() > 0.01).collect(Collectors.toList()));
        } catch (Exception e) {
             logger.error("Could not fetch billing summary.", e);
             return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    @Async("awsTaskExecutor") @Cacheable("iamResources")
    public CompletableFuture<DashboardData.IamResources> getIamResources() {
        logger.info("Fetching IAM resources...");
        int users=0, groups=0, policies=0, roles=0;
        try { users = iamClient.listUsers().users().size(); } catch (Exception e) { logger.error("IAM check failed for Users", e); }
        try { groups = iamClient.listGroups().groups().size(); } catch (Exception e) { logger.error("IAM check failed for Groups", e); }
        try { policies = iamClient.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size(); } catch (Exception e) { logger.error("IAM check failed for Policies", e); }
        try { roles = iamClient.listRoles().roles().size(); } catch (Exception e) { logger.error("IAM check failed for Roles", e); }
        return CompletableFuture.completedFuture(new DashboardData.IamResources(users, groups, policies, roles));
    }

    @Async("awsTaskExecutor") @Cacheable("costHistory")
    public CompletableFuture<DashboardData.CostHistory> getCostHistory() {
        logger.info("Fetching cost history...");
        List<String> labels = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        try {
            for (int i = 5; i >= 0; i--) {
                LocalDate month = LocalDate.now().minusMonths(i);
                labels.add(month.format(DateTimeFormatter.ofPattern("MMM uuuu")));
                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder().timePeriod(DateInterval.builder().start(month.withDayOfMonth(1).toString()).end(month.plusMonths(1).withDayOfMonth(1).toString()).build()).granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();
                costs.add(Double.parseDouble(costExplorerClient.getCostAndUsage(req).resultsByTime().get(0).total().get("UnblendedCost").amount()));
            }
        } catch (Exception e) {
            logger.error("Could not fetch cost history", e);
        }
        return CompletableFuture.completedFuture(new DashboardData.CostHistory(labels, costs));
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary() {
        // This is still mock data and should be replaced with real calculations in a later phase.
        List<DashboardData.SavingsSuggestion> suggestions = List.of(new DashboardData.SavingsSuggestion("Rightsizing", 155.93), new DashboardData.SavingsSuggestion("Spots", 211.78));
        return CompletableFuture.completedFuture(new DashboardData.SavingsSummary(suggestions.stream().mapToDouble(DashboardData.SavingsSuggestion::getSuggested).sum(), suggestions));
    }

    // --- Private Helper Methods ---
    
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
                       "Unattached Volume"
                   );
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
                .map(address -> new DashboardData.WastedResource(address.allocationId(), address.publicIp(), "Elastic IP", "Global", 5.0, "Unassociated EIP"))
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
                .map(snapshot -> new DashboardData.WastedResource(snapshot.snapshotId(), getTagName(snapshot), "Snapshot", "Regional", calculateSnapshotMonthlyCost(snapshot), "Older than 90 days"))
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
                .map(image -> new DashboardData.WastedResource(image.imageId(), image.name(), "AMI", "Regional", 1.0, "Deregistered or Failed State" ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Sub-task failed: unused AMIs.", e);
            return Collections.emptyList();
        }
    }
    
    private String getTagName(Volume volume) {
        return volume.hasTags() ? volume.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(volume.volumeId()) : volume.volumeId();
    }
    
    private String getTagName(Snapshot snapshot) {
        return snapshot.hasTags() ? snapshot.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(snapshot.snapshotId()) : snapshot.snapshotId();
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
        double totalSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs).flatMap(List::stream).mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings).sum();
        long criticalAlerts = anomalies.size() + ec2Recs.size() + ebsRecs.size() + lambdaRecs.size();
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    private DashboardData.RegionStatus mapRegionToStatus(Region region) {
        double[] coords = REGION_COORDINATES.get(region.regionName());
        String status = "NON_ACTIVE";
        if (!"not-opted-in".equals(region.optInStatus())) {
            try {
                Ec2Client regionClient = Ec2Client.builder().region(software.amazon.awssdk.regions.Region.of(region.regionName())).build();
                DescribeInstancesRequest request = DescribeInstancesRequest.builder().filters(software.amazon.awssdk.services.ec2.model.Filter.builder().name("instance-state-name").values("running").build()).build();
                boolean hasRunningInstances = regionClient.describeInstances(request).hasReservations() && regionClient.describeInstances(request).reservations().stream().anyMatch(res -> res.hasInstances());
                if (hasRunningInstances) { status = "ACTIVE"; } else { status = SUSTAINABLE_REGIONS.contains(region.regionName()) ? "SUSTAINABLE" : "SEMI_ACTIVE"; }
            } catch (Exception e) { logger.warn("Region check failed for {}: {}", region.regionName(), e.getMessage()); status = "SEMI_ACTIVE"; }
        }
        return new DashboardData.RegionStatus(region.regionName(), region.regionName(), status, coords[0], coords[1]);
    }
    
    private String getServiceNameFromAnomaly(Anomaly anomaly) {
        if (anomaly.rootCauses() != null && !anomaly.rootCauses().isEmpty()) {
            RootCause rootCause = anomaly.rootCauses().get(0);
            if (rootCause.service() != null) { return rootCause.service(); }
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
}
