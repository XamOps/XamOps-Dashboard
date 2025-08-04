package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster; // Import the new entity
import com.xammer.cloud.dto.AccountCreationRequestDto;
import com.xammer.cloud.dto.AccountDto;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.repository.ClientRepository; // Import ClientRepository
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository; // Import the new repository
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsDataService;
import com.xammer.cloud.service.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/account-manager")
public class AccountManagerController {

    private final AwsDataService awsDataService;
    private final GcpDataService gcpDataService;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;

    public AccountManagerController(AwsDataService awsDataService, GcpDataService gcpDataService, CloudAccountRepository cloudAccountRepository, ClientRepository clientRepository, KubernetesClusterRepository kubernetesClusterRepository) {
        this.awsDataService = awsDataService;
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
            // Service now returns a map
            Map<String, Object> result = awsDataService.generateCloudFormationUrl(request.getAccountName(), request.getAccessType(), clientId);
            
            // Create the response map for the frontend
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
            CloudAccount verifiedAccount = awsDataService.verifyAccount(request);
            return ResponseEntity.ok(Map.of("message", "Account " + verifiedAccount.getAccountName() + " connected successfully!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account verification failed", "message", e.getMessage()));
        }
    }
    
    @PostMapping("/add-gcp-account")
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto request, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Client client = new Client(); // In a real app, you'd fetch this from the DB
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
                    // Step 1: Delete dependent Kubernetes clusters first
                    List<KubernetesCluster> clusters = kubernetesClusterRepository.findByCloudAccountId(account.getId());
                    if (clusters != null && !clusters.isEmpty()) {
                        kubernetesClusterRepository.deleteAll(clusters);
                    }

                    // Step 2: Remove the account from the parent client's list
                    Client client = account.getClient();
                    if (client != null) {
                        client.getCloudAccounts().remove(account);
                        clientRepository.save(client);
                    }

                    // Step 3: Now it's safe to delete the account
                    cloudAccountRepository.delete(account);
                    
                    // Step 4: Clear caches to ensure stale data is removed
                    awsDataService.clearAllCaches();
                    
                    return ResponseEntity.ok(Map.of("message", "Account " + account.getAccountName() + " removed successfully."));
                }).orElse(ResponseEntity.notFound().build());
    }

    private AccountDto mapToAccountDto(CloudAccount account) {
        // ** FIX IS HERE **
        // Determine the connection type based on the provider field
        String connectionType = "AWS".equals(account.getProvider()) ? "Cross-account role" : "Service Account";

        return new AccountDto(
                account.getId(),
                account.getAccountName(),
                account.getAwsAccountId(), // This field holds AWS Account ID or GCP Project ID
                account.getAccessType(),
                connectionType, // Use the corrected connectionType
                account.getStatus(),
                account.getRoleArn(),
                account.getExternalId(),
                account.getProvider()
        );
    }
}