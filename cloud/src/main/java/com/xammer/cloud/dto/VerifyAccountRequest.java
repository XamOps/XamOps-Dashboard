package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class VerifyAccountRequest {
    private String awsAccountId; // The AWS Account ID of the account to connect
    private String roleName;     // The name of the role created by CloudFormation
    private String externalId;   // The unique external ID for verification
}
