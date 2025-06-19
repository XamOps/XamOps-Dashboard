package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
// The S3Client import is no longer needed

@Configuration
public class AwsConfig {

    @Value("${aws.credentials.access-key-id}")
    private String accessKey;

    @Value("${aws.credentials.secret-access-key}")
    private String secretKey;

    @Value("${aws.region}")
    private String region;

    @Bean
    public StaticCredentialsProvider staticCredentialsProvider() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    @Bean
    public Ec2Client ec2Client(StaticCredentialsProvider p) { return Ec2Client.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    @Bean
    public IamClient iamClient(StaticCredentialsProvider p) { return IamClient.builder().credentialsProvider(p).region(Region.AWS_GLOBAL).build(); }
    @Bean
    public EcsClient ecsClient(StaticCredentialsProvider p) { return EcsClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    @Bean
    public EksClient eksClient(StaticCredentialsProvider p) { return EksClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    @Bean
    public LambdaClient lambdaClient(StaticCredentialsProvider p) { return LambdaClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    @Bean
    public CloudWatchClient cloudWatchClient(StaticCredentialsProvider p) { return CloudWatchClient.builder().credentialsProvider(p).region(Region.of(region)).build(); }
    @Bean
    public CostExplorerClient costExplorerClient(StaticCredentialsProvider p) { return CostExplorerClient.builder().credentialsProvider(p).region(Region.US_EAST_1).build(); }
    
    // The unused S3Client bean has been removed to resolve the error.
}
