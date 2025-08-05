package com.xammer.cloud.dto;

public class GcpAccountRequestDto {

    private String accountName;
    private String gcpProjectId;
    private String gcpWorkloadIdentityPoolId;
    private String gcpWorkloadIdentityProviderId;
    private String gcpServiceAccountEmail;
    private String serviceAccountKey;
    private String projectId;

    // Getters and Setters
    public void setServiceAccountKey(String serviceAccountKey) {
        this.serviceAccountKey = serviceAccountKey;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public void setGcpProjectId(String gcpProjectId) {
        this.gcpProjectId = gcpProjectId;
    }

    public String getGcpWorkloadIdentityPoolId() {
        return gcpWorkloadIdentityPoolId;
    }

    public void setGcpWorkloadIdentityPoolId(String gcpWorkloadIdentityPoolId) {
        this.gcpWorkloadIdentityPoolId = gcpWorkloadIdentityPoolId;
    }

    public String getGcpWorkloadIdentityProviderId() {
        return gcpWorkloadIdentityProviderId;
    }

    public void setGcpWorkloadIdentityProviderId(String gcpWorkloadIdentityProviderId) {
        this.gcpWorkloadIdentityProviderId = gcpWorkloadIdentityProviderId;
    }

    public String getGcpServiceAccountEmail() {
        return gcpServiceAccountEmail;
    }

    public void setGcpServiceAccountEmail(String gcpServiceAccountEmail) {
        this.gcpServiceAccountEmail = gcpServiceAccountEmail;
    }

    public String getServiceAccountKey() {
        return serviceAccountKey;
    }
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }
}