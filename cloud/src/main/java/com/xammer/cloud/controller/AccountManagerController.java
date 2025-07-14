package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AccountCreationRequestDto;
import com.xammer.cloud.service.AwsDataService;
import com.xammer.cloud.dto.StackCreationDto;
import com.xammer.cloud.dto.AccountDto;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;



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