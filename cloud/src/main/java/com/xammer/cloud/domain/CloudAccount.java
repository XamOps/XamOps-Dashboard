package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Entity
@Data
@NoArgsConstructor
public class CloudAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountName;

    @Column(unique = true)
    private String awsAccountId; // For AWS this is account ID, for GCP this is Project ID

    @Column
    private String externalId;

    @Column
    private String accessType;

    @Column(unique = true)
    private String roleArn;

    // This is the field that was causing the error. The @Lob annotation has been removed.
    @Column(columnDefinition = "TEXT")
    private String gcpServiceAccountKey;

    @Column
    private String gcpWorkloadIdentityPoolId;

    @Column
    private String gcpWorkloadIdentityProviderId;

    @Column
    private String gcpServiceAccountEmail;

    @Column
    private String gcpProjectId;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, CONNECTED, FAILED

    @Column(nullable = false)
    private String provider; // AWS or GCP

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    public CloudAccount(String accountName, String externalId, String accessType, Client client) {
        this.accountName = accountName;
        this.externalId = externalId;
        this.accessType = accessType;
        this.client = client;
    }
    public String getGcpProjectId() {
        return gcpProjectId;
    }

    public void setGcpProjectId(String gcpProjectId) {
        this.gcpProjectId = gcpProjectId;
    }
}