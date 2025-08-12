package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Base64;

@Service
public class EksAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(EksAutomationService.class);

    private final AwsClientProvider awsClientProvider;
    private final KubernetesManifestService manifestService;

    @Autowired
    public EksAutomationService(AwsClientProvider awsClientProvider, KubernetesManifestService manifestService) {
        this.awsClientProvider = awsClientProvider;
        this.manifestService = manifestService;
    }

    public boolean installOpenCost(CloudAccount account, String clusterName, String region) {
        logger.info("Starting OpenCost installation via manifests for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            if (client.apps().deployments().inNamespace("opencost").withName("opencost").get() != null) {
                logger.warn("OpenCost deployment already exists in namespace 'opencost'. Skipping installation.");
                return true;
            }
            manifestService.applyManifests(client, "classpath:kubernetes/opencost/*.yaml");
            logger.info("Successfully applied all OpenCost manifests to cluster {}.", clusterName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to install OpenCost on cluster {}", clusterName, e);
            return false;
        }
    }

    public boolean enableContainerInsights(CloudAccount account, String clusterName, String region) {
        logger.info("Starting automated Container Insights installation for cluster {}", clusterName);
        try (KubernetesClient client = getKubernetesClientForEks(account, clusterName, region)) {
            ClassPathResource resource = new ClassPathResource("kubernetes/container-insights-template.yaml");
            String manifestTemplate;
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                manifestTemplate = FileCopyUtils.copyToString(reader);
            }
            String finalManifest = manifestTemplate
                .replace("{{cluster_name}}", clusterName)
                .replace("{{region_name}}", region)
                .replace("{{http_server_toggle}}", "\"On\"")
                .replace("{{http_server_port}}", "\"2020\"")
                .replace("{{read_from_head}}", "\"Off\"")
                .replace("{{read_from_tail}}", "\"On\"");
            client.load(new java.io.ByteArrayInputStream(finalManifest.getBytes())).createOrReplace();
            logger.info("Successfully applied Container Insights manifests to cluster {}.", clusterName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to enable Container Insights on cluster {}", clusterName, e);
            return false;
        }
    }

    private KubernetesClient getKubernetesClientForEks(CloudAccount account, String clusterName, String region) {
        EksClient eksClient = awsClientProvider.getEksClient(account, region);
        Cluster cluster = eksClient.describeCluster(req -> req.name(clusterName)).cluster();
        String endpoint = cluster.endpoint();
        String caData = cluster.certificateAuthority().data();
        String token = generateEksToken(account, region);

        Config config = new Config();
        config.setMasterUrl(endpoint);
        config.setCaCertData(caData);
        config.setTrustCerts(true);
        config.setOauthToken(token);

        return new DefaultKubernetesClient(config);
    }

    /**
     * Generates a presigned URL that acts as a bearer token for EKS authentication.
     */
    private String generateEksToken(CloudAccount account, String region) {
        try {
            // Get credentials and log access key
            AwsCredentials credentials = awsClientProvider.getCredentialsProvider(account).resolveCredentials();
            logger.info("Java app using access key: {}", credentials.accessKeyId());

            // Check the caller identity to see which IAM role is being used
            try {
                StsClient stsClient = StsClient.builder()
                    .region(software.amazon.awssdk.regions.Region.of(region))
                    .credentialsProvider(awsClientProvider.getCredentialsProvider(account))
                    .build();
                
                GetCallerIdentityResponse identity = stsClient.getCallerIdentity();
                logger.info("Java app caller identity ARN: {}", identity.arn());
                logger.info("Java app caller identity User ID: {}", identity.userId());
                logger.info("Java app caller identity Account: {}", identity.account());
                
                // Test STS access
                logger.debug("STS GetCallerIdentity successful");
            } catch (Exception stsException) {
                logger.error("Failed to get caller identity via STS", stsException);
            }

            String host = "sts." + region + ".amazonaws.com";
            String endpoint = "https://" + host + "/";
            Instant now = Instant.now();
            
            // Format timestamp correctly
            String timestamp = now.atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            
            String dateStamp = now.atZone(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            
            logger.debug("Timestamp: {}, DateStamp: {}", timestamp, dateStamp);
            
            // Build credential scope
            String credentialScope = dateStamp + "/" + region + "/sts/aws4_request";
            logger.debug("Credential scope: {}", credentialScope);
            
            // Build query parameters in EXACT order as AWS CLI
            Map<String, String> params = new LinkedHashMap<>();
            params.put("Action", "GetCallerIdentity");
            params.put("Version", "2011-06-15");
            params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
            params.put("X-Amz-Credential", credentials.accessKeyId() + "/" + credentialScope);
            params.put("X-Amz-Date", timestamp);
            params.put("X-Amz-Expires", "60");
            params.put("X-Amz-SignedHeaders", "host;x-k8s-aws-id");
            
            // Add session token if present (for assumed roles)
            if (credentials instanceof AwsSessionCredentials) {
                AwsSessionCredentials sessionCreds = (AwsSessionCredentials) credentials;
                params.put("X-Amz-Security-Token", sessionCreds.sessionToken());
                logger.debug("Using session credentials for EKS token generation");
                logger.debug("Session token length: {}", sessionCreds.sessionToken().length());
            } else {
                logger.debug("Using basic credentials (no session token)");
            }
            
            // Build query string
            String queryString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
            
            logger.debug("Query string length: {}", queryString.length());
            
            // Build canonical request with the x-k8s-aws-id header
            String canonicalHeaders = "host:" + host + "\n" + "x-k8s-aws-id:\n";
            String canonicalRequest = "GET\n" +
                "/\n" +
                queryString + "\n" +
                canonicalHeaders + "\n" +
                "host;x-k8s-aws-id\n" +
                "UNSIGNED-PAYLOAD";
            
            // Log canonical request for debugging
            logger.debug("Canonical request:\n{}", canonicalRequest);
            
            // Create string to sign
            String stringToSign = "AWS4-HMAC-SHA256\n" +
                timestamp + "\n" +
                credentialScope + "\n" +
                sha256Hex(canonicalRequest);
            
            logger.debug("String to sign:\n{}", stringToSign);
            
            // Calculate signature
            String signature = calculateSignature(credentials.secretAccessKey(), 
                stringToSign, dateStamp, region, "sts");
            
            logger.debug("Generated signature: {}", signature);
            
            // Add signature to query string
            String finalQueryString = queryString + "&X-Amz-Signature=" + signature;
            
            // Build final presigned URL
            String presignedUrl = endpoint + "?" + finalQueryString;
            
            logger.debug("Generated presigned URL: {}", presignedUrl);
            
            // Generate EKS token (same format as AWS CLI)
            String token = "k8s-aws-v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(presignedUrl.getBytes(StandardCharsets.UTF_8));
            
            logger.debug("Generated EKS token (first 50 chars): {}", token.substring(0, Math.min(50, token.length())) + "...");
            logger.info("Successfully generated EKS authentication token");
            
            return token;
            
        } catch (Exception e) {
            logger.error("Failed to generate EKS token", e);
            throw new RuntimeException("Failed to generate EKS token", e);
        }
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
        } catch (Exception e) {
            logger.error("URL encoding failed for value: {}", value, e);
            throw new RuntimeException(e);
        }
    }

    private String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            logger.error("SHA256 hashing failed", e);
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private String calculateSignature(String secretKey, String stringToSign, 
                                    String dateStamp, String region, String service) {
        try {
            byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
            byte[] kDate = hmacSha256(kSecret, dateStamp);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, service);
            byte[] kSigning = hmacSha256(kService, "aws4_request");
            
            byte[] signature = hmacSha256(kSigning, stringToSign);
            return bytesToHex(signature);
        } catch (Exception e) {
            logger.error("Failed to calculate AWS signature", e);
            throw new RuntimeException("Failed to calculate signature", e);
        }
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
