package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.pricing.PricingClient;

@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    @Bean("awsTaskExecutor")
    public TaskExecutor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("AWS-Async-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Ec2Client ec2Client() {
        return Ec2Client.builder().region(Region.of(region)).build();
    }

    @Bean
    public IamClient iamClient() {
        return IamClient.builder().region(Region.AWS_GLOBAL).build();
    }

    @Bean
    public EcsClient ecsClient() {
        return EcsClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public EksClient eksClient() {
        return EksClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public LambdaClient lambdaClient() {
        return LambdaClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder().region(Region.of(region)).build();
    }

    @Bean
    public CostExplorerClient costExplorerClient() {
        return CostExplorerClient.builder().region(Region.US_EAST_1).build();
    }

    @Bean
    public ComputeOptimizerClient computeOptimizerClient() {
        return ComputeOptimizerClient.builder().region(Region.of(region)).build();
    }

    // ADDED: Bean for the AWS Pricing API client.
    @Bean
    public PricingClient pricingClient() {
        // The pricing API is only available in us-east-1 and ap-south-1.
        return PricingClient.builder().region(Region.US_EAST_1).build();
    }
}
