package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Bean("awsTaskExecutor")
    public TaskExecutor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15); // Increased pool size for more parallel tasks
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("AWS-Async-");
        executor.initialize();
        return executor;
    }

    @Bean public Ec2Client ec2Client() { return Ec2Client.builder().region(Region.of(region)).build(); }
    @Bean public IamClient iamClient() { return IamClient.builder().region(Region.AWS_GLOBAL).build(); }
    @Bean public EcsClient ecsClient() { return EcsClient.builder().region(Region.of(region)).build(); }
    @Bean public EksClient eksClient() { return EksClient.builder().region(Region.of(region)).build(); }
    @Bean public LambdaClient lambdaClient() { return LambdaClient.builder().region(Region.of(region)).build(); }
    @Bean public CloudWatchClient cloudWatchClient() { return CloudWatchClient.builder().region(Region.of(region)).build(); }
    @Bean public CostExplorerClient costExplorerClient() { return CostExplorerClient.builder().region(Region.US_EAST_1).build(); }
    @Bean public ComputeOptimizerClient computeOptimizerClient() { return ComputeOptimizerClient.builder().region(Region.of(region)).build(); }
    @Bean public PricingClient pricingClient() { return PricingClient.builder().region(Region.US_EAST_1).build(); }
    @Bean public RdsClient rdsClient() { return RdsClient.builder().region(Region.of(region)).build(); }
    
    // ADDED: New clients for service expansion
    @Bean public S3Client s3Client() { return S3Client.builder().region(Region.of(region)).build(); }
    @Bean public ElasticLoadBalancingV2Client elbv2Client() { return ElasticLoadBalancingV2Client.builder().region(Region.of(region)).build(); }
    @Bean public AutoScalingClient autoScalingClient() { return AutoScalingClient.builder().region(Region.of(region)).build(); }
    @Bean public ElastiCacheClient elastiCacheClient() { return ElastiCacheClient.builder().region(Region.of(region)).build(); }
    @Bean public DynamoDbClient dynamoDbClient() { return DynamoDbClient.builder().region(Region.of(region)).build(); }
    @Bean public EcrClient ecrClient() { return EcrClient.builder().region(Region.of(region)).build(); }
    @Bean public Route53Client route53Client() { return Route53Client.builder().region(Region.AWS_GLOBAL).build(); }
}
