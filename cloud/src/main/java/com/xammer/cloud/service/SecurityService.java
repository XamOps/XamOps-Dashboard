package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Trail;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.FlowLog;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeyMetadata;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SecurityService {

    private static final Logger logger = LoggerFactory.getLogger(SecurityService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final String configuredRegion;

    @Autowired
    public SecurityService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }
    
    @Async("awsTaskExecutor")
    @Cacheable(value = "securityFindings", key = "#account.awsAccountId")
    public CompletableFuture<List<DashboardData.SecurityFinding>> getComprehensiveSecurityFindings(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        logger.info("Starting comprehensive security scan for account {}...", account.getAwsAccountId());
        List<CompletableFuture<List<DashboardData.SecurityFinding>>> futures = List.of(
            findUsersWithoutMfa(account), findPublicS3Buckets(account), findUnrestrictedSecurityGroups(account, activeRegions),
            findVpcsWithoutFlowLogs(account, activeRegions), checkCloudTrailStatus(account, activeRegions), findUnusedIamRoles(account),
            findSoc2ComplianceFindings(account, activeRegions)
        );
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).flatMap(List::stream).collect(Collectors.toList()));
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findUsersWithoutMfa(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for IAM users without MFA...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            IamClient iam = awsClientProvider.getIamClient(account);
            try {
                iam.listUsers().users().forEach(user -> {
                    try {
                        if (user.passwordLastUsed() != null || iam.getLoginProfile(r -> r.userName(user.userName())).sdkHttpResponse().isSuccessful()) {
                            ListMfaDevicesResponse mfaDevicesResponse = iam.listMFADevices(r -> r.userName(user.userName()));
                            if (!mfaDevicesResponse.hasMfaDevices() || mfaDevicesResponse.mfaDevices().isEmpty()) {
                                findings.add(new DashboardData.SecurityFinding(user.userName(), "Global", "IAM", "High", "User has console access but MFA is not enabled.", "CIS AWS Foundations", "1.2"));
                            }
                        }
                    } catch (NoSuchEntityException e) {
                        // This is expected for users without a login profile, so we can ignore it.
                    }
                });
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not check for MFA on users.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findPublicS3Buckets(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for public S3 buckets...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            S3Client s3GlobalClient = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                List<Bucket> buckets = s3GlobalClient.listBuckets().buckets();
                buckets.parallelStream().forEach(bucket -> {
                    String bucketName = bucket.name();
                    String bucketRegion = null;

                    try {
                        String locationConstraint = s3GlobalClient.getBucketLocation(r -> r.bucket(bucketName)).locationConstraintAsString();
                        bucketRegion = (locationConstraint == null || locationConstraint.isEmpty()) ? "us-east-1" : locationConstraint;
                    } catch (S3Exception e) {
                        bucketRegion = e.awsErrorDetails().sdkHttpResponse()
                                .firstMatchingHeader("x-amz-bucket-region")
                                .orElse(null);

                        if (bucketRegion == null) {
                            String message = e.awsErrorDetails().errorMessage();
                            if (message != null && message.contains("expecting")) {
                                String[] parts = message.split("'");
                                if (parts.length >= 4) {
                                    bucketRegion = parts[3];
                                }
                            }
                        }

                        if (bucketRegion == null) {
                            logger.warn("Could not determine region for bucket {} from S3Exception. Skipping. Error: {}", bucketName, e.getMessage());
                            return;
                        }
                    } catch (Exception e) {
                        logger.warn("General error getting location for bucket {} for public access check. Skipping. Error: {}", bucketName, e.getMessage());
                        return;
                    }

                    S3Client regionalS3Client = awsClientProvider.getS3Client(account, bucketRegion);
                    boolean isPublic = false;
                    String reason = "";

                    try {
                        PublicAccessBlockConfiguration pab = regionalS3Client.getPublicAccessBlock(r -> r.bucket(bucketName)).publicAccessBlockConfiguration();
                        if (!pab.blockPublicAcls() || !pab.ignorePublicAcls() || !pab.blockPublicPolicy() || !pab.restrictPublicBuckets()) {
                            isPublic = true;
                            reason = "Public Access Block is not fully enabled.";
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get Public Access Block for bucket {}. Checking ACLs. Error: {}", bucketName, e.getMessage());
                    }

                    if (!isPublic) {
                        try {
                            boolean hasPublicAcl = regionalS3Client.getBucketAcl(r -> r.bucket(bucketName)).grants().stream()
                                .anyMatch(grant -> {
                                    String granteeUri = grant.grantee().uri();
                                    return (granteeUri != null && (granteeUri.endsWith("AllUsers") || granteeUri.endsWith("AuthenticatedUsers")))
                                        && (grant.permission() == Permission.READ || grant.permission() == Permission.WRITE || grant.permission() == Permission.FULL_CONTROL);
                                });
                            if (hasPublicAcl) {
                                isPublic = true;
                                reason = "Bucket ACL grants public access.";
                            }
                        } catch (Exception e) {
                             logger.warn("Could not check ACL for bucket {}: {}", bucketName, e.getMessage());
                        }
                    }

                    if (isPublic) {
                        findings.add(new DashboardData.SecurityFinding(bucketName, bucketRegion, "S3", "Critical", reason, "CIS AWS Foundations", "2.1.2"));
                    }
                });
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not list S3 buckets.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findUnrestrictedSecurityGroups(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            logger.info("Security Scan for account {}: Checking for unrestricted security groups in {}...", account.getAwsAccountId(), regionId);
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            try {
                ec2.describeSecurityGroups().securityGroups().forEach(sg -> {
                    sg.ipPermissions().forEach(perm -> {
                        boolean openToWorld = perm.ipRanges().stream().anyMatch(ip -> "0.0.0.0/0".equals(ip.cidrIp()));
                        if (openToWorld) {
                            String description = String.format("Allows inbound traffic from anywhere (0.0.0.0/0) on port(s) %s",
                                perm.fromPort() == null ? "ALL" : (Objects.equals(perm.fromPort(), perm.toPort()) ? perm.fromPort().toString() : perm.fromPort() + "-" + perm.toPort()));
                            findings.add(new DashboardData.SecurityFinding(sg.groupId(), regionId, "VPC", "Critical", description, "CIS AWS Foundations", "4.1"));
                        }
                    });
                });
            } catch (Exception e) {
                logger.error("Failed to check security groups in region {} for account {}", regionId, account.getAwsAccountId(), e);
            }
            return findings;
        }, "Unrestricted Security Groups");
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findVpcsWithoutFlowLogs(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            logger.info("Security Scan for account {}: Checking for VPCs without Flow Logs in {}...", account.getAwsAccountId(), regionId);
            try {
                Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
                Set<String> vpcsWithFlowLogs = ec2.describeFlowLogs().flowLogs().stream().map(FlowLog::resourceId).collect(Collectors.toSet());
                return ec2.describeVpcs().vpcs().stream()
                        .filter(vpc -> !vpcsWithFlowLogs.contains(vpc.vpcId()))
                        .map(vpc -> new DashboardData.SecurityFinding(vpc.vpcId(), regionId, "VPC", "Medium", "VPC does not have Flow Logs enabled.", "CIS AWS Foundations", "2.9"))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Failed to check for VPC Flow Logs in region {} for account {}", regionId, account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        }, "VPCs without Flow Logs");
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> checkCloudTrailStatus(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking CloudTrail status...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            if (activeRegions.isEmpty()) {
                logger.warn("No active regions found for account {}, skipping CloudTrail check.", account.getAwsAccountId());
                return findings;
            }
            try {
                CloudTrailClient cloudTrail = awsClientProvider.getCloudTrailClient(account, configuredRegion);
                List<Trail> trails = cloudTrail.describeTrails().trailList();
                if (trails.isEmpty()) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "CloudTrail", "Critical", "No CloudTrail trails are configured for the account.", "CIS AWS Foundations", "2.1"));
                    return findings;
                }
                boolean hasActiveMultiRegionTrail = trails.stream().anyMatch(t -> {
                    try {
                        CloudTrailClient regionalTrailClient = awsClientProvider.getCloudTrailClient(account, t.homeRegion());
                        boolean isLogging = regionalTrailClient.getTrailStatus(r -> r.name(t.name())).isLogging();
                        return t.isMultiRegionTrail() && isLogging;
                    } catch (Exception e) {
                        logger.warn("Could not get status for trail {}, assuming not logging.", t.name());
                        return false;
                    }
                });
                if (!hasActiveMultiRegionTrail) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "CloudTrail", "High", "No active, multi-region CloudTrail trail found.", "CIS AWS Foundations", "2.1"));
                }
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not check CloudTrail status.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findUnusedIamRoles(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for unused IAM roles...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            IamClient iam = awsClientProvider.getIamClient(account);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            try {
                iam.listRoles().roles().stream()
                        .filter(role -> !role.path().startsWith("/aws-service-role/"))
                        .forEach(role -> {
                            try {
                                Role lastUsed = iam.getRole(r -> r.roleName(role.roleName())).role();
                                if (lastUsed.roleLastUsed() == null || lastUsed.roleLastUsed().lastUsedDate() == null) {
                                    if (role.createDate().isBefore(ninetyDaysAgo)) {
                                        findings.add(new DashboardData.SecurityFinding(role.roleName(), "Global", "IAM", "Medium", "Role has never been used and was created over 90 days ago.", "Custom Best Practice", "IAM-001"));
                                    }
                                } else if (lastUsed.roleLastUsed().lastUsedDate().isBefore(ninetyDaysAgo)) {
                                    findings.add(new DashboardData.SecurityFinding(role.roleName(), "Global", "IAM", "Low", "Role has not been used in over 90 days.", "Custom Best Practice", "IAM-002"));
                                }
                            } catch (Exception e) {
                                logger.warn("Could not get last used info for role {} in account {}: {}", role.roleName(), account.getAwsAccountId(), e.getMessage());
                            }
                        });
            } catch (Exception e) {
                logger.error("Security Scan failed for account {}: Could not check for unused IAM roles.", account.getAwsAccountId(), e);
            }
            return findings;
        });
    }

    private CompletableFuture<List<DashboardData.SecurityFinding>> findSoc2ComplianceFindings(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return CompletableFuture.supplyAsync(() -> {
            logger.info("Security Scan for account {}: Checking for SOC2 compliance...", account.getAwsAccountId());
            List<DashboardData.SecurityFinding> findings = new ArrayList<>();
            
            findings.addAll(checkSoc2AccessControls(account));
            
            findings.addAll(checkSoc2DataTransmission(account, activeRegions));
            
            findings.addAll(checkSoc2SystemMonitoring(account, activeRegions));
            
            findings.addAll(checkSoc2DataClassification(account));
            
            findings.addAll(checkSoc2LogicalAccessSecurity(account));
            
            return findings;
        });
    }
    
    private List<DashboardData.SecurityFinding> checkSoc2AccessControls(CloudAccount account) {
        List<DashboardData.SecurityFinding> findings = new ArrayList<>();
        IamClient iam = awsClientProvider.getIamClient(account);
        
        try {
            ListAccessKeysResponse rootKeys = iam.listAccessKeys(r -> r.userName("root"));
            if (rootKeys.hasAccessKeyMetadata() && !rootKeys.accessKeyMetadata().isEmpty()) {
                findings.add(new DashboardData.SecurityFinding("root", "Global", "IAM", "Critical", 
                    "Root user has active access keys, violating SOC2 access control requirements.", "SOC2", "CC6.1"));
            }
            
            iam.listUsers().users().forEach(user -> {
                try {
                    boolean hasConsoleAccess = false;
                    boolean hasProgrammaticAccess = false;
                    
                    try {
                        iam.getLoginProfile(r -> r.userName(user.userName()));
                        hasConsoleAccess = true;
                    } catch (NoSuchEntityException e) {
                        
                    }
                    
                    ListAccessKeysResponse userKeys = iam.listAccessKeys(r -> r.userName(user.userName()));
                    if (userKeys.hasAccessKeyMetadata() && !userKeys.accessKeyMetadata().isEmpty()) {
                        hasProgrammaticAccess = true;
                    }
                    
                    if (hasConsoleAccess && hasProgrammaticAccess) {
                        findings.add(new DashboardData.SecurityFinding(user.userName(), "Global", "IAM", "Medium",
                            "User has both console and programmatic access, violating SOC2 access segregation principles.", "SOC2", "CC6.1"));
                    }
                } catch (Exception e) {
                    logger.warn("Could not check access types for user {}: {}", user.userName(), e.getMessage());
                }
            });
            
        } catch (AwsServiceException e) {
            if (e.statusCode() == 403) {
                 logger.warn("SOC2 access control check skipped due to insufficient permissions: {}", e.awsErrorDetails().errorMessage());
            } else {
                 logger.error("SOC2 access control check failed for account {}: {}", account.getAwsAccountId(), e.getMessage());
            }
        }
        
        return findings;
    }
    
    private List<DashboardData.SecurityFinding> checkSoc2DataTransmission(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        List<DashboardData.SecurityFinding> findings = new ArrayList<>();
        
        S3Client s3GlobalClient = awsClientProvider.getS3Client(account, "us-east-1");
        try {
            List<Bucket> buckets = s3GlobalClient.listBuckets().buckets();
            buckets.forEach(bucket -> {
                String bucketName = bucket.name();
                String bucketRegion = "us-east-1";
                
                try {
                    String locationConstraint = s3GlobalClient.getBucketLocation(r -> r.bucket(bucketName)).locationConstraintAsString();
                    if (locationConstraint != null && !locationConstraint.isEmpty()) {
                        bucketRegion = locationConstraint;
                    }
                } catch (Exception e) {
                    
                }
                
                S3Client regionalS3Client = awsClientProvider.getS3Client(account, bucketRegion);
                
                try {
                    
                    GetBucketEncryptionResponse encryption = regionalS3Client.getBucketEncryption(r -> r.bucket(bucketName));
                    if (encryption.serverSideEncryptionConfiguration() == null) {
                        findings.add(new DashboardData.SecurityFinding(bucketName, bucketRegion, "S3", "High",
                            "S3 bucket does not have encryption enabled, violating SOC2 data protection requirements.", "SOC2", "CC6.7"));
                    }
                } catch (Exception e) {
                    findings.add(new DashboardData.SecurityFinding(bucketName, bucketRegion, "S3", "High",
                        "S3 bucket encryption configuration could not be verified, violating SOC2 data protection requirements.", "SOC2", "CC6.7"));
                }
                
                try {
                    
                    GetBucketPolicyResponse policyResponse = regionalS3Client.getBucketPolicy(r -> r.bucket(bucketName));
                    String policy = policyResponse.policy();
                    if (policy == null || !policy.contains("aws:SecureTransport")) {
                        findings.add(new DashboardData.SecurityFinding(bucketName, bucketRegion, "S3", "Medium",
                            "S3 bucket does not enforce HTTPS-only access, violating SOC2 data transmission requirements.", "SOC2", "CC6.7"));
                    }
                } catch (Exception e) {
                    findings.add(new DashboardData.SecurityFinding(bucketName, bucketRegion, "S3", "Medium",
                        "S3 bucket does not have a secure transport policy, violating SOC2 data transmission requirements.", "SOC2", "CC6.7"));
                }
            });
        } catch (Exception e) {
            logger.error("SOC2 data transmission check failed for S3: {}", e.getMessage());
        }
        
        activeRegions.forEach(regionStatus -> {
            try {
                RdsClient rds = awsClientProvider.getRdsClient(account, regionStatus.getRegionId());
                List<DBInstance> dbInstances = rds.describeDBInstances().dbInstances();
                dbInstances.forEach(db -> {
                    if (!db.storageEncrypted()) {
                        findings.add(new DashboardData.SecurityFinding(db.dbInstanceIdentifier(), regionStatus.getRegionId(), "RDS", "High",
                            "RDS instance does not have storage encryption enabled, violating SOC2 data protection requirements.", "SOC2", "CC6.7"));
                    }
                });
            } catch (Exception e) {
                logger.warn("Could not check RDS encryption in region {}: {}", regionStatus.getRegionId(), e.getMessage());
            }
        });
        
        return findings;
    }
    
    private List<DashboardData.SecurityFinding> checkSoc2SystemMonitoring(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        List<DashboardData.SecurityFinding> findings = new ArrayList<>();
        
        try {
            CloudTrailClient cloudTrail = awsClientProvider.getCloudTrailClient(account, configuredRegion);
            List<Trail> trails = cloudTrail.describeTrails().trailList();
            
            if (trails.isEmpty()) {
                findings.add(new DashboardData.SecurityFinding("Account", "Global", "CloudTrail", "Critical",
                    "No CloudTrail trails configured, violating SOC2 monitoring requirements.", "SOC2", "CC7.1"));
            } else {
                boolean hasValidTrail = false;
                for (Trail trail : trails) {
                    try {
                        CloudTrailClient regionalTrailClient = awsClientProvider.getCloudTrailClient(account, trail.homeRegion());
                        boolean isLogging = regionalTrailClient.getTrailStatus(r -> r.name(trail.name())).isLogging();
                        boolean hasLogFileValidation = trail.logFileValidationEnabled();
                        
                        if (isLogging && trail.isMultiRegionTrail() && hasLogFileValidation) {
                            hasValidTrail = true;
                            break;
                        }
                        
                        if (isLogging && !hasLogFileValidation) {
                            findings.add(new DashboardData.SecurityFinding(trail.name(), trail.homeRegion(), "CloudTrail", "Medium",
                                "CloudTrail log file validation is not enabled, reducing SOC2 monitoring integrity.", "SOC2", "CC7.1"));
                        }
                    } catch (Exception e) {
                        logger.warn("Could not verify trail {} status: {}", trail.name(), e.getMessage());
                    }
                }
                
                if (!hasValidTrail) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "CloudTrail", "High",
                        "No properly configured multi-region CloudTrail with log file validation found, violating SOC2 monitoring requirements.", "SOC2", "CC7.1"));
                }
            }
        } catch (Exception e) {
            logger.error("SOC2 system monitoring check failed: {}", e.getMessage());
        }
        
        return findings;
    }
    
    private List<DashboardData.SecurityFinding> checkSoc2DataClassification(CloudAccount account) {
        List<DashboardData.SecurityFinding> findings = new ArrayList<>();
        
        try {
            KmsClient kms = awsClientProvider.getKmsClient(account, configuredRegion);
            kms.listKeys().keys().forEach(key -> {
                try {
                    KeyMetadata metadata = kms.describeKey(r -> r.keyId(key.keyId())).keyMetadata();
                    if ("ENCRYPT_DECRYPT".equals(metadata.keyUsageAsString()) &&
                        metadata.keySpec() == KeySpec.SYMMETRIC_DEFAULT &&
                        metadata.originAsString().equals("AWS_KMS")) {
                        try {
                            boolean rotationEnabled = kms.getKeyRotationStatus(r -> r.keyId(key.keyId())).keyRotationEnabled();
                            if (!rotationEnabled) {
                                findings.add(new DashboardData.SecurityFinding(key.keyId(), configuredRegion, "KMS", "Medium",
                                    "KMS key does not have automatic rotation enabled, violating SOC2 key management requirements.", "SOC2", "CC6.8"));
                            }
                        } catch (Exception e) {
                            // This catch block handles potential permissions errors for getKeyRotationStatus
                            logger.warn("Could not get key rotation status for KMS key {}: {}", key.keyId(), e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not check KMS key {}: {}", key.keyId(), e.getMessage());
                }
            });
        } catch (KmsException e) {
            if (e.statusCode() == 400) {
                 logger.warn("SOC2 data classification check for KMS keys skipped due to insufficient permissions: {}", e.awsErrorDetails().errorMessage());
            } else {
                 logger.error("SOC2 data classification check failed for KMS: {}", e.getMessage());
            }
        } catch (Exception e) {
             logger.error("SOC2 data classification check failed for KMS: {}", e.getMessage());
        }
        
        return findings;
    }
    
    private List<DashboardData.SecurityFinding> checkSoc2LogicalAccessSecurity(CloudAccount account) {
        List<DashboardData.SecurityFinding> findings = new ArrayList<>();
        IamClient iam = awsClientProvider.getIamClient(account);
        
        try {
            try {
                PasswordPolicy passwordPolicy = iam.getAccountPasswordPolicy().passwordPolicy();
                
                if (passwordPolicy.minimumPasswordLength() < 14) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "IAM", "Medium",
                        "Password policy minimum length is less than 14 characters, not meeting SOC2 access security standards.", "SOC2", "CC6.3"));
                }
                
                if (!passwordPolicy.requireUppercaseCharacters() || !passwordPolicy.requireLowercaseCharacters() || 
                    !passwordPolicy.requireNumbers() || !passwordPolicy.requireSymbols()) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "IAM", "Medium",
                        "Password policy does not require character complexity, violating SOC2 access security requirements.", "SOC2", "CC6.3"));
                }
                
                if (passwordPolicy.maxPasswordAge() == null || passwordPolicy.maxPasswordAge() > 90) {
                    findings.add(new DashboardData.SecurityFinding("Account", "Global", "IAM", "Medium",
                        "Password policy does not enforce password expiration within 90 days, violating SOC2 access security requirements.", "SOC2", "CC6.3"));
                }
                
            } catch (NoSuchEntityException e) {
                findings.add(new DashboardData.SecurityFinding("Account", "Global", "IAM", "High",
                    "No password policy is configured, violating SOC2 access security requirements.", "SOC2", "CC6.3"));
            }
            
            iam.listPolicies(r -> r.scope("Local")).policies().forEach(policy -> {
                try {
                    String policyDocument = iam.getPolicyVersion(r -> r.policyArn(policy.arn())
                        .versionId(policy.defaultVersionId())).policyVersion().document();
                    
                    if (policyDocument.contains("\"*\"") && policyDocument.contains("\"Effect\": \"Allow\"")) {
                        findings.add(new DashboardData.SecurityFinding(policy.policyName(), "Global", "IAM", "High",
                            "Custom IAM policy grants overly broad permissions (*), violating SOC2 least privilege principles.", "SOC2", "CC6.3"));
                    }
                } catch (AwsServiceException e) {
                    if (e.statusCode() == 403) {
                         logger.warn("Could not check policy {}: Insufficient permissions to get policy version. {}", policy.policyName(), e.awsErrorDetails().errorMessage());
                    } else {
                         logger.warn("Could not check policy {}: {}", policy.policyName(), e.getMessage());
                    }
                }
            });
            
        } catch (AwsServiceException e) {
            if (e.statusCode() == 403) {
                 logger.warn("SOC2 logical access security check skipped due to insufficient permissions: {}", e.awsErrorDetails().errorMessage());
            } else {
                 logger.error("SOC2 logical access security check failed: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.error("SOC2 logical access security check failed: {}", e.getMessage());
        }
        
        return findings;
    }
    
    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
            .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchFunction.apply(regionStatus.getRegionId());
                } catch (AwsServiceException e) {
                    logger.warn("Security Scan failed for account {}: {} in region {}. AWS Error: {}", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e.awsErrorDetails().errorMessage());
                    return Collections.<T>emptyList();
                } catch (Exception e) {
                    logger.error("Security Scan failed for account {}: {} in region {}.", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                    return Collections.<T>emptyList();
                }
            }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }
}