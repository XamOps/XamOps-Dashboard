package com.xammer.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableCaching
@EnableAsync // ADDED: Enables support for asynchronous method execution
public class CloudDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudDashboardApplication.class, args);
    }
}
