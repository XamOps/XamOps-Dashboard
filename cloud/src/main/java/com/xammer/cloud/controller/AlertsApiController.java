package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/cloudguard")
public class AlertsApiController {

    private final AwsDataService awsDataService;
    private final CloudAccountRepository cloudAccountRepository;

    public AlertsApiController(AwsDataService awsDataService, CloudAccountRepository cloudAccountRepository) {
        this.awsDataService = awsDataService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping("/quotas")
    public ResponseEntity<List<DashboardData.ServiceQuotaInfo>> getQuotaDetails(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        List<DashboardData.ServiceQuotaInfo> quotas = awsDataService.getServiceQuotaInfo(account).get();
        return ResponseEntity.ok(quotas);
    }
}
