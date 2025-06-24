package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.*;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ImageState;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final AwsCredentialsProvider credentialsProvider;
    
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

    public AwsDataService(Ec2Client ec2, IamClient iam, EcsClient ecs, EksClient eks, LambdaClient lambda, CloudWatchClient cw, CostExplorerClient ce, ComputeOptimizerClient co, AwsCredentialsProvider creds) {
        this.ec2Client = ec2; this.iamClient = iam; this.ecsClient = ecs; this.eksClient = eks; this.lambdaClient = lambda; this.cloudWatchClient = cw; this.costExplorerClient = ce;
        this.computeOptimizerClient = co; this.credentialsProvider = creds;
    }

    @Cacheable("dashboard")
    public DashboardData getDashboardData() {
        logger.info("--- FETCHING FRESH DATA FROM AWS --- (This should only appear periodically)");
        
        List<DashboardData.RegionStatus> regionStatus = getRegionStatusForAccount();
        DashboardData.ResourceInventory resourceInventory = getResourceInventory();
        DashboardData.CloudWatchStatus cloudWatchStatus = getCloudWatchStatus();
        List<DashboardData.SecurityInsight> securityInsights = getSecurityInsights();
        DashboardData.CostHistory costHistory = getCostHistory();
        List<DashboardData.BillingSummary> billingSummary = getBillingSummary();
        DashboardData.IamResources iamResources = getIamResources();
        DashboardData.SavingsSummary savingsSummary = getSavingsSummary();
        List<DashboardData.OptimizationRecommendation> ec2Recommendations = getEc2InstanceRecommendations();
        List<DashboardData.CostAnomaly> costAnomalies = getCostAnomalies();
        List<DashboardData.OptimizationRecommendation> ebsRecommendations = getEbsVolumeRecommendations();
        List<DashboardData.OptimizationRecommendation> lambdaRecommendations = getLambdaFunctionRecommendations();
        DashboardData.ReservationAnalysis reservationAnalysis = getReservationAnalysis();
        List<DashboardData.ReservationPurchaseRecommendation> reservationPurchaseRecommendations = getReservationPurchaseRecommendations();
        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(ec2Recommendations, ebsRecommendations, lambdaRecommendations, costAnomalies);
        
        DashboardData data = new DashboardData();
        DashboardData.Account mainAccount = new DashboardData.Account(
            "123456789012", "MachaDalo",
            regionStatus, resourceInventory, cloudWatchStatus, securityInsights, 
            costHistory, billingSummary, iamResources, savingsSummary, 
            ec2Recommendations, costAnomalies, ebsRecommendations, 
            lambdaRecommendations, reservationAnalysis, reservationPurchaseRecommendations,
            optimizationSummary,
            null // Wasted resources are fetched on a separate page
        );
        data.setAvailableAccounts(List.of(mainAccount, new DashboardData.Account("987654321098", "Xammer", new ArrayList<>(), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
        data.setSelectedAccount(mainAccount);
        return data;
    }

    public List<DashboardData.WastedResource> getWastedResources() {
        List<DashboardData.WastedResource> wasted = new ArrayList<>();
        wasted.addAll(findUnattachedEbsVolumes());
        wasted.addAll(findUnusedElasticIps());
        wasted.addAll(findOldSnapshots());
        wasted.addAll(findDeregisteredAmis());
        // Placeholder for other waste types
        // wasted.addAll(findIdleRdsInstances());
        // wasted.addAll(findIdleLoadBalancers());
        
        // if (wasted.isEmpty() && USE_MOCK_DATA_IF_EMPTY) {
        //     logger.warn("No live wasted resources found. Returning MOCK data for testing.");
        //     wasted.add(new DashboardData.WastedResource("vol-0abcdef1234567890", "Test Volume", "EBS Volume", "us-east-1", 10.0, "Unattached Volume"));
        //     wasted.add(new DashboardData.WastedResource("eipalloc-0fedcba987654321", "198.51.100.1", "Elastic IP", "Global", 5.0, "Unassociated EIP"));
        //     wasted.add(new DashboardData.WastedResource("snap-01a2b3c4d5e6f7g8h", "Old DB Backup", "Snapshot", "us-west-2", 8.0, "Older than 90 days"));
        //     wasted.add(new DashboardData.WastedResource("ami-0a1b2c3d4e5f6g7h8", "Deprecated Web Server", "AMI", "us-east-1", 1.25, "Deregistered / Unused"));
        // }
        return wasted;
    }

    private List<DashboardData.WastedResource> findUnattachedEbsVolumes() {
        logger.info("Finding unattached EBS volumes.");
        try {
            return ec2Client.describeVolumes(req -> req.filters(f -> f.name("status").values("available")))
                .volumes().stream()
                .map(volume -> new DashboardData.WastedResource(
                    volume.volumeId(),
                    getTagNameFromVolume(volume),
                    "EBS Volume",
                    volume.availabilityZone().substring(0, volume.availabilityZone().length() - 1),
                    calculateEbsMonthlyCost(volume),
                    "Unattached Volume"
                ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch unattached EBS volumes.", e);
            return Collections.emptyList();
        }
    }


    private List<DashboardData.WastedResource> findUnusedElasticIps() {
        logger.info("Finding unused Elastic IPs.");
        try {
            return ec2Client.describeAddresses().addresses().stream()
                .filter(address -> address.associationId() == null)
                .map(address -> new DashboardData.WastedResource(
                    address.allocationId(),
                    address.publicIp(),
                    "Elastic IP",
                    "Global",
                    5.0,
                    "Unassociated EIP"
                ))
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch unused Elastic IPs.", e);
            return Collections.emptyList();
        }
    }
    private List<DashboardData.WastedResource> findOldSnapshots() {
        logger.info("Finding old EBS snapshots.");
        try {
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return ec2Client.describeSnapshots(r -> r.ownerIds("self")).snapshots().stream()
                .filter(s -> s.startTime().isBefore(ninetyDaysAgo))
                .map(snapshot -> new DashboardData.WastedResource(
                    snapshot.snapshotId(), getTagNameFromSnapshot(snapshot), "Snapshot",
                    "us-east-1", // Snapshots are regional, this is a placeholder
                    calculateSnapshotMonthlyCost(snapshot), "Older than 90 days"
                )).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch old snapshots.", e);
            return Collections.emptyList();
        }
    }

    private List<DashboardData.WastedResource> findDeregisteredAmis() {
        logger.info("Finding deregistered/unused AMIs.");
        try {
            // IMPROVED LOGIC: Find AMIs that are no longer in an 'available' state.
            // These are typically deregistered or have failed, but their snapshots may still exist.
            DescribeImagesRequest imagesRequest = DescribeImagesRequest.builder().owners("self").build();
            return ec2Client.describeImages(imagesRequest).images().stream()
                .filter(image -> image.state() != ImageState.AVAILABLE)
                .map(image -> new DashboardData.WastedResource(
                    image.imageId(), image.name(), "AMI",
                    "Regional", // AMIs are regional
                    1.0, // Placeholder cost for the snapshot storage
                    "Deregistered or Failed State"
                )).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch unused AMIs.", e);
            return Collections.emptyList();
        }
    }
    
    private String getTagNameFromVolume(Volume volume) {
        if (volume.hasTags()) {
            return volume.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(volume.volumeId());
        }
        return volume.volumeId();
    }
    
    private String getTagNameFromSnapshot(Snapshot snapshot) {
        if (snapshot.hasTags()) {
            return snapshot.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(snapshot.snapshotId());
        }
        return snapshot.snapshotId();
    }
    
    private String getTagName(Volume volume) {
        if (volume.hasTags()) {
            return volume.tags().stream()
                .filter(t -> "Name".equalsIgnoreCase(t.key()))
                .findFirst()
                .map(Tag::value)
                .orElse(volume.volumeId());
        }
        return volume.volumeId();
    }
    
    private double calculateEbsMonthlyCost(Volume volume) {
        return volume.size() * 0.10;
    }

    // Estimate snapshot monthly cost (simple approximation: $0.05 per GB-month)
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
        
        double totalSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
            .flatMap(List::stream)
            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
            .sum();

        long criticalAlerts = anomalies.size() + ec2Recs.size() + ebsRecs.size() + lambdaRecs.size();

        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }
    

private DashboardData.ReservationAnalysis getReservationAnalysis() {
        try {
            logger.info("Fetching reservation utilization and coverage.");
            String today = LocalDate.now().toString();
            String thirtyDaysAgo = LocalDate.now().minusDays(30).toString();
            DateInterval last30Days = DateInterval.builder().start(thirtyDaysAgo).end(today).build();

            GetReservationUtilizationRequest utilRequest = GetReservationUtilizationRequest.builder()
                .timePeriod(last30Days)
                .build();
            
            List<UtilizationByTime> utilizations = costExplorerClient.getReservationUtilization(utilRequest)
                .utilizationsByTime();
            
            GetReservationCoverageRequest covRequest = GetReservationCoverageRequest.builder()
                .timePeriod(last30Days)
                .build();
            
            List<CoverageByTime> coverages = costExplorerClient.getReservationCoverage(covRequest)
                .coveragesByTime();
            
            double utilizationPercentage = utilizations.isEmpty() || utilizations.get(0).total() == null ? 0.0 :
                Double.parseDouble(utilizations.get(0).total().utilizationPercentage());
            
            double coveragePercentage = coverages.isEmpty() || coverages.get(0).total() == null ? 0.0 :
                Double.parseDouble(coverages.get(0).total().coverageHours().coverageHoursPercentage());

            return new DashboardData.ReservationAnalysis(utilizationPercentage, coveragePercentage);
        } catch (Exception e) {
            logger.error("Could not fetch reservation analysis data. This may be due to permissions (ce:GetReservationUtilization, ce:GetReservationCoverage) or no active reservations.", e);
            return new DashboardData.ReservationAnalysis(0.0, 0.0);
        }
    }

    private List<DashboardData.ReservationPurchaseRecommendation> getReservationPurchaseRecommendations() {
        try {
            logger.info("Fetching reservation purchase recommendations.");
            GetReservationPurchaseRecommendationRequest request = GetReservationPurchaseRecommendationRequest.builder()
                .lookbackPeriodInDays(LookbackPeriodInDays.SIXTY_DAYS)
                .service("Amazon Elastic Compute Cloud - Compute")
                .build();
            
            GetReservationPurchaseRecommendationResponse response = costExplorerClient.getReservationPurchaseRecommendation(request);
            
            if (!response.recommendations().isEmpty() && 
                !response.recommendations().get(0).recommendationDetails().isEmpty()) {
                
                ReservationPurchaseRecommendationDetail sampleDetail = 
                    response.recommendations().get(0).recommendationDetails().get(0);
                
                logger.debug("Available methods in ReservationPurchaseRecommendationDetail:");
                Arrays.stream(sampleDetail.getClass().getMethods())
                    .map(Method::getName)
                    .sorted()
                    .forEach(logger::debug);
            }
            
            return response.recommendations().stream()
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
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch reservation purchase recommendations (ce:GetReservationPurchaseRecommendation).", e);
            return Collections.emptyList();
        }
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

    private List<DashboardData.RegionStatus> getRegionStatusForAccount() {
        try {
            return ec2Client.describeRegions().regions().stream()
                .filter(region -> REGION_COORDINATES.containsKey(region.regionName()))
                .map(this::mapRegionToStatus)
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch EC2 regions.", e);
            return new ArrayList<>();
        }
    }

    private DashboardData.RegionStatus mapRegionToStatus(Region region) {
        double[] coords = REGION_COORDINATES.get(region.regionName());
        String status = "NON_ACTIVE";

        if (!"not-opted-in".equals(region.optInStatus())) {
            try {
                Ec2Client regionClient = Ec2Client.builder()
                    .credentialsProvider(credentialsProvider)
                    .region(software.amazon.awssdk.regions.Region.of(region.regionName()))
                    .build();

                DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(Filter.builder().name("instance-state-name").values("running").build())
                    .build();

                boolean hasRunningInstances = regionClient.describeInstances(request).hasReservations() && 
                                              regionClient.describeInstances(request).reservations().stream().anyMatch(res -> res.hasInstances());

                if (hasRunningInstances) {
                    status = "ACTIVE";
                } else {
                    if (SUSTAINABLE_REGIONS.contains(region.regionName())) {
                        status = "SUSTAINABLE";
                    } else {
                        status = "SEMI_ACTIVE";
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to check instance status in region: " + region.regionName(), e);
                status = "SEMI_ACTIVE";
            }
        }
        
        return new DashboardData.RegionStatus(region.regionName(), region.regionName(), status, coords[0], coords[1]);
    }
    
    private List<DashboardData.OptimizationRecommendation> getEbsVolumeRecommendations() {
        try {
            logger.info("Fetching EBS volume recommendations from Compute Optimizer.");
            GetEbsVolumeRecommendationsRequest request = GetEbsVolumeRecommendationsRequest.builder().build();
            List<VolumeRecommendation> recommendations = computeOptimizerClient.getEBSVolumeRecommendations(request).volumeRecommendations();
            return recommendations.stream()
                .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.volumeRecommendationOptions() != null && !r.volumeRecommendationOptions().isEmpty())
                .map(r -> {
                    VolumeRecommendationOption opt = r.volumeRecommendationOptions().get(0);
                    return new DashboardData.OptimizationRecommendation("EBS", r.volumeArn().split("/")[1], r.currentConfiguration().volumeType() + " - " + r.currentConfiguration().volumeSize() + "GiB", opt.configuration().volumeType() + " - " + opt.configuration().volumeSize() + "GiB", opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0, r.finding().toString());
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch EBS volume recommendations.", e);
            return Collections.emptyList();
        }
    }
    
    private List<DashboardData.OptimizationRecommendation> getLambdaFunctionRecommendations() {
        try {
            logger.info("Fetching Lambda function recommendations from Compute Optimizer.");
            GetLambdaFunctionRecommendationsRequest request = GetLambdaFunctionRecommendationsRequest.builder().build();
            List<LambdaFunctionRecommendation> recommendations = computeOptimizerClient.getLambdaFunctionRecommendations(request).lambdaFunctionRecommendations();
            return recommendations.stream()
                .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.memorySizeRecommendationOptions() != null && !r.memorySizeRecommendationOptions().isEmpty())
                .map(r -> {
                    LambdaFunctionMemoryRecommendationOption opt = r.memorySizeRecommendationOptions().get(0);
                    return new DashboardData.OptimizationRecommendation("Lambda", r.functionArn().substring(r.functionArn().lastIndexOf(':') + 1), r.currentMemorySize() + " MB", opt.memorySize() + " MB", opt.savingsOpportunity() != null && opt.savingsOpportunity().estimatedMonthlySavings() != null ? opt.savingsOpportunity().estimatedMonthlySavings().value() : 0.0, r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", ")));
                })
                .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch Lambda function recommendations.", e);
            return Collections.emptyList();
        }
    }
    
    private List<DashboardData.CostAnomaly> getCostAnomalies() {
        try {
            logger.info("Fetching cost anomalies from Cost Explorer.");
            AnomalyDateInterval dateInterval = AnomalyDateInterval.builder().startDate(LocalDate.now().minusDays(60).toString()).endDate(LocalDate.now().toString()).build();
            GetAnomaliesRequest request = GetAnomaliesRequest.builder().dateInterval(dateInterval).build();
            List<Anomaly> anomalies = costExplorerClient.getAnomalies(request).anomalies();
            return anomalies.stream().map(a -> new DashboardData.CostAnomaly(a.anomalyId(), getServiceNameFromAnomaly(a), a.impact().totalImpact(), LocalDate.parse(a.anomalyStartDate()), a.anomalyEndDate() != null ? LocalDate.parse(a.anomalyEndDate()) : LocalDate.now())).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Could not fetch Cost Anomalies.", e);
            return Collections.emptyList();
        }
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

    private List<DashboardData.OptimizationRecommendation> getEc2InstanceRecommendations() {
       try {
           logger.info("Fetching EC2 instance recommendations from Compute Optimizer.");
           GetEc2InstanceRecommendationsRequest request = GetEc2InstanceRecommendationsRequest.builder().build();
           List<InstanceRecommendation> recommendations = computeOptimizerClient.getEC2InstanceRecommendations(request).instanceRecommendations();
           return recommendations.stream()
              .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
              .map(r -> new DashboardData.OptimizationRecommendation("EC2", r.instanceArn().split("/")[1], r.currentInstanceType(), r.recommendationOptions().get(0).instanceType(), r.recommendationOptions().get(0).savingsOpportunity() != null && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings() != null && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings().value() != null ? r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings().value() : 0.0, r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", "))))
              .collect(Collectors.toList());
       } catch (Exception e) {
           logger.error("Could not fetch EC2 instance recommendations.", e);
           return Collections.emptyList();
       }
    }
    
    private DashboardData.ResourceInventory getResourceInventory() {
        int vpc=0, ecs=0, ec2=0, k8s=0, lambdas=0, ebs=0, images=0, snapshots=0;
        try { vpc = ec2Client.describeVpcs().vpcs().size(); } catch (Exception e) { logger.error("Inv check fail: VPCs", e); }
        try { ecs = ecsClient.listClusters().clusterArns().size(); } catch (Exception e) { logger.error("Inv check fail: ECS", e); }
        try { ec2 = ec2Client.describeInstances().reservations().stream().mapToInt(r -> r.instances().size()).sum(); } catch (Exception e) { logger.error("Inv check fail: EC2", e); }
        try { k8s = eksClient.listClusters().clusters().size(); } catch (Exception e) { logger.error("Inv check fail: EKS", e); }
        try { lambdas = lambdaClient.listFunctions().functions().size(); } catch (Exception e) { logger.error("Inv check fail: Lambda", e); }
        try { ebs = ec2Client.describeVolumes().volumes().size(); } catch (Exception e) { logger.error("Inv check fail: EBS", e); }
        try { images = ec2Client.describeImages(r -> r.owners("self")).images().size(); } catch (Exception e) { logger.error("Inv check fail: Images", e); }
        try { snapshots = ec2Client.describeSnapshots(r -> r.ownerIds("self")).snapshots().size(); } catch (Exception e) { logger.error("Inv check fail: Snapshots", e); }
        return new DashboardData.ResourceInventory(vpc, ecs, ec2, k8s, lambdas, ebs, images, snapshots);
    }
    
    private DashboardData.CloudWatchStatus getCloudWatchStatus() {
        try {
            List<software.amazon.awssdk.services.cloudwatch.model.MetricAlarm> alarms = cloudWatchClient.describeAlarms().metricAlarms();
            long ok = alarms.stream().filter(a -> a.stateValueAsString().equals("OK")).count();
            long alarm = alarms.stream().filter(a -> a.stateValueAsString().equals("ALARM")).count();
            long insufficient = alarms.stream().filter(a -> a.stateValueAsString().equals("INSUFFICIENT_DATA")).count();
            return new DashboardData.CloudWatchStatus(ok, alarm, insufficient);
        } catch (Exception e) { logger.error("Could not fetch CloudWatch alarms.", e); return new DashboardData.CloudWatchStatus(0,0,0); }
    }
    
    private List<DashboardData.SecurityInsight> getSecurityInsights() {
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
        return insights;
    }

    private List<DashboardData.BillingSummary> getBillingSummary() {
        try {
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(LocalDate.now().withDayOfMonth(1).toString()).end(LocalDate.now().plusDays(1).toString()).build())
                .granularity(Granularity.MONTHLY).metrics("UnblendedCost")
                .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build()).build();
            return costExplorerClient.getCostAndUsage(request).resultsByTime().stream().flatMap(r -> r.groups().stream())
                .map(g -> new DashboardData.BillingSummary(g.keys().get(0), Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                .filter(s -> s.getMonthToDateCost() > 0.01).collect(Collectors.toList());
        } catch (Exception e) {
             logger.error("Could not fetch billing summary.", e);
             return new ArrayList<>();
        }
    }
    
    private DashboardData.IamResources getIamResources() {
        int users=0, groups=0, policies=0, roles=0;
        try { users = iamClient.listUsers().users().size(); } catch (Exception e) { logger.error("IAM check failed for Users", e); }
        try { groups = iamClient.listGroups().groups().size(); } catch (Exception e) { logger.error("IAM check failed for Groups", e); }
        try { policies = iamClient.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size(); } catch (Exception e) { logger.error("IAM check failed for Policies", e); }
        try { roles = iamClient.listRoles().roles().size(); } catch (Exception e) { logger.error("IAM check failed for Roles", e); }
        return new DashboardData.IamResources(users, groups, policies, roles);
    }
    
    private DashboardData.CostHistory getCostHistory() {
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
        return new DashboardData.CostHistory(labels, costs);
    }
    
    private DashboardData.SavingsSummary getSavingsSummary() {
        List<DashboardData.SavingsSuggestion> suggestions = List.of(new DashboardData.SavingsSuggestion("Rightsizing", 155.93), new DashboardData.SavingsSuggestion("Spots", 211.78));
        return new DashboardData.SavingsSummary(suggestions.stream().mapToDouble(DashboardData.SavingsSuggestion::getSuggested).sum(), suggestions);
    }}