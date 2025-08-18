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
    private String awsAccountId;

    @Column
    private String externalId;

    @Column
    private String accessType;

    @Column(unique = true)
    private String roleArn;

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

    // ✅ ADD THIS FIELD FOR GCP BILLING
    @Column
    private String billingExportTable; 

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
}