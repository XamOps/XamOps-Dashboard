package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.GenerateStackUrlRequest; // Assuming a DTO exists or is needed
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URL;
import java.util.Map;
import java.util.UUID;

@Service
public class AwsAccountService {

    private static final Logger logger = LoggerFactory.getLogger(AwsAccountService.class);

    private final String hostAccountId;
    private final String configuredRegion;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    public final AwsClientProvider awsClientProvider;

    @Value("${cloudformation.template.s3.url}")
    private String cloudFormationTemplateUrl;

    @Autowired
    public AwsAccountService(
            CloudAccountRepository cloudAccountRepository,
            ClientRepository clientRepository,
            AwsClientProvider awsClientProvider,
            StsClient stsClient) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");

        String tmpAccountId;
        try {
            tmpAccountId = stsClient.getCallerIdentity().account();
        } catch (Exception e) {
            logger.error("Could not determine host AWS Account ID on startup.", e);
            tmpAccountId = "HOST_ACCOUNT_ID_NOT_FOUND"; // Fallback
        }
        this.hostAccountId = tmpAccountId;
    }

    // ✅ MODIFICATION 1: Update method signature to accept all necessary data, ideally via a DTO.
    // For simplicity, adding awsAccountId directly.
    public Map<String, Object> generateCloudFormationUrl(String accountName, String awsAccountId, String accessType, Long clientId) throws Exception {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found with id: " + clientId));

        String externalId = UUID.randomUUID().toString();
        
        // Use the constructor that takes all initial details
        CloudAccount newAccount = new CloudAccount(accountName, externalId, accessType, client);
        
        // ✅ MODIFICATION 2: Set the AWS Account ID before saving
        newAccount.setAwsAccountId(awsAccountId); 
        newAccount.setProvider("AWS");
        cloudAccountRepository.save(newAccount);

        String stackName = "XamOps-Connection-" + accountName.replaceAll("[^a-zA-Z0-9-]", "");
        String xamopsAccountId = this.hostAccountId;
        String urlString = String.format("https://console.aws.amazon.com/cloudformation/home#/stacks/create/review?templateURL=%s&stackName=%s&param_XamOpsAccountId=%s&param_ExternalId=%s", cloudFormationTemplateUrl, stackName, xamopsAccountId, externalId);

        return Map.of(
                "url", new URL(urlString),
                "externalId", externalId
        );
    }

    public CloudAccount verifyAccount(VerifyAccountRequest request) {
        // Find by ExternalId, ensuring it's unique and tied to the pending request
        CloudAccount account = cloudAccountRepository.findByExternalId(request.getExternalId())
                .orElseThrow(() -> new RuntimeException("No pending account found for the given external ID."));

        if (!"PENDING".equals(account.getStatus())) {
            throw new RuntimeException("Account is not in a PENDING state.");
        }

        // The account ID should already be set from the generate URL step, but we ensure it's correct
        account.setAwsAccountId(request.getAwsAccountId());
        String roleArn = String.format("arn:aws:iam::%s:role/%s", request.getAwsAccountId(), request.getRoleName());
        account.setRoleArn(roleArn);

        try {
            // This call will now succeed because the account object has the correct AWS Account ID
            Ec2Client testClient = awsClientProvider.getEc2Client(account, configuredRegion);
            testClient.describeRegions(); // Simple API call to verify role assumption
            
            account.setStatus("CONNECTED");
            logger.info("Successfully verified and connected to account: {}", account.getAccountName());
        } catch (Exception e) {
            account.setStatus("FAILED");
            logger.error("Failed to verify account {}: {}", account.getAccountName(), e.getMessage());
            throw new RuntimeException("Role assumption failed. Please check the role ARN, trust policy, and external ID.", e);
        }
        return cloudAccountRepository.save(account);
    }

    @CacheEvict(value = {"dashboardData", "cloudlistResources", "groupedCloudlistResources", "wastedResources", "regionStatus", "inventory", "cloudwatchStatus", "securityInsights", "ec2Recs", "costAnomalies", "ebsRecs", "lambdaRecs", "reservationAnalysis", "reservationPurchaseRecs", "billingSummary", "iamResources", "costHistory", "allRecommendations", "securityFindings", "serviceQuotas", "reservationPageData", "reservationInventory", "historicalReservationData", "reservationModificationRecs", "eksClusters", "k8sNodes", "k8sNamespaces", "k8sDeployments", "k8sPods", "finopsReport", "costByTag", "budgets", "taggingCompliance", "costByRegion"}, allEntries = true)
    public void clearAllCaches() {
        logger.info("All dashboard caches have been evicted.");
    }
}