package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData;
import org.springframework.stereotype.Service;

@Service
public class RemediationService {

    /**
     * Generates a formatted HTML string with remediation steps for a given security finding.
     * @param finding The security finding to generate steps for.
     * @return An HTML string containing the remediation guide.
     */
    public String getRemediationForFinding(DashboardData.SecurityFinding finding) {
        String genericSteps = String.format(
            "<p><strong>1. Identify the Resource:</strong> Locate the resource <strong>%s</strong> in the AWS console in the <strong>%s</strong> region.</p>" +
            "<p><strong>2. Analyze the Finding:</strong> Understand why this is flagged. The description is: <em>\"%s\"</em>.</p>" +
            "<p><strong>3. Follow Best Practices:</strong> Consult the official AWS documentation for the service (<strong>%s</strong>) and the compliance framework (<strong>%s</strong>) to apply the recommended fix.</p>" +
            "<p><strong>4. Verify the Fix:</strong> After applying changes, re-run the security scan or wait for the next scheduled scan to confirm the issue is resolved.</p>",
            finding.getResourceId(), finding.getRegion(), finding.getDescription(), finding.getCategory(), finding.getComplianceFramework()
        );

        String findingKey = String.format("%s-%s", finding.getComplianceFramework(), finding.getControlId());
        String specificSteps = getSpecificSteps(findingKey);

        if (!specificSteps.isEmpty()) {
            return String.format("<h4>Specific Guidance:</h4>%s<hr class=\"my-4\"><h4>General Steps:</h4>%s", specificSteps, genericSteps);
        } else {
            return String.format("<h4>General Steps:</h4>%s", genericSteps);
        }
    }

    private String getSpecificSteps(String key) {
        switch (key) {
            case "CIS AWS Foundations-1.2":
                return "<p><strong>Specific Action:</strong> Navigate to the IAM user and enforce Multi-Factor Authentication (MFA). This is critical for preventing unauthorized access.</p>";
            case "CIS AWS Foundations-2.1.2":
                return "<p><strong>Specific Action:</strong> Go to the S3 bucket settings. Enable \"Block all public access\" and review bucket policies and ACLs to remove public grants.</p>";
            case "CIS AWS Foundations-4.1":
                return "<p><strong>Specific Action:</strong> Edit the inbound rules for the security group. Restrict the source IP range from '0.0.0.0/0' to a specific, known IP address or range. Avoid opening ports like SSH (22) or RDP (3389) to the world.</p>";
            case "CIS AWS Foundations-2.9":
                return "<p><strong>Specific Action:</strong> In the VPC console, select the VPC and choose to create a new Flow Log. Configure it to send logs to a CloudWatch Log Group or an S3 bucket for traffic analysis.</p>";
            default:
                return "";
        }
    }
}
