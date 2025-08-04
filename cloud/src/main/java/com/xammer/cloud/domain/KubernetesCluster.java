package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Entity
@Data
@NoArgsConstructor
public class KubernetesCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String clusterName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cloud_account_id", nullable = false)
    @JsonIgnore
    private CloudAccount cloudAccount;

    public KubernetesCluster(String clusterName, CloudAccount cloudAccount) {
        this.clusterName = clusterName;
        this.cloudAccount = cloudAccount;
    }
}