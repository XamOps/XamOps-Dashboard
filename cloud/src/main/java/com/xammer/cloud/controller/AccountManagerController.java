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
import com.xammer.cloud.service.AwsAccountService; // Import the new service
import com.xammer.cloud.service.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/account-manager")
public class AccountManagerController {

    private final AwsAccountService awsAccountService; // Use the new service
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
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        try {
            Map<String, Object> result = awsAccountService.generateCloudFormationUrl(request.getAccountName(), request.getAccessType(), clientId);
            
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
    public ResponseEntity<?> verifyAccount(@RequestBody VerifyAccountRequest request) {
        try {
            CloudAccount verifiedAccount = awsAccountService.verifyAccount(request);
            return ResponseEntity.ok(Map.of("message", "Account " + verifiedAccount.getAccountName() + " connected successfully!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account verification failed", "message", e.getMessage()));
        }
    }
    
    @PostMapping("/add-gcp-account")
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto request, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Client client = new Client();
        client.setId(userDetails.getClientId());

        try {
            gcpDataService.createGcpAccount(request, client);
            return ResponseEntity.ok(Map.of("message", "GCP Account " + request.getAccountName() + " added successfully!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to add GCP account", "message", e.getMessage()));
        }
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AccountDto>> getAccounts(Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        List<AccountDto> accounts = cloudAccountRepository.findByClientId(clientId).stream()
                .map(this::mapToAccountDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(accounts);
    }

    @DeleteMapping("/accounts/{id}")
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
        String connectionType = "AWS".equals(account.getProvider()) ? "Cross-account role" : "Service Account";

        return new AccountDto(
                account.getId(),
                account.getAccountName(),
                account.getAwsAccountId(),
                account.getAccessType(),
                connectionType,
                account.getStatus(),
                account.getRoleArn(),
                account.getExternalId(),
                account.getProvider()
        );
    }
}