package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendation;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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

    public AwsDataService(Ec2Client ec2, IamClient iam, EcsClient ecs, EksClient eks, LambdaClient lambda, CloudWatchClient cw, CostExplorerClient ce, ComputeOptimizerClient co) {
        this.ec2Client = ec2; this.iamClient = iam; this.ecsClient = ecs; this.eksClient = eks; this.lambdaClient = lambda; this.cloudWatchClient = cw; this.costExplorerClient = ce;
        this.computeOptimizerClient = co;
    }

    @Cacheable("dashboard")
    public DashboardData getDashboardData() {
        logger.info("--- FETCHING FRESH DATA FROM AWS --- (This should only appear periodically)");
        DashboardData data = new DashboardData();
        DashboardData.Account mainAccount = new DashboardData.Account(
            "123456789012", "MachaDalo",
            getRegionStatusForAccount(),
            getResourceInventory(),
            getCloudWatchStatus(),
            getSecurityInsights(),
            getCostHistory(),
            getBillingSummary(),
            getIamResources(),
            getSavingsSummary(),
            getEc2InstanceRecommendations()
        );
        data.setAvailableAccounts(List.of(mainAccount, new DashboardData.Account("987654321098", "Xammer", new ArrayList<>(), null, null, null, null, null, null, null, null)));
        data.setSelectedAccount(mainAccount);
        return data;
    }

private List<DashboardData.OptimizationRecommendation> getEc2InstanceRecommendations() {
    try {
        logger.info("Fetching EC2 instance recommendations from Compute Optimizer.");
        GetEc2InstanceRecommendationsRequest request = GetEc2InstanceRecommendationsRequest.builder().build();

        List<InstanceRecommendation> recommendations = computeOptimizerClient.getEC2InstanceRecommendations(request).instanceRecommendations();

        return recommendations.stream()
            .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED") && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
            .map(r -> new DashboardData.OptimizationRecommendation(
                    "EC2",
                    r.instanceArn().split("/")[1],
                    r.currentInstanceType(),
                    r.recommendationOptions().get(0).instanceType(),
                    r.recommendationOptions().get(0).savingsOpportunity() != null
                        && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings() != null
                        && r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings().value() != null
                        ? r.recommendationOptions().get(0).savingsOpportunity().estimatedMonthlySavings().value()
                        : 0.0,
                    r.findingReasonCodes().stream().map(Object::toString).collect(Collectors.joining(", "))
            ))
            .collect(Collectors.toList());
    } catch (Exception e) {
        logger.error("Could not fetch EC2 instance recommendations. Please ensure AWS Compute Optimizer is enabled and you have the correct IAM permissions (compute-optimizer:GetEC2InstanceRecommendations).", e);
        return Collections.emptyList();
    }
}

    private List<DashboardData.RegionStatus> getRegionStatusForAccount() {
        try {
            return ec2Client.describeRegions().regions().stream()
                .filter(region -> REGION_COORDINATES.containsKey(region.regionName()))
                .map(this::mapRegionToStatus).collect(Collectors.toList());
        } catch (Exception e) { logger.error("Could not fetch EC2 regions.", e); return new ArrayList<>(); }
    }

    private DashboardData.RegionStatus mapRegionToStatus(Region region) {
        double[] coords = REGION_COORDINATES.get(region.regionName());
        String[] statuses = {"ACTIVE", "SEMI_ACTIVE", "SUSTAINABLE", "NON_ACTIVE"};
        String randomStatus = statuses[ThreadLocalRandom.current().nextInt(statuses.length)];
        return new DashboardData.RegionStatus(region.regionName(), region.regionName(), randomStatus, coords[0], coords[1]);
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
        } catch (Exception e) { logger.error("Could not fetch billing summary.", e); return new ArrayList<>(); }
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
        List<String> labels = new ArrayList<>(); List<Double> costs = new ArrayList<>();
        try {
            for (int i = 5; i >= 0; i--) {
                LocalDate month = LocalDate.now().minusMonths(i);
                labels.add(month.format(DateTimeFormatter.ofPattern("MMM uuuu")));
                GetCostAndUsageRequest req = GetCostAndUsageRequest.builder().timePeriod(DateInterval.builder().start(month.withDayOfMonth(1).toString()).end(month.plusMonths(1).withDayOfMonth(1).toString()).build()).granularity(Granularity.MONTHLY).metrics("UnblendedCost").build();
                costs.add(Double.parseDouble(costExplorerClient.getCostAndUsage(req).resultsByTime().get(0).total().get("UnblendedCost").amount()));
            }
        } catch (Exception e) { logger.error("Could not fetch cost history", e); }
        return new DashboardData.CostHistory(labels, costs);
    }
    
    private DashboardData.SavingsSummary getSavingsSummary() {
        List<DashboardData.SavingsSuggestion> suggestions = List.of(new DashboardData.SavingsSuggestion("Rightsizing", 155.93), new DashboardData.SavingsSuggestion("Spots", 211.78));
        return new DashboardData.SavingsSummary(suggestions.stream().mapToDouble(DashboardData.SavingsSuggestion::getSuggested).sum(), suggestions);
    }
}
