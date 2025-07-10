package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class VerifyAccountRequest {
    private String accountName;
    private String roleArn;
    private String externalId;
}