package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/cloudlist")
public class CloudlistController {

    private final AwsDataService awsDataService;

    public CloudlistController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/resources")
    public ResponseEntity<List<ResourceDto>> getAllResources() throws ExecutionException, InterruptedException {
        List<ResourceDto> resources = awsDataService.getAllResources().get();
        return ResponseEntity.ok(resources);
    }
}
