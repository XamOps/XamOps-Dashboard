package com.xammer.cloud.dto;

public class AccountCreationRequestDto {
    private String accountName;
    private String awsAccountId; // ✅ ADD THIS LINE
    private String accessType;
    private Long clientId;

    // Getters and Setters

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    // ✅ ADD GETTER AND SETTER FOR THE NEW FIELD
    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }
}