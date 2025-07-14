package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AccountCreationRequestDto;
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.dto.AccountDto;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account-manager")
public class AccountManagerController {

    private final AwsDataService awsDataService;

    public AccountManagerController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request) {
        try {
            URL stackUrl = awsDataService.generateCloudFormationUrl(request.getAccountName(), request.getAccessType());
            return ResponseEntity.ok(Map.of("url", stackUrl.toString()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not generate CloudFormation URL", "message", e.getMessage()));
        }
    }

    @PostMapping("/verify-stack")
    public ResponseEntity<Map<String, String>> verifyStack(@RequestBody VerifyAccountRequest request) {
        try {
            CloudAccount account = awsDataService.verifyAccount(request.getAccountName(), request.getRoleArn(), request.getExternalId());
            return ResponseEntity.ok(Map.of("status", account.getStatus()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Verification failed", "message", e.getMessage()));
        }
    }

    @GetMapping("/accounts")
    public List<AccountDto> getAccounts() {
        return awsDataService.getAccounts().stream().map(a -> new AccountDto(
                a.getAccountName(),
                a.getAwsAccountId(),
                a.getAccessType(),
                a.getRoleArn() != null ? "Cross-account role" : "-",
                a.getStatus(),
                a.getRoleArn()
        )).toList();
    }
}