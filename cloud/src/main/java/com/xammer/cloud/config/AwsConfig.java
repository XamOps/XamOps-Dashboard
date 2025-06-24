package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;

@Configuration
public class AwsConfig {

    @Value("${aws.credentials.access-key-id}")
    private String accessKey;

    @Value("${aws.credentials.secret-access-key}")
    private String secretKey;

    @Value("${aws.region}")
    private String region;

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    @Bean
    public Ec2Client ec2Client(AwsCredentialsProvider p) { return Ec2Client.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    
    @Bean
    public IamClient iamClient(AwsCredentialsProvider p) { return IamClient.builder().credentialsProvider(p).region(Region.AWS_GLOBAL).build(); }
    
    @Bean
    public EcsClient ecsClient(AwsCredentialsProvider p) { return EcsClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    
    @Bean
    public EksClient eksClient(AwsCredentialsProvider p) { return EksClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    
    @Bean
    public LambdaClient lambdaClient(AwsCredentialsProvider p) { return LambdaClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    
    @Bean
    public CloudWatchClient cloudWatchClient(AwsCredentialsProvider p) { return CloudWatchClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    
    @Bean
    public CostExplorerClient costExplorerClient(AwsCredentialsProvider p) { return CostExplorerClient.builder().credentialsProvider(p).region(Region.US_EAST_1).build(); }
    
    @Bean
    public ComputeOptimizerClient computeOptimizerClient(AwsCredentialsProvider p) {
        return ComputeOptimizerClient.builder()
                .credentialsProvider(p)
                .region(Region.of(region))
                .build();
    }
}
