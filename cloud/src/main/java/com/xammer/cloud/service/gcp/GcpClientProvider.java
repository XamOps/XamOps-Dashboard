package com.xammer.cloud.service.gcp;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.dns.Dns;
import com.google.api.services.dns.DnsScopes;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.billing.budgets.v1.BudgetServiceClient;
import com.google.cloud.billing.budgets.v1.BudgetServiceSettings;
import com.google.cloud.compute.v1.*;
import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.cloud.container.v1.ClusterManagerSettings;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.google.cloud.recommender.v1.RecommenderSettings;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import com.google.cloud.securitycenter.v2.SecurityCenterClient; // Correct V2 import
import com.google.cloud.securitycenter.v2.SecurityCenterSettings; // Correct V2 import
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;

@Service
@Slf4j
public class GcpClientProvider {

    private final CloudAccountRepository cloudAccountRepository;

    public GcpClientProvider(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    private Optional<GoogleCredentials> getCredentials(String gcpProjectId) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(gcpProjectId);
        if (accountOpt.isEmpty()) {
            log.error("GCP account not found for project ID: {}", gcpProjectId);
            return Optional.empty();
        }
        try {
            CloudAccount account = accountOpt.get();
            if (account.getGcpServiceAccountKey() == null || account.getGcpServiceAccountKey().isBlank()) {
                log.error("Credentials for GCP project ID: {} are missing.", gcpProjectId);
                return Optional.empty();
            }
            return Optional.of(GoogleCredentials.fromStream(
                    new ByteArrayInputStream(account.getGcpServiceAccountKey().getBytes()))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform"));
        } catch (IOException e) {
            log.error("Failed to create GoogleCredentials for project ID: {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    public Optional<SecurityCenterClient> getSecurityCenterV2Client(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                SecurityCenterSettings settings = SecurityCenterSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return SecurityCenterClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create SecurityCenterClient V2 for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    // --- Other client provider methods ---

    public Optional<InstancesClient> getInstancesClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                InstancesSettings settings = InstancesSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return InstancesClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create InstancesClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<Storage> getStorageClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials ->
                StorageOptions.newBuilder()
                        .setCredentials(credentials)
                        .setProjectId(gcpProjectId)
                        .build()
                        .getService());
    }

    public Optional<BigQuery> getBigQueryClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials ->
                BigQueryOptions.newBuilder()
                        .setCredentials(credentials)
                        .setProjectId(gcpProjectId)
                        .build()
                        .getService());
    }

    public Optional<RecommenderClient> getRecommenderClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                RecommenderSettings settings = RecommenderSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return RecommenderClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create RecommenderClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<MetricServiceClient> getMetricServiceClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                MetricServiceSettings settings = MetricServiceSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return MetricServiceClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create MetricServiceClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<NetworksClient> getNetworksClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                NetworksSettings settings = NetworksSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return NetworksClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create NetworksClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<SubnetworksClient> getSubnetworksClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                SubnetworksSettings settings = SubnetworksSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return SubnetworksClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create SubnetworksClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ClusterManagerClient> getClusterManagerClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ClusterManagerSettings settings = ClusterManagerSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ClusterManagerClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ClusterManagerClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<ProjectsClient> getProjectsClient(String gcpProjectId) {
        return getCredentials(gcpProjectId).map(credentials -> {
            try {
                ProjectsSettings settings = ProjectsSettings.newBuilder()
                        .setCredentialsProvider(() -> credentials)
                        .build();
                return ProjectsClient.create(settings);
            } catch (IOException e) {
                log.error("Failed to create ProjectsClient for project ID: {}", gcpProjectId, e);
                return null;
            }
        });
    }

    public Optional<SQLAdmin> getSqlAdminClient(String gcpProjectId) {
        try {
            Optional<GoogleCredentials> credsOpt = getCredentials(gcpProjectId);
            if (credsOpt.isEmpty()) {
                return Optional.empty();
            }
            GoogleCredentials credentials = credsOpt.get();
            var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            var jsonFactory = new GsonFactory();

            SQLAdmin sqlAdmin = new SQLAdmin.Builder(
                    httpTransport,
                    jsonFactory,
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("xamops-app")
                    .build();
            return Optional.of(sqlAdmin);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to create SQLAdmin client for project ID: {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    public Storage createStorageClient(String serviceAccountKey) throws IOException {
        GoogleCredentials credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccountKey.getBytes()))
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        return StorageOptions.newBuilder().setCredentials(credentials).build().getService();
    }

    public Optional<Dns> getDnsZonesClient(String gcpProjectId) {
        try {
            Optional<GoogleCredentials> credsOpt = getCredentials(gcpProjectId);
            if (credsOpt.isEmpty()) return Optional.empty();
            GoogleCredentials credentials = credsOpt.get().createScoped(DnsScopes.all());
            var httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            var jsonFactory = new GsonFactory();
            Dns dns = new Dns.Builder(httpTransport, jsonFactory, new HttpCredentialsAdapter(credentials))
                    .setApplicationName("xamops-app")
                    .build();
            return Optional.of(dns);
        } catch (Exception e) {
            log.error("Failed to create Dns client for project ID: {}", gcpProjectId, e);
            return Optional.empty();
        }
    }

    public BudgetServiceClient getBudgetServiceClient(String gcpProjectId) throws IOException {
        Optional<GoogleCredentials> credsOpt = getCredentials(gcpProjectId);
        if (credsOpt.isEmpty()) {
            throw new IOException("GoogleCredentials not found for project ID: " + gcpProjectId);
        }
        BudgetServiceSettings budgetServiceSettings = BudgetServiceSettings.newBuilder()
                .setCredentialsProvider(com.google.api.gax.core.FixedCredentialsProvider.create(credsOpt.get()))
                .build();
        return BudgetServiceClient.create(budgetServiceSettings);
    }
}