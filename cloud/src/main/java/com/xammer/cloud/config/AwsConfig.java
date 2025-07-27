package com.xammer.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
public class AwsConfig {

    @Value("${aws.region}")
    private String region;

    /**
     * Use the DefaultCredentialsProvider. This is the standard, flexible way to
     * load credentials. It automatically checks environment variables, Java system properties,
     * the shared credentials file (~/.aws/credentials), and IAM roles for EC2/ECS.
     *
     * @return A default AWS credentials provider.
     */
    private DefaultCredentialsProvider getCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    /**
     * Provides the foundational STS client for assuming roles into other accounts.
     * This client uses the host application's own credentials.
     *
     * @return An StsClient instance.
     */
    @Bean
    public StsClient stsClient() {
        return StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }
    
    /**
     * Provides a CloudFormation client for generating stack URLs.
     * This client uses the host application's own credentials.
     *
     * @return A CloudFormationClient instance.
     */
    @Bean
    public CloudFormationClient cloudFormationClient() {
        return CloudFormationClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    /**
     * Provides the Pricing client. This is a global service accessed via us-east-1
     * and should use the host application's credentials.
     *
     * @return A PricingClient instance.
     */
    @Bean
    public PricingClient pricingClient() {
        return PricingClient.builder()
                .credentialsProvider(getCredentialsProvider())
                .region(Region.US_EAST_1)
                .build();
    }

    /**
     * ADDED: Provides a default Ec2Client bean.
     * This bean is required by OperationsService for dependency injection.
     * It will use the host's default credentials, not an assumed role.
     *
     * @return An Ec2Client instance.
     */
    @Bean
    public Ec2Client ec2Client() {
        return Ec2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    /**
     * ADDED: Provides a default RdsClient bean.
     * This bean is required by OperationsService for dependency injection.
     * It will use the host's default credentials, not an assumed role.
     *
     * @return An RdsClient instance.
     */
    @Bean
    public RdsClient rdsClient() {
        return RdsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider())
                .build();
    }

    /**
     * Configures the thread pool for running asynchronous AWS tasks.
     *
     * @return A configured TaskExecutor.
     */
    @Bean("awsTaskExecutor")
    public TaskExecutor threadPoolTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(40);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("AWS-Async-");
        executor.initialize();
        return executor;
    }
}
