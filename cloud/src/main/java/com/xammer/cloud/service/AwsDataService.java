package com.xammer.cloud.service;

// imports ...
import com.xammer.cloud.dto.StackCreationDto;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AccountDto;
import java.net.URL;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
// ... other imports

@Service
public class AwsDataService {
    // ... existing fields and methods

    public StackCreationDto generateCloudFormationUrl(String accountName, String accessType) throws Exception {
        // 1. Generate a unique external ID for security
        String externalId = UUID.randomUUID().toString();
        // 2. Create and save a record for this new account connection
        CloudAccount newAccount = new CloudAccount(accountName, externalId, accessType);
        cloudAccountRepository.save(newAccount);
        // 3. Define stack parameters
        String stackName = "XamOps-Connection-" + accountName.replaceAll("[^a-zA-Z0-9-]", "");
        String xamopsAccountId = this.accountId; // The account ID of the main XamOps application
        // 4. Construct the Quick Create URL
        String urlString = String.format(
            "https://console.aws.amazon.com/cloudformation/home#/stacks/create/review?templateURL=%s&stackName=%s&param_XamOpsAccountId=%s&param_ExternalId=%s",
            cloudFormationTemplateUrl,
            stackName,
            xamopsAccountId,
            externalId
        );
        return new StackCreationDto(new URL(urlString).toString(), externalId);
    }

    public CloudAccount verifyAccount(String accountName, String roleArn, String externalId) {
        CloudAccount account = cloudAccountRepository.findByExternalId(externalId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid external ID"));
        if (!account.getAccountName().equals(accountName)) {
            throw new IllegalArgumentException("Account name does not match request");
        }
        try {
            AssumeRoleRequest assumeRequest = AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName("XamOpsVerify")
                    .externalId(externalId)
                    .build();
            Credentials creds = stsClient.assumeRole(assumeRequest).credentials();
            AwsSessionCredentials session = AwsSessionCredentials.create(
                    creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken());
            StsClient assumed = StsClient.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(session))
                    .region(stsClient.serviceClientConfiguration().region())
                    .build();
            GetCallerIdentityResponse identity = assumed.getCallerIdentity();
            account.setAwsAccountId(identity.account());
            account.setRoleArn(roleArn);
            account.setStatus("CONNECTED");
            cloudAccountRepository.save(account);
            return account;
        } catch (Exception e) {
            account.setStatus("FAILED");
            cloudAccountRepository.save(account);
            throw new RuntimeException("Could not verify the assumed role. Please check permissions.", e);
        }
    }

    public Optional<AccountDto> getAccountStatus(String externalId) {
        return cloudAccountRepository.findByExternalId(externalId)
                .map(acc -> new AccountDto(acc.getAccountName(), acc.getAwsAccountId(),
                        acc.getAccessType(), "Cross-account role", acc.getStatus(), acc.getRoleArn()));
    }

    public void markAccountConnected(String externalId, String awsAccountId, String roleArn) {
        cloudAccountRepository.findByExternalId(externalId).ifPresent(acc -> {
            acc.setAwsAccountId(awsAccountId);
            acc.setRoleArn(roleArn);
            acc.setStatus("CONNECTED");
            cloudAccountRepository.save(acc);
        });
    }

    public List<AccountDto> getAllAccounts() {
        return cloudAccountRepository.findAll().stream()
                .map(acc -> new AccountDto(acc.getAccountName(), acc.getAwsAccountId(),
                        acc.getAccessType(), "Cross-account role", acc.getStatus(), acc.getRoleArn()))
                .collect(Collectors.toList());
    }

    public List<CloudAccount> getAccounts() {
        return cloudAccountRepository.findAll();
    }
    
    // ... other existing methods
}