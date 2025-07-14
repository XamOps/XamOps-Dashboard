package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AccountCreationRequestDto;
import com.xammer.cloud.dto.AccountDto;
import com.xammer.cloud.dto.StackCreationDto;
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.AwsDataService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account-manager")
public class AccountManagerController {

    private final AwsDataService awsDataService;

    public AccountManagerController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @PostMapping("/generate-stack-url")
    public ResponseEntity<StackCreationDto> generateStackUrl(@RequestBody AccountCreationRequestDto request) {
        try {
            StackCreationDto dto = awsDataService.generateCloudFormationUrl(request.getAccountName(), request.getAccessType());
            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
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
    
    @GetMapping("/status/{externalId}")
    public ResponseEntity<AccountDto> getAccountStatus(@PathVariable String externalId) {
        return awsDataService.getAccountStatus(externalId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountDto>> getAccounts() {
        return ResponseEntity.ok(awsDataService.getAllAccounts());
    }
}