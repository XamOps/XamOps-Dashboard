package com.xammer.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching // ADDED: This annotation turns on Spring's caching features
public class CloudDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudDashboardApplication.class, args);
    }
}
