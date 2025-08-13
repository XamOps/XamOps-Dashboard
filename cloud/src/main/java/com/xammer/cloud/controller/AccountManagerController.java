package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.AccountCreationRequestDto;
import com.xammer.cloud.dto.AccountDto;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller; // Changed from @RestController
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller // Changed from @RestController to support both API and view redirection
@RequestMapping("/api/account-manager")
public class AccountManagerController {

    private final AwsAccountService awsAccountService;
    private final GcpDataService gcpDataService;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;

    public AccountManagerController(AwsAccountService awsAccountService, GcpDataService gcpDataService, CloudAccountRepository cloudAccountRepository, ClientRepository clientRepository, KubernetesClusterRepository kubernetesClusterRepository) {
        this.awsAccountService = awsAccountService;
        this.gcpDataService = gcpDataService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
    }

    @PostMapping("/generate-stack-url")
    @ResponseBody // Added to ensure the response is treated as a JSON body
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        try {
            Map<String, Object> result = awsAccountService.generateCloudFormationUrl(request.getAccountName(), request.getAccessType(), "", clientId);

            Map<String, String> stackDetails = Map.of(
                "url", result.get("url").toString(),
                "externalId", result.get("externalId").toString()
            );

            return ResponseEntity.ok(stackDetails);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not generate CloudFormation URL", "message", e.getMessage()));
        }
    }

    @PostMapping("/verify-account")
    @ResponseBody // Added for JSON response
    public ResponseEntity<?> verifyAccount(@RequestBody VerifyAccountRequest request) {
        try {
            CloudAccount verifiedAccount = awsAccountService.verifyAccount(request);
            return ResponseEntity.ok(Map.of("message", "Account " + verifiedAccount.getAccountName() + " connected successfully!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account verification failed", "message", e.getMessage()));
        }
    }

    @GetMapping("/accounts")
    @ResponseBody // Added for JSON response
    public ResponseEntity<List<AccountDto>> getAccounts(Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        List<AccountDto> accounts = cloudAccountRepository.findByClientId(clientId).stream()
                .map(this::mapToAccountDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(accounts);
    }

    @DeleteMapping("/accounts/{id}")
    @ResponseBody // Added for JSON response
    public ResponseEntity<?> deleteAccount(@PathVariable Long id) {
        return cloudAccountRepository.findById(id)
                .map(account -> {
                    List<KubernetesCluster> clusters = kubernetesClusterRepository.findByCloudAccountId(account.getId());
                    if (clusters != null && !clusters.isEmpty()) {
                        kubernetesClusterRepository.deleteAll(clusters);
                    }

                    Client client = account.getClient();
                    if (client != null) {
                        client.getCloudAccounts().remove(account);
                        clientRepository.save(client);
                    }

                    cloudAccountRepository.delete(account);

                    // Call the new service to clear all caches
                    awsAccountService.clearAllCaches();

                    return ResponseEntity.ok(Map.of("message", "Account " + account.getAccountName() + " removed successfully."));
                }).orElse(ResponseEntity.notFound().build());
    }

    private AccountDto mapToAccountDto(CloudAccount account) {
        String connectionType = "AWS".equals(account.getProvider()) ? "Cross-account role" : "Workload Identity Federation"; // Updated for GCP

        return new AccountDto(
                account.getId(),
                account.getAccountName(),
                "AWS".equals(account.getProvider()) ? account.getAwsAccountId() : account.getGcpProjectId(), // Use appropriate ID
                account.getAccessType(),
                connectionType,
                account.getStatus(),
                account.getRoleArn(),
                account.getExternalId(),
                account.getProvider()
        );
    }

    // This is the correct method for handling the form submission from add-gcp-account.html
    @PostMapping("/add-gcp-account")
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto gcpAccountRequestDto, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        Client client = clientRepository.findById(clientId).orElse(null);
        try {
            gcpDataService.createGcpAccount(gcpAccountRequestDto, client);
            return ResponseEntity.ok().build();
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500).body("IOException: " + e.getMessage());
        }
    }
}