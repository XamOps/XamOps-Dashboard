package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final AwsDataService awsDataService;

    public SecurityController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/findings")
    public ResponseEntity<List<DashboardData.SecurityFinding>> getSecurityFindings() throws ExecutionException, InterruptedException {
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings().get();
        return ResponseEntity.ok(findings);
    }
}
