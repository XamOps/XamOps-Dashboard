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
@RequestMapping("/api/cloudguard")
public class AlertsApiController {

    private final AwsDataService awsDataService;

    public AlertsApiController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/quotas")
    public ResponseEntity<List<DashboardData.ServiceQuotaInfo>> getQuotaDetails() throws ExecutionException, InterruptedException {
        List<DashboardData.ServiceQuotaInfo> quotas = awsDataService.getServiceQuotaInfo().get();
        return ResponseEntity.ok(quotas);
    }
}