package com.xammer.cloud.service;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.GcpAccountRequestDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class GcpDataService {

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public GcpDataService(GcpClientProvider gcpClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    public void createGcpAccount(GcpAccountRequestDto request, Client client) throws IOException {
        try {
            // First, verify the credentials are valid by creating a client
            Storage storage = gcpClientProvider.createStorageClient(request.getServiceAccountKey());
            // Test the connection by listing buckets
            storage.list(Storage.BucketListOption.pageSize(1));
            // If no exception, proceed to save the account
            CloudAccount account = new CloudAccount();
            account.setAccountName(request.getAccountName());
            account.setProvider("GCP");
            account.setAwsAccountId(request.getProjectId()); // Re-using this field for Project ID
            account.setGcpServiceAccountKey(request.getServiceAccountKey());
            account.setStatus("CONNECTED");
            account.setAccessType("read-only"); // Default for now
            account.setClient(client);
            account.setExternalId(request.getProjectId()); // Set externalId to projectId for GCP
            account.setGcpProjectId(request.getProjectId()); // Set gcpProjectId for GCP
            cloudAccountRepository.save(account);
        } catch (IOException e) {
            // Propagate the exact error message from GCP
            throw new RuntimeException("GCP connection error: " + e.getMessage(), e);
        } catch (Exception e) {
            // Catch any other exceptions and propagate their exact message
            throw new RuntimeException("GCP error: " + e.getMessage(), e);
        }
    }


    public List<String> getGcpBucketList(String projectId) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(projectId)
                .orElseThrow(() -> new RuntimeException("GCP Account not found for project: " + projectId));

        if (!"GCP".equals(account.getProvider())) {
            throw new IllegalArgumentException("Account is not a GCP account.");
        }

        try {
            Storage storage = gcpClientProvider.createStorageClient(account.getGcpServiceAccountKey());
            List<String> bucketNames = new ArrayList<>();
            Page<Bucket> buckets = storage.list();
            for (Bucket bucket : buckets.iterateAll()) {
                bucketNames.add(bucket.getName());
            }
            return bucketNames;
        } catch (IOException e) {
            throw new RuntimeException("GCP connection error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("GCP error: " + e.getMessage(), e);
        }
    }

    public List<Bucket> getBuckets(CloudAccount account) throws IOException {
        Storage storage = gcpClientProvider.createStorageClient(account.getGcpServiceAccountKey());
        return StreamSupport.stream(storage.list().iterateAll().spliterator(), false)
                .collect(Collectors.toList());
    }
}