package com.xammer.cloud.service;

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
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CloudMapService {

    private static final Logger logger = LoggerFactory.getLogger(CloudMapService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService; // Used for fetching active regions
    private final String configuredRegion;

    @Autowired
    public CloudMapService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "vpcListForCloudmap", key = "#accountId")
    public CompletableFuture<List<ResourceDto>> getVpcListForCloudmap(String accountId) {
        CloudAccount account = getAccount(accountId);
        logger.info("Fetching list of VPCs for Cloudmap for account {}...", accountId);
        return cloudListService.getRegionStatusForAccount(account).thenCompose(activeRegions ->
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
                List<Subnet> subnets = ec2.describeSubnets(subnetsRequest).subnets();
                subnets.stream().map(Subnet::availabilityZone).distinct().forEach(azName -> {
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

    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
                .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchFunction.apply(regionStatus.getRegionId());
                    } catch (AwsServiceException e) {
                        logger.warn("CloudMap sub-task failed for account {}: {} in region {}. AWS Error: {}", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e.awsErrorDetails().errorMessage());
                        return Collections.<T>emptyList();
                    } catch (Exception e) {
                        logger.error("CloudMap sub-task failed for account {}: {} in region {}.", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
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

    private String getTagName(List<Tag> tags, String defaultName) {
        if (tags == null || tags.isEmpty()) return defaultName;
        return tags.stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(defaultName);
    }
}